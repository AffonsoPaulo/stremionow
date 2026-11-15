// SPDX-License-Identifier: GPL-3.0-or-later
//! End-to-end tests of serving to other devices, over TLS on loopback and without the
//! BitTorrent network.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use reqwest::StatusCode;
use tokio::io::AsyncReadExt;

use super::*;
use crate::lan::client::{PairError, RemoteError, RemoteHealth, RemoteServer, pair};
use crate::lan::relay::TorrentRoute;
use crate::lan::tls::PinnedVerifier;
use crate::server::tests::{cached_torrent, film, get, offline_server};

/// A server on loopback, with the states it handed to the app.
struct Harness {
    lan: LanServer,
    stream: StreamServer,
    saved: Arc<Mutex<Vec<String>>>,
    _cache: tempfile::TempDir,
}

impl Harness {
    /// A server turned on now, on loopback's stand-in home network.
    async fn start(saved_state: Option<String>) -> Self {
        Self::start_with(saved_state, None, true).await
    }

    /// `network` stands in for the network the device is on (see `BindPolicy::Loopback`).
    async fn start_with(
        saved_state: Option<String>,
        network: Option<&str>,
        turned_on: bool,
    ) -> Self {
        let (stream, cache) = offline_server(0).await;
        let saved = Arc::new(Mutex::new(Vec::new()));
        let sink = Arc::clone(&saved);
        let config = LanConfig {
            device_name: "Living room PC".to_owned(),
            saved_state,
            on_state_change: Box::new(move |state| sink.lock().expect("lock").push(state)),
            network_hint: network.map(str::to_owned),
            serve_on_current_network: turned_on,
        };
        let lan = LanServer::start(stream.clone(), config, BindPolicy::Loopback)
            .await
            .expect("lan server starts");
        Self {
            lan,
            stream,
            saved,
            _cache: cache,
        }
    }

    fn address(&self) -> String {
        format!("127.0.0.1:{}", self.lan.status().port)
    }

    fn last_saved(&self) -> String {
        self.saved
            .lock()
            .expect("lock")
            .last()
            .cloned()
            .expect("a saved state")
    }

    async fn pair(&self, name: &str) -> RemoteServer {
        let code = self.lan.new_pairing_code().expect("code").code;
        pair(&self.address(), &code, name).await.expect("paired")
    }
}

/// A client that trusts only the harness's certificate, to send raw requests.
fn pinned_client(harness: &Harness) -> reqwest::Client {
    let verifier = PinnedVerifier::pinned(harness.lan.fingerprint());
    reqwest::Client::builder()
        .tls_backend_preconfigured(super::super::tls::client_config(verifier).expect("tls"))
        .no_proxy()
        .build()
        .expect("client")
}

/// The token inside a saved pairing.
fn token_of(remote: &RemoteServer) -> String {
    let saved: serde_json::Value = serde_json::from_str(&remote.save()).expect("json");
    saved["token"].as_str().expect("token").to_owned()
}

/// The same pairing, pointed at another harness.
fn moved(remote: &RemoteServer, to: &Harness) -> RemoteServer {
    let saved = remote
        .save()
        .replace(&remote.address().to_string(), &to.address());
    RemoteServer::load(&saved).expect("pairing")
}

fn wrong_code(code: &str) -> String {
    if code == "000000" { "000001" } else { "000000" }.to_owned()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pairing_trades_the_code_for_a_token_once() {
    let harness = Harness::start(None).await;
    // A first run creates an identity and hands it to the app at once.
    assert_eq!(harness.saved.lock().expect("lock").len(), 1);
    let status = harness.lan.status();
    assert_eq!(status.device_name, "Living room PC");
    assert_eq!(status.addresses, vec![Ipv4Addr::LOCALHOST]);
    assert!(status.devices.is_empty());

    let code = harness.lan.new_pairing_code().expect("code");
    assert_eq!(code.code.len(), 6);
    assert_eq!(
        harness.lan.status().pairing.map(|p| p.code),
        Some(code.code.clone())
    );
    let remote = pair(&harness.address(), &code.code, "Fire TV")
        .await
        .expect("paired");
    assert_eq!(remote.name(), "Living room PC");
    assert_eq!(remote.server_id(), harness.lan.status().server_id);
    assert_eq!(remote.check().await, RemoteHealth::Ready);

    let status = harness.lan.status();
    assert_eq!(status.pairing, None, "the code is used up");
    assert_eq!(status.devices.len(), 1);
    assert_eq!(status.devices[0].name, "Fire TV");
    // The new device was saved, without its token.
    let saved = harness.last_saved();
    assert!(saved.contains("Fire TV"));
    assert!(!saved.contains(&token_of(&remote)));

    assert_eq!(
        pair(&harness.address(), &code.code, "Intruder").await.err(),
        Some(PairError::NoCode),
        "a code works once"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn wrong_codes_are_refused_and_counted() {
    let harness = Harness::start(None).await;
    let code = harness.lan.new_pairing_code().expect("code").code;
    assert_eq!(
        pair(&harness.address(), &wrong_code(&code), "Guess")
            .await
            .err(),
        Some(PairError::WrongCode)
    );
    assert!(harness.lan.status().devices.is_empty());
    // The right code still works after a wrong one; the rate limit is waited out.
    assert!(pair(&harness.address(), &code, "TV").await.is_ok());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pairing_needs_a_code_on_screen() {
    let harness = Harness::start(None).await;
    assert_eq!(
        pair(&harness.address(), "123456", "TV").await.err(),
        Some(PairError::NoCode)
    );
    let code = harness.lan.new_pairing_code().expect("code").code;
    harness.lan.cancel_pairing();
    assert_eq!(
        pair(&harness.address(), &code, "TV").await.err(),
        Some(PairError::NoCode)
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn every_route_but_pairing_needs_a_valid_token() {
    let harness = Harness::start(None).await;
    let remote = harness.pair("TV").await;
    let client = pinned_client(&harness);
    let base = format!("https://{}", harness.address());
    let token = token_of(&remote);
    let wrong = "0".repeat(64);
    for (method, path) in [
        (reqwest::Method::GET, "/v1/hello".to_owned()),
        (reqwest::Method::POST, "/v1/torrents".to_owned()),
        (
            reqwest::Method::GET,
            format!("/v1/torrents/{}", "1".repeat(40)),
        ),
        (
            reqwest::Method::GET,
            format!("/v1/torrents/{}/0", "1".repeat(40)),
        ),
        (reqwest::Method::DELETE, "/v1/me".to_owned()),
    ] {
        for auth in [
            None,
            Some(wrong.as_str()),
            Some(&token[..63]),
            Some("Basic x"),
        ] {
            let mut request = client.request(method.clone(), format!("{base}{path}"));
            if let Some(auth) = auth {
                request = request.header(header::AUTHORIZATION, format!("Bearer {auth}"));
            }
            let response = request.send().await.expect("answer");
            assert_eq!(
                response.status(),
                StatusCode::UNAUTHORIZED,
                "{method} {path} {auth:?}"
            );
        }
    }
    let hello = client
        .get(format!("{base}/v1/hello"))
        .bearer_auth(&token)
        .send()
        .await
        .expect("answer");
    assert_eq!(hello.status(), StatusCode::OK);

    // No route fetches URLs: the server is not a proxy.
    for path in [
        "/v1/proxy/x",
        "/proxy/x",
        "/v1/fetch?url=http://192.168.1.1/",
    ] {
        let response = client
            .get(format!("{base}{path}"))
            .bearer_auth(&token)
            .send()
            .await
            .expect("answer");
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
    let response = client
        .post(format!("{base}/v1/torrents"))
        .bearer_auth(&token)
        .body("not json")
        .send()
        .await
        .expect("answer");
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let response = client
        .post(format!("{base}/v1/torrents"))
        .bearer_auth(&token)
        .body(vec![b' '; MAX_BODY_BYTES + 1])
        .send()
        .await
        .expect("answer");
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn removed_devices_and_reset_servers_must_pair_again() {
    let harness = Harness::start(None).await;
    let tv = harness.pair("TV").await;
    let phone = {
        tokio::time::sleep(pairing::MIN_ATTEMPT_INTERVAL).await;
        harness.pair("Phone").await
    };
    let tv_id = harness
        .lan
        .status()
        .devices
        .iter()
        .find(|device| device.name == "TV")
        .map(|device| device.id.clone())
        .expect("tv");
    assert!(harness.lan.remove_device(&tv_id).await);
    assert_eq!(tv.check().await, RemoteHealth::Unpaired);
    assert_eq!(phone.check().await, RemoteHealth::Ready);
    assert!(!harness.last_saved().contains("\"TV\""));

    // The same state after a restart keeps the phone paired.
    let saved = harness.last_saved();
    harness.lan.stop().await;
    drop(harness);
    let restarted = Harness::start(Some(saved)).await;
    let phone = moved(&phone, &restarted);
    assert_eq!(phone.check().await, RemoteHealth::Ready);

    // A reset server has a new certificate. The client notices, from that very request,
    // and can look for its server elsewhere before asking the user to pair again.
    let reset = Harness::start(None).await;
    assert_eq!(
        moved(&phone, &reset).check().await,
        RemoteHealth::CertificateChanged
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_paired_device_streams_through_its_loopback_relay() {
    let harness = Harness::start(None).await;
    let content = film(200_000);
    let (hash, _) = cached_torrent(&harness.stream, harness._cache.path(), &content).await;
    let remote = harness.pair("TV").await;
    let (client, _client_cache) = offline_server(0).await;

    let url = client
        .remote_torrent_url(
            &remote,
            &hash,
            None,
            None,
            &["tracker:udp://localhost:1/announce".to_owned()],
        )
        .await
        .expect("remote url");
    assert!(
        url.starts_with("http://127.0.0.1:"),
        "the player gets a loopback URL"
    );
    assert!(
        !url.contains(&token_of(&remote)),
        "the token stays out of the URL"
    );
    assert_eq!(
        client.torrent_route(&hash),
        Some(TorrentRoute::Remote {
            server_name: "Living room PC".to_owned()
        })
    );

    let response = get(&url, Some("bytes=1000-1999")).await;
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(
        response
            .headers()
            .get(header::CONTENT_RANGE)
            .and_then(|value| value.to_str().ok()),
        Some("bytes 1000-1999/200000")
    );
    assert_eq!(response.bytes().await.expect("body"), content[1000..2000]);
    let whole = get(&url, None).await;
    assert_eq!(whole.status(), StatusCode::OK);
    assert_eq!(whole.bytes().await.expect("body"), content);

    // A device cannot take every stream slot. Each open body holds a clone of its
    // holding's counter; hold them here as a player streaming slowly would.
    let open: Vec<Arc<()>> = {
        let locked = harness.lan.inner.locked();
        let holding = locked.holdings.values().next().expect("holding");
        (0..MAX_STREAMS_PER_DEVICE)
            .map(|_| Arc::clone(&holding.streams))
            .collect()
    };
    assert!(harness.lan.status().devices[0].streaming);
    assert_eq!(
        get(&url, None).await.status(),
        StatusCode::SERVICE_UNAVAILABLE,
        "the server's refusal reaches the player"
    );
    drop(open);
    assert_eq!(
        get(&url, Some("bytes=0-0")).await.status(),
        StatusCode::PARTIAL_CONTENT
    );

    // Progress arrives through the poller.
    let mut status = None;
    for _ in 0..30 {
        status = client.status(&hash);
        if status.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let status = status.expect("remote status");
    assert_eq!(status.file_size, 200_000);
    assert_eq!(harness.lan.status().devices.len(), 1);

    // Stopping on the client stops it on the server.
    client.stop(&hash).await;
    assert_eq!(client.status(&hash), None);
    assert_eq!(get(&url, None).await.status(), StatusCode::NOT_FOUND);
    assert_eq!(harness.stream.status(&hash), None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn playback_continues_locally_when_the_server_goes_away() {
    let harness = Harness::start(None).await;
    let content = film(100_000);
    let (hash, _) = cached_torrent(&harness.stream, harness._cache.path(), &content).await;
    let remote = harness.pair("TV").await;
    let (client, client_cache) = offline_server(0).await;
    // The client has the data too, as a peer would provide it.
    let _ = cached_torrent(&client, client_cache.path(), &content).await;
    let url = client
        .remote_torrent_url(&remote, &hash, None, None, &[])
        .await
        .expect("remote url");

    client.set_local_torrents_allowed(false);
    harness.lan.stop().await;
    assert_eq!(
        get(&url, None).await.status(),
        StatusCode::BAD_GATEWAY,
        "no local download when local torrents are not allowed"
    );

    client.set_local_torrents_allowed(true);
    let response = get(&url, Some("bytes=0-99")).await;
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(response.bytes().await.expect("body"), content[..100]);
    assert_eq!(
        client.torrent_route(&hash),
        Some(TorrentRoute::FellBack {
            server_name: "Living room PC".to_owned()
        })
    );
    assert!(client.status(&hash).is_some());
    client.stop(&hash).await;
    assert_eq!(client.torrent_route(&hash), None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_torrent_used_by_two_devices_stops_with_the_last() {
    let harness = Harness::start(None).await;
    let content = film(50_000);
    let (hash, _) = cached_torrent(&harness.stream, harness._cache.path(), &content).await;
    // This device plays it too.
    harness
        .stream
        .shared
        .add_for(
            InfoHash::parse(&hash).expect("hash"),
            Holder::Local,
            FileRequest::default(),
            &[],
        )
        .await
        .expect("local");
    let remote = harness.pair("TV").await;
    let (client, _client_cache) = offline_server(0).await;
    client
        .remote_torrent_url(&remote, &hash, None, None, &[])
        .await
        .expect("remote url");
    client.stop(&hash).await;
    assert!(
        harness.stream.status(&hash).is_some(),
        "still playing on the server device"
    );
    harness.stream.stop(&hash).await;
    assert_eq!(harness.stream.status(&hash), None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn torrents_are_refused_while_paused() {
    let harness = Harness::start(None).await;
    let remote = harness.pair("TV").await;
    harness.lan.set_torrents_allowed(false);
    let (client, _cache) = offline_server(0).await;
    let result = client
        .remote_torrent_url(
            &remote,
            "1111111111111111111111111111111111111111",
            None,
            None,
            &[],
        )
        .await;
    assert_eq!(result.err(), Some(RemoteError::Paused));
    let result = client
        .remote_torrent_url(&remote, "not a hash", None, None, &[])
        .await;
    assert!(matches!(result, Err(RemoteError::Torrent(_))));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn idle_and_excess_connections_are_dropped() {
    let harness = Harness::start(None).await;
    let address = harness.address();
    // Connections that never start TLS hold slots only until the handshake timeout.
    let mut idle = Vec::new();
    for _ in 0..super::super::limits::MAX_PER_PEER {
        idle.push(TcpStream::connect(&address).await.expect("connect"));
    }
    tokio::time::sleep(Duration::from_millis(200)).await;
    let mut extra = TcpStream::connect(&address).await.expect("connect");
    let mut buf = [0u8; 1];
    let closed = tokio::time::timeout(Duration::from_secs(2), extra.read(&mut buf)).await;
    assert!(
        matches!(closed, Ok(Ok(0) | Err(_))),
        "a connection over the per-device limit is closed at once"
    );

    let started = Instant::now();
    let closed = tokio::time::timeout(
        HANDSHAKE_TIMEOUT + Duration::from_secs(3),
        idle[0].read(&mut buf),
    )
    .await;
    assert!(
        matches!(closed, Ok(Ok(0) | Err(_))),
        "idle connections are closed"
    );
    assert!(started.elapsed() >= HANDSHAKE_TIMEOUT - Duration::from_secs(1));
    drop(idle);
    tokio::time::sleep(Duration::from_millis(200)).await;
    let code = harness.lan.new_pairing_code().expect("code").code;
    assert!(pair(&address, &code, "TV").await.is_ok(), "slots are freed");
}

#[test]
fn the_oldest_torrent_of_a_device_makes_room() {
    let now = Instant::now();
    let hashes: Vec<InfoHash> = (1..=4)
        .map(|i| InfoHash::parse(&format!("{i}").repeat(40)).expect("hash"))
        .collect();
    let mut holdings = HashMap::new();
    for (age, hash) in hashes.iter().take(MAX_TORRENTS_PER_DEVICE).enumerate() {
        holdings.insert(
            ("tv".to_owned(), *hash),
            Holding {
                last_used: now - Duration::from_secs(100 - age as u64),
                streams: Arc::new(()),
            },
        );
    }
    assert_eq!(
        oldest_beyond_limit(&holdings, "tv", hashes[3]),
        Some(hashes[0])
    );
    assert_eq!(
        oldest_beyond_limit(&holdings, "tv", hashes[1]),
        None,
        "already held"
    );
    assert_eq!(oldest_beyond_limit(&holdings, "phone", hashes[3]), None);
}

/// Waits for the server to have reconciled with a network change.
async fn settle(harness: &Harness, paused: Option<PauseReason>) {
    for _ in 0..50 {
        if harness.lan.status().paused == paused {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("still {:?}", harness.lan.status().paused);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn serving_follows_the_network_where_it_was_turned_on() {
    let harness = Harness::start_with(None, Some("home"), true).await;
    assert_eq!(harness.lan.status().paused, None);
    let remote = harness.pair("TV").await;

    // Another network: nothing listens, and the state says why.
    harness.lan.set_network_hint(Some("cafe".to_owned()));
    settle(&harness, Some(PauseReason::OtherNetwork)).await;
    assert!(harness.lan.status().addresses.is_empty());
    assert_eq!(remote.check().await, RemoteHealth::Unreachable);
    // No raw connect check here: tests run in parallel, and on Windows another test's server
    // can be given the freed port at once. The pinned client above is what must fail.

    // Back home, it serves again on its own.
    harness.lan.set_network_hint(Some("home".to_owned()));
    settle(&harness, None).await;
    assert_eq!(remote.check().await, RemoteHealth::Ready);

    // Only the user makes another network the home network.
    harness.lan.set_network_hint(Some("office".to_owned()));
    settle(&harness, Some(PauseReason::OtherNetwork)).await;
    harness
        .lan
        .serve_on_this_network()
        .await
        .expect("serves here");
    assert_eq!(harness.lan.status().paused, None);
    harness.lan.set_network_hint(Some("home".to_owned()));
    settle(&harness, Some(PauseReason::OtherNetwork)).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_restart_never_adopts_the_current_network() {
    // Serving restored at launch, on a network the user never chose, stays paused.
    let harness = Harness::start_with(None, Some("hotel"), false).await;
    assert_eq!(harness.lan.status().paused, Some(PauseReason::OtherNetwork));
    assert!(harness.lan.status().addresses.is_empty());

    // The home network is part of the saved state.
    let home = Harness::start_with(None, Some("home"), true).await;
    let saved = home.last_saved();
    home.lan.stop().await;
    drop(home);
    let restarted = Harness::start_with(Some(saved.clone()), Some("home"), false).await;
    assert_eq!(restarted.lan.status().paused, None);
    restarted.lan.stop().await;
    drop(restarted);
    let elsewhere = Harness::start_with(Some(saved), Some("hotel"), false).await;
    assert_eq!(
        elsewhere.lan.status().paused,
        Some(PauseReason::OtherNetwork)
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn saves_arrive_in_order() {
    let harness = Arc::new(Harness::start(None).await);
    let mut devices = Vec::new();
    for i in 0..3 {
        tokio::time::sleep(pairing::MIN_ATTEMPT_INTERVAL).await;
        let code = harness.lan.new_pairing_code().expect("code").code;
        pair(&harness.address(), &code, &format!("Device {i}"))
            .await
            .expect("paired");
        devices.push(format!("Device {i}"));
    }
    let ids: Vec<String> = harness
        .lan
        .status()
        .devices
        .into_iter()
        .map(|device| device.id)
        .collect();
    let removals = ids.iter().map(|id| {
        let harness = Arc::clone(&harness);
        let id = id.clone();
        tokio::spawn(async move { harness.lan.remove_device(&id).await })
    });
    for removal in removals {
        assert!(removal.await.expect("task"));
    }
    // Whatever order the removals ran in, the last state handed over has none of them.
    let last = harness.last_saved();
    for name in devices {
        assert!(!last.contains(&name), "{name} came back");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn removing_a_device_ends_its_open_streams() {
    let harness = Harness::start(None).await;
    let content = film(16 * 1024 * 1024);
    let (hash, _) = cached_torrent(&harness.stream, harness._cache.path(), &content).await;
    let remote = harness.pair("TV").await;
    let (client, _client_cache) = offline_server(0).await;
    let url = client
        .remote_torrent_url(&remote, &hash, None, None, &[])
        .await
        .expect("remote url");
    let mut response = crate::server::tests::client()
        .get(&url)
        .send()
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::OK);
    let first = response.chunk().await.expect("chunk").expect("data");
    assert!(!first.is_empty());
    let id = harness.lan.status().devices[0].id.clone();
    assert!(harness.lan.remove_device(&id).await);
    let mut received = first.len();
    while let Ok(Some(chunk)) = response.chunk().await {
        received += chunk.len();
    }
    assert!(received < content.len(), "the stream ended early");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn gone_torrents_and_switched_files_answer_differently() {
    let harness = Harness::start(None).await;
    let (hash, _) = cached_torrent(&harness.stream, harness._cache.path(), &film(50_000)).await;
    let remote = harness.pair("TV").await;
    let (client, _client_cache) = offline_server(0).await;
    client
        .remote_torrent_url(&remote, &hash, None, None, &[])
        .await
        .expect("remote url");
    let raw = pinned_client(&harness);
    let base = format!("https://{}", harness.address());
    let token = token_of(&remote);
    let error = |response: reqwest::Response| async move {
        let status = response.status();
        let body: wire::Failure = response.json().await.expect("json");
        assert_eq!(body.message, None, "no details leave the server");
        (status, body.error)
    };
    let other_file = raw
        .get(format!("{base}/v1/torrents/{hash}/1"))
        .bearer_auth(&token)
        .send()
        .await
        .expect("answer");
    assert_eq!(
        error(other_file).await,
        (StatusCode::CONFLICT, "file_switched".to_owned())
    );
    // A torrent that stopped is gone, not switched: the relay may add it again.
    harness
        .stream
        .shared
        .engine
        .stop(InfoHash::parse(&hash).expect("hash"))
        .await;
    let gone = raw
        .get(format!("{base}/v1/torrents/{hash}/0"))
        .bearer_auth(&token)
        .send()
        .await
        .expect("answer");
    assert_eq!(
        error(gone).await,
        (StatusCode::NOT_FOUND, "not_found".to_owned())
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn failed_torrents_report_only_a_code() {
    let harness = Harness::start(None).await;
    let remote = harness.pair("TV").await;
    let raw = pinned_client(&harness);
    let response = raw
        .post(format!("https://{}/v1/torrents", harness.address()))
        .bearer_auth(token_of(&remote))
        .json(&serde_json::json!({
            "info_hash": "1111111111111111111111111111111111111111",
            "file_idx": null,
            "trackers": ["tracker:udp://127.0.0.1:1/announce"]
        }))
        .send()
        .await
        .expect("answer");
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let body: wire::Failure = response.json().await.expect("json");
    assert_eq!(body.message, None);
    assert!(!body.error.is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_port_taken_while_paused_is_chosen_again() {
    let harness = Harness::start(None).await;
    let port = harness.lan.status().port;
    assert_ne!(port, 0);
    // Serving pauses (the network went away), and another program takes the port.
    let listeners: Vec<Listener> = std::mem::take(&mut *lock(&harness.lan.inner.listeners))
        .into_values()
        .collect();
    for listener in listeners {
        listener.close().await;
    }
    let _taken = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, port)).expect("take port");

    harness.lan.inner.reconcile().await;
    let status = harness.lan.status();
    assert_ne!(status.port, port, "a new port");
    assert_ne!(status.port, 0);
    assert_eq!(status.addresses, [Ipv4Addr::LOCALHOST]);
    assert_eq!(status.paused, None);
    // Devices reach it on the new port.
    tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, status.port))
        .await
        .expect("listening on the new port");
}
