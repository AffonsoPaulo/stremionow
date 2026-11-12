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
        .proxy_url("http://127.0.0.1:9/movie.mkv")
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
            matches!(server.proxy_url(url), Err(TorrentError::UnsupportedUrl)),
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
        .proxy_url(&format!("{}/movie.mkv", upstream.uri()))
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
        .proxy_url(&format!("{}/old", upstream.uri()))
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
        .proxy_url(&format!("http://127.0.0.1:{port}/movie.mkv"))
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
    let result = server.torrent_url("not-a-hash", None, &[]).await;
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
    let added = tokio::time::timeout(Duration::from_secs(5), server.torrent_url(HASH, None, &[]))
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
