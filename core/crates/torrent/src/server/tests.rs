// SPDX-License-Identifier: GPL-3.0-or-later
//! End-to-end tests of the HTTP routes, run without the BitTorrent network.

use std::fs;
use std::path::Path;
use std::time::Duration;

use librqbit::spawn_utils::BlockingSpawner;
use librqbit::{CreateTorrentOptions, create_torrent};
use reqwest::StatusCode;
use wiremock::matchers::{header as has_header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::*;
use crate::TorrentState;
use crate::files::TorrentFile;
use crate::torrent::DATA_DIR;

const HASH: &str = "08ada5a7a6183aae1e09d831df6748d566095a10";

pub(crate) async fn offline_server(max_cache_bytes: u64) -> (StreamServer, tempfile::TempDir) {
    let cache = tempfile::tempdir().expect("temp dir");
    let config = ServerConfig {
        cache_dir: cache.path().to_path_buf(),
        max_cache_bytes,
    };
    let server = StreamServer::start_with(config, Network::Offline)
        .await
        .expect("server starts");
    (server, cache)
}

/// A plain client; built after the server, which installs the TLS provider reqwest needs.
pub(crate) fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("client")
}

fn origin(server: &StreamServer) -> String {
    format!("http://127.0.0.1:{}", server.shared.port)
}

pub(crate) async fn get(url: &str, range: Option<&str>) -> reqwest::Response {
    let mut request = client().get(url);
    if let Some(range) = range {
        request = request.header(header::RANGE, range);
    }
    request.send().await.expect("request")
}

fn header_of(response: &reqwest::Response, name: header::HeaderName) -> Option<String> {
    let value = response.headers().get(name)?;
    Some(value.to_str().expect("ASCII header").to_owned())
}

/// The bytes of a fake film: a pattern that reveals any offset mistake.
pub(crate) fn film(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i % 251) as u8).collect()
}

/// Adds a torrent whose data is already in the cache, as if downloaded earlier, and returns
/// its info hash and stream URL.
pub(crate) async fn cached_torrent(
    server: &StreamServer,
    cache: &Path,
    content: &[u8],
) -> (String, String) {
    let source = tempfile::tempdir().expect("temp dir");
    let file = source.path().join("movie.mkv");
    fs::write(&file, content).expect("write film");
    let options = CreateTorrentOptions {
        name: None,
        trackers: Vec::new(),
        piece_length: Some(32 * 1024),
    };
    let created = create_torrent(&file, options, &BlockingSpawner::new(1))
        .await
        .expect("create torrent");
    let hash = InfoHash::parse(&created.info_hash().as_string()).expect("hash");
    let folder = cache.join(DATA_DIR).join(hash.to_string());
    fs::create_dir_all(&folder).expect("create folder");
    fs::copy(&file, folder.join("movie.mkv")).expect("copy film");
    let files = vec![TorrentFile {
        path: "movie.mkv".to_owned(),
        len: content.len() as u64,
        padding: false,
    }];
    let torrent = created.as_bytes().expect("torrent bytes");
    let index = server
        .shared
        .engine
        .add_torrent_file(hash, torrent, files)
        .await
        .expect("torrent added");
    let url = format!("{}/torrent/{hash}/{index}", server.shared.base_url);
    (hash.to_string(), url)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn requests_without_the_secret_are_not_found() {
    let (server, _cache) = offline_server(0).await;
    let proxied = server
        .proxy_url("http://127.0.0.1:9/movie.mkv", true)
        .expect("proxy url");
    let token = proxied.rsplit('/').next().expect("token");
    let origin = origin(&server);
    let secret = &server.shared.secret;
    let wrong_secret = "0".repeat(secret.len());
    for path in [
        String::new(),
        "/".to_owned(),
        format!("/proxy/{token}"),
        format!("/{wrong_secret}/proxy/{token}"),
        format!("/{}/proxy/{token}", &secret[..secret.len() - 1]),
        format!("/{secret}x/proxy/{token}"),
        format!("/{wrong_secret}/torrent/{HASH}/0"),
    ] {
        let response = get(&format!("{origin}{path}"), None).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn unknown_routes_and_tokens_are_not_found() {
    let (server, _cache) = offline_server(0).await;
    let base = &server.shared.base_url;
    let unknown_token = "f".repeat(32);
    for path in [
        String::new(),
        "/".to_owned(),
        "/proxy".to_owned(),
        format!("/proxy/{unknown_token}"),
        format!("/proxy/{unknown_token}/extra"),
        format!("/torrent/{HASH}/0"),
        format!("/torrent/{HASH}"),
        "/torrent/not-a-hash/0".to_owned(),
        "/files/etc/passwd".to_owned(),
    ] {
        let response = get(&format!("{base}{path}"), None).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_refuses_non_http_sources() {
    let (server, _cache) = offline_server(0).await;
    for url in ["file:///etc/passwd", "ftp://example.org/a.mkv", "not a url"] {
        assert!(
            matches!(
                server.proxy_url(url, true),
                Err(TorrentError::UnsupportedUrl)
            ),
            "{url}"
        );
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_forwards_ranges_and_partial_answers() {
    let upstream = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/movie.mkv"))
        .and(has_header("range", "bytes=100-199"))
        .and(has_header("accept-encoding", "identity"))
        .respond_with(
            ResponseTemplate::new(206)
                .insert_header("content-range", "bytes 100-199/1000")
                .insert_header("accept-ranges", "bytes")
                .insert_header("x-internal", "not forwarded")
                .set_body_raw(film(100), "video/x-matroska"),
        )
        .expect(1)
        .mount(&upstream)
        .await;
    let (server, _cache) = offline_server(0).await;
    let url = server
        .proxy_url(&format!("{}/movie.mkv", upstream.uri()), true)
        .expect("proxy url");

    let response = get(&url, Some("bytes=100-199")).await;
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(
        header_of(&response, header::CONTENT_RANGE).as_deref(),
        Some("bytes 100-199/1000")
    );
    assert_eq!(
        header_of(&response, header::CONTENT_TYPE).as_deref(),
        Some("video/x-matroska")
    );
    assert_eq!(
        header_of(&response, header::ACCEPT_RANGES).as_deref(),
        Some("bytes")
    );
    assert_eq!(
        header_of(&response, header::CONTENT_LENGTH).as_deref(),
        Some("100")
    );
    assert!(response.headers().get("x-internal").is_none());
    assert_eq!(response.bytes().await.expect("body"), film(100));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_follows_redirects_and_answers_head() {
    let upstream = MockServer::start().await;
    Mock::given(path("/old"))
        .respond_with(ResponseTemplate::new(302).insert_header("location", "/new"))
        .mount(&upstream)
        .await;
    Mock::given(path("/new"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(film(1000), "video/mp4"))
        .mount(&upstream)
        .await;
    let (server, _cache) = offline_server(0).await;
    let url = server
        .proxy_url(&format!("{}/old", upstream.uri()), true)
        .expect("proxy url");

    let response = get(&url, None).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.bytes().await.expect("body"), film(1000));

    let response = client().head(&url).send().await.expect("request");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        header_of(&response, header::CONTENT_LENGTH).as_deref(),
        Some("1000")
    );
    assert_eq!(
        header_of(&response, header::CONTENT_TYPE).as_deref(),
        Some("video/mp4")
    );
    assert!(response.bytes().await.expect("body").is_empty());

    let response = client().post(&url).send().await.expect("request");
    assert_eq!(response.status(), StatusCode::METHOD_NOT_ALLOWED);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_reports_unreachable_sources() {
    let (server, _cache) = offline_server(0).await;
    // Bind and drop a listener to find a port nothing listens on.
    let closed = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = closed.local_addr().expect("address").port();
    drop(closed);
    let url = server
        .proxy_url(&format!("http://127.0.0.1:{port}/movie.mkv"), true)
        .expect("proxy url");
    assert_eq!(get(&url, None).await.status(), StatusCode::BAD_GATEWAY);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn torrent_files_are_served_with_ranges() {
    let (server, cache) = offline_server(0).await;
    let content = film(300_000);
    let (_, url) = cached_torrent(&server, cache.path(), &content).await;

    let response = get(&url, None).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        header_of(&response, header::CONTENT_TYPE).as_deref(),
        Some("video/x-matroska")
    );
    assert_eq!(
        header_of(&response, header::ACCEPT_RANGES).as_deref(),
        Some("bytes")
    );
    assert_eq!(
        header_of(&response, header::CONTENT_LENGTH).as_deref(),
        Some("300000")
    );
    assert_eq!(response.bytes().await.expect("body"), content);

    for (range, start, end) in [
        ("bytes=100-199", 100, 199),
        ("bytes=32000-70000", 32_000, 70_000),
        ("bytes=299000-", 299_000, 299_999),
        ("bytes=-500", 299_500, 299_999),
        ("bytes=0-0", 0, 0),
    ] {
        let response = get(&url, Some(range)).await;
        assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT, "{range}");
        assert_eq!(
            header_of(&response, header::CONTENT_RANGE),
            Some(format!("bytes {start}-{end}/300000")),
            "{range}"
        );
        let body = response.bytes().await.expect("body");
        assert_eq!(body, content[start..=end], "{range}");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn torrent_route_handles_head_bad_ranges_and_other_files() {
    let (server, cache) = offline_server(0).await;
    let (hash, url) = cached_torrent(&server, cache.path(), &film(100_000)).await;

    let response = client().head(&url).send().await.expect("request");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        header_of(&response, header::CONTENT_LENGTH).as_deref(),
        Some("100000")
    );
    assert!(response.bytes().await.expect("body").is_empty());

    let response = get(&url, Some("bytes=100000-")).await;
    assert_eq!(response.status(), StatusCode::RANGE_NOT_SATISFIABLE);
    assert_eq!(
        header_of(&response, header::CONTENT_RANGE).as_deref(),
        Some("bytes */100000")
    );

    let other_file = format!("{}/torrent/{hash}/1", server.shared.base_url);
    assert_eq!(get(&other_file, None).await.status(), StatusCode::NOT_FOUND);
    let response = client().delete(&url).send().await.expect("request");
    assert_eq!(response.status(), StatusCode::METHOD_NOT_ALLOWED);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn status_reports_cached_torrents_until_stopped() {
    let (server, cache) = offline_server(0).await;
    let (hash, url) = cached_torrent(&server, cache.path(), &film(100_000)).await;

    let status = server.status(&hash).expect("status");
    assert_eq!(status.state, TorrentState::Ready);
    assert_eq!(status.downloaded_bytes, 100_000);
    assert_eq!(status.file_size, 100_000);
    assert_eq!(status.file_name.as_deref(), Some("movie.mkv"));
    assert_eq!(status.error, None);

    server.stop(&hash).await;
    assert_eq!(server.status(&hash), None);
    assert_eq!(get(&url, None).await.status(), StatusCode::NOT_FOUND);
    // The data stays for a later session.
    assert!(
        cache
            .path()
            .join(DATA_DIR)
            .join(&hash)
            .join("movie.mkv")
            .exists()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn torrent_url_rejects_malformed_hashes() {
    let (server, _cache) = offline_server(0).await;
    let result = server.torrent_url("not-a-hash", None, None, &[]).await;
    assert!(matches!(result, Err(TorrentError::InvalidInfoHash(_))));
    assert_eq!(server.status("not-a-hash"), None);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn prune_keeps_torrents_in_use() {
    let (server, cache) = offline_server(1_000).await;
    let data = cache.path().join(DATA_DIR);
    let old = "1111111111111111111111111111111111111111";
    for name in [old, HASH] {
        fs::create_dir_all(data.join(name)).expect("create folder");
        fs::write(data.join(name).join("movie.mkv"), film(5_000)).expect("write");
    }
    // Offline, the metadata cannot be fetched, but the torrent counts as in use until stopped.
    let added = tokio::time::timeout(
        Duration::from_secs(5),
        server.torrent_url(HASH, None, None, &[]),
    )
    .await
    .expect("fails fast offline");
    assert!(added.is_err());
    let status = server.status(HASH).expect("status");
    assert_eq!(status.state, TorrentState::Error);
    assert!(status.error.is_some());

    assert!(server.prune_cache().await.expect("prune") >= 5_000);
    assert!(!data.join(old).exists());
    assert!(data.join(HASH).exists());

    server.stop(HASH).await;
    assert!(server.prune_cache().await.expect("prune") >= 5_000);
    assert!(!data.join(HASH).exists());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn remote_sources_play_when_the_torrent_engine_cannot_start() {
    let upstream = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/movie.mkv"))
        .respond_with(ResponseTemplate::new(200).set_body_raw(film(100), "video/x-matroska"))
        .mount(&upstream)
        .await;
    // A cache "folder" that is a file: the engine cannot create its data folder in it.
    let parent = tempfile::tempdir().expect("temp dir");
    let cache_dir = parent.path().join("cache");
    fs::write(&cache_dir, b"not a folder").expect("write file");
    let config = ServerConfig {
        cache_dir,
        max_cache_bytes: 0,
    };
    let server = StreamServer::start_with(config, Network::Offline)
        .await
        .expect("server starts without the engine");

    let url = server
        .proxy_url(&format!("{}/movie.mkv", upstream.uri()), true)
        .expect("proxy url");
    let response = get(&url, None).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.bytes().await.expect("body"), film(100));

    // Torrents report the engine's error, and nothing claims to be running.
    assert!(server.torrent_url(HASH, None, None, &[]).await.is_err());
    assert!(server.status(HASH).is_none());
    assert!(server.torrent_route(HASH).is_none());
}

/// Adds a season pack whose two episodes are already in the cache, for this device's player,
/// which gets the larger one. Returns the info hash and the files.
async fn cached_pack(
    server: &StreamServer,
    cache: &Path,
    episodes: &[(&str, &[u8])],
) -> (InfoHash, Vec<TorrentFile>) {
    let source = tempfile::tempdir().expect("temp dir");
    let pack = source.path().join("Show.S01");
    fs::create_dir_all(&pack).expect("create pack");
    for (name, content) in episodes {
        fs::write(pack.join(name), content).expect("write episode");
    }
    let options = CreateTorrentOptions {
        name: None,
        trackers: Vec::new(),
        piece_length: Some(32 * 1024),
    };
    let created = create_torrent(&pack, options, &BlockingSpawner::new(1))
        .await
        .expect("create torrent");
    let hash = InfoHash::parse(&created.info_hash().as_string()).expect("hash");
    let info = created
        .as_info()
        .info
        .data
        .clone()
        .validate()
        .expect("valid");
    let files = crate::torrent::torrent_files(&info);
    let folder = cache.join(DATA_DIR).join(hash.to_string());
    fs::create_dir_all(&folder).expect("create folder");
    for (name, content) in episodes {
        fs::write(folder.join(name), content).expect("copy episode");
    }
    let torrent = created.as_bytes().expect("torrent bytes");
    server
        .shared
        .engine
        .add_torrent_file(hash, torrent, files.clone())
        .await
        .expect("torrent added");
    server
        .shared
        .holders()
        .entry(hash)
        .or_default()
        .insert(Holder::Local);
    (hash, files)
}

fn index_of(files: &[TorrentFile], name: &str) -> usize {
    files
        .iter()
        .position(|file| file.path == name)
        .expect("file in the torrent")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn two_episodes_of_one_pack_play_side_by_side() {
    let (server, cache) = offline_server(0).await;
    let first = film(100_000);
    let second: Vec<u8> = film(150_000).into_iter().rev().collect();
    let (hash, files) = cached_pack(
        &server,
        cache.path(),
        &[("Show.S01E01.mkv", &first), ("Show.S01E02.mkv", &second)],
    )
    .await;
    let e01 = index_of(&files, "Show.S01E01.mkv");
    let e02 = index_of(&files, "Show.S01E02.mkv");
    let url = |index: usize| format!("{}/torrent/{hash}/{index}", server.shared.base_url);
    // This device plays the larger episode; a paired device asks for the other one.
    assert_eq!(get(&url(e02), None).await.status(), StatusCode::OK);
    let device = Holder::Device("tv".to_owned());
    let request = FileRequest {
        index: Some(e01),
        hint: None,
    };
    let added = server
        .shared
        .add_for(hash, device.clone(), request, &[])
        .await;
    assert_eq!(added.expect("added"), e01);

    // Both stay served, each with its own progress.
    let response = get(&url(e02), Some("bytes=0-99")).await;
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(response.bytes().await.expect("body"), second[..100]);
    assert_eq!(
        get(&url(e01), None).await.bytes().await.expect("body"),
        first
    );
    assert_eq!(server.shared.engine.serves(hash, e01), Some(true));
    let local = server.status(&hash.to_string()).expect("status");
    assert_eq!(local.file_name.as_deref(), Some("Show.S01E02.mkv"));
    let remote = server.shared.engine.status(hash, &device).expect("status");
    assert_eq!(remote.file_name.as_deref(), Some("Show.S01E01.mkv"));

    // An index the torrent does not have fails, and changes nothing.
    let missing = FileRequest {
        index: Some(7),
        hint: None,
    };
    let added = server
        .shared
        .add_for(hash, device.clone(), missing, &[])
        .await;
    assert!(
        matches!(added, Err(TorrentError::NoSuchFile(7))),
        "{added:?}"
    );
    assert_eq!(server.shared.engine.serves(hash, e01), Some(true));

    // When the paired device lets go, only its episode stops.
    server.shared.release(hash, &device).await;
    assert_eq!(get(&url(e01), None).await.status(), StatusCode::NOT_FOUND);
    assert_eq!(get(&url(e02), None).await.status(), StatusCode::OK);
    assert_eq!(server.shared.engine.serves(hash, e01), Some(false));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn without_an_index_the_hint_chooses_the_episode() {
    let (server, cache) = offline_server(0).await;
    let (hash, files) = cached_pack(
        &server,
        cache.path(),
        &[
            ("Show.S01E01.mkv", &film(100_000)),
            ("Show.S01E02.mkv", &film(150_000)),
        ],
    )
    .await;
    let url = server
        .torrent_url(&hash.to_string(), None, Some("Show S01E01 1080p WEB"), &[])
        .await
        .expect("url");
    let e01 = index_of(&files, "Show.S01E01.mkv");
    assert!(url.ends_with(&format!("/{e01}")), "{url}");
    let missing = server
        .torrent_url(&hash.to_string(), Some(9), None, &[])
        .await;
    assert!(matches!(missing, Err(TorrentError::NoSuchFile(9))));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_new_cache_size_applies_to_the_next_prune() {
    let (server, cache) = offline_server(u64::MAX).await;
    let folder = cache.path().join(DATA_DIR).join(HASH);
    fs::create_dir_all(&folder).expect("create folder");
    fs::write(folder.join("movie.mkv"), film(5_000)).expect("write");
    assert_eq!(server.prune_cache().await.expect("prune"), 0);
    assert!(folder.exists());

    server.set_max_cache_bytes(0);
    assert!(server.prune_cache().await.expect("prune") >= 5_000);
    assert!(!folder.exists());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_passes_on_how_the_body_is_encoded() {
    let upstream = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/subtitles.srt"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-encoding", "gzip")
                .set_body_raw(film(64), "application/x-subrip"),
        )
        .mount(&upstream)
        .await;
    let (server, _cache) = offline_server(0).await;
    let url = server
        .proxy_url(&format!("{}/subtitles.srt", upstream.uri()), true)
        .expect("proxy url");
    let response = get(&url, None).await;
    assert_eq!(
        header_of(&response, header::CONTENT_ENCODING).as_deref(),
        Some("gzip")
    );
    // The body arrives as the source sent it, for the player to decode.
    assert_eq!(response.bytes().await.expect("body"), film(64));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxy_refuses_private_sources_from_the_internet() {
    let (server, _cache) = offline_server(0).await;
    for url in ["http://127.0.0.1:9/movie.mkv", "http://192.168.1.1/admin"] {
        assert!(
            matches!(
                server.proxy_url(url, false),
                Err(TorrentError::PrivateAddress)
            ),
            "{url}"
        );
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn folders_claimed_for_removal_are_left_alone_until_released() {
    let (server, _cache) = offline_server(0).await;
    let engine = &server.shared.engine;
    let claim = engine.claim_removal(HASH).expect("claimed");
    assert!(engine.claim_removal(HASH).is_none(), "one claim at a time");
    // Adding the torrent waits for the removal to finish.
    let adding = {
        let server = server.clone();
        tokio::spawn(async move { server.torrent_url(HASH, None, None, &[]).await })
    };
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(!adding.is_finished(), "the add waited for the removal");
    // Meanwhile the torrent counts as in use, so no other prune takes its folder.
    assert!(engine.in_use(HASH));
    drop(claim);
    let added = tokio::time::timeout(Duration::from_secs(5), adding)
        .await
        .expect("the add went on")
        .expect("task");
    // Offline, the metadata never arrives; what matters is that the add went on.
    assert!(added.is_err());
    assert!(engine.claim_removal(HASH).is_none(), "in use until stopped");
    server.stop(HASH).await;
    assert!(engine.claim_removal(HASH).is_some());
}
