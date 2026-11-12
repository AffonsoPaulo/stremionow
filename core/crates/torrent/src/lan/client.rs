// SPDX-License-Identifier: GPL-3.0-or-later
//! The client side: pairing with another device and asking it for torrents.
//!
//! The token travels only in the `Authorization` header of requests to the paired server,
//! over TLS pinned to its certificate. The player never sees it: it reads from this device's
//! loopback server, which forwards to the paired one (see `relay`).

use std::fmt;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use hyper::header::{self, HeaderValue};
use reqwest::{Method, StatusCode};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use super::pairing::{self, ClientExchange};
use super::tls::{self, Fingerprint, PinnedVerifier};
use super::{clients, net, wire};
use crate::magnet::InfoHash;
use crate::{TorrentError, TorrentStatus, secret};

/// Time allowed to connect. Devices on the same network answer in milliseconds; a server
/// that is off or asleep should be noticed quickly, so playback can fall back.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);

/// Longest wait for the next chunk of an answer.
const READ_TIMEOUT: Duration = Duration::from_secs(30);

/// Longest wait for a quick request (status, stop, pairing).
const SHORT_TIMEOUT: Duration = Duration::from_secs(8);

/// Longest wait for a torrent to be added: the server waits up to a minute for metadata.
const ADD_TIMEOUT: Duration = Duration::from_secs(90);

/// Version of the saved pairing's format.
const SAVED_VERSION: u32 = 1;

/// Why pairing failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum PairError {
    #[error("the address is not on the local network")]
    InvalidAddress,
    #[error("the code must have 6 digits")]
    InvalidCode,
    #[error("the other device cannot be reached")]
    Unreachable,
    #[error("the other device is not showing a pairing code, or it expired")]
    NoCode,
    #[error("the code is wrong")]
    WrongCode,
    #[error("too many attempts; show a new code and try again")]
    TooManyAttempts,
    #[error("the other device has too many paired devices")]
    TooManyDevices,
    /// The answer did not prove knowledge of the code: not the device the user looked at.
    #[error("the other device could not prove it is the one showing the code")]
    NotGenuine,
    #[error("unexpected answer from the other device")]
    Protocol,
}

/// Why the paired server could not do what was asked.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum RemoteError {
    #[error("the other device cannot be reached")]
    Unreachable,
    /// The server no longer knows this device's token: it was removed there, or the server
    /// was reset.
    #[error("the other device no longer recognizes this one")]
    Unpaired,
    /// The server is not starting torrents right now (it is on mobile data).
    #[error("the other device is not starting torrents right now")]
    Paused,
    /// The torrent itself failed on the server: no peers, no video file.
    #[error("{0}")]
    Torrent(String),
    #[error("unexpected answer from the other device")]
    Protocol,
}

/// Whether the paired server can be used.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteHealth {
    Ready,
    Unreachable,
    Unpaired,
}

/// A paired server. Cheap to clone.
#[derive(Clone)]
pub struct RemoteServer {
    inner: Arc<Inner>,
}

struct Inner {
    verifier: Arc<PinnedVerifier>,
    address: SocketAddr,
    server_id: String,
    name: String,
    token: String,
    fingerprint: Fingerprint,
    http: reqwest::Client,
    base: String,
}

#[derive(Serialize, Deserialize)]
struct Saved {
    version: u32,
    address: String,
    server_id: String,
    name: String,
    token: String,
    fingerprint: String,
}

impl RemoteServer {
    fn new(
        address: SocketAddr,
        server_id: String,
        name: String,
        token: String,
        fingerprint: Fingerprint,
    ) -> Result<Self, TorrentError> {
        let verifier = PinnedVerifier::pinned(fingerprint);
        let http = http_client(Arc::clone(&verifier))?;
        Ok(Self {
            inner: Arc::new(Inner {
                verifier,
                base: format!("https://{address}"),
                address,
                server_id,
                name,
                token,
                fingerprint,
                http,
            }),
        })
    }

    /// The pairing saved by [`Self::save`].
    pub fn load(saved: &str) -> Result<Self, TorrentError> {
        let invalid = || TorrentError::Io(std::io::Error::other("unreadable pairing"));
        let saved: Saved = serde_json::from_str(saved).map_err(|_| invalid())?;
        if saved.version != SAVED_VERSION
            || !is_hex(&saved.token, 64)
            || !is_hex(&saved.server_id, 32)
        {
            return Err(invalid());
        }
        let address = net::parse_server_address(&saved.address)?;
        let fingerprint: Fingerprint = secret::from_hex(&saved.fingerprint)
            .and_then(|bytes| bytes.try_into().ok())
            .ok_or_else(invalid)?;
        Self::new(
            address,
            saved.server_id,
            clients::clean_name(&saved.name),
            saved.token,
            fingerprint,
        )
    }

    /// The pairing as text, for the app's secret store: it holds the token.
    pub fn save(&self) -> String {
        let saved = Saved {
            version: SAVED_VERSION,
            address: self.inner.address.to_string(),
            server_id: self.inner.server_id.clone(),
            name: self.inner.name.clone(),
            token: self.inner.token.clone(),
            fingerprint: secret::to_hex(&self.inner.fingerprint),
        };
        serde_json::to_string(&saved).unwrap_or_default()
    }

    /// The same pairing at another address, after the server moved (DHCP).
    pub fn at(&self, address: SocketAddr) -> Result<Self, TorrentError> {
        if !net::is_lan_ip(address.ip()) {
            return Err(TorrentError::NotLocalNetwork);
        }
        Self::new(
            address,
            self.inner.server_id.clone(),
            self.inner.name.clone(),
            self.inner.token.clone(),
            self.inner.fingerprint,
        )
    }

    pub fn name(&self) -> &str {
        &self.inner.name
    }

    pub fn address(&self) -> SocketAddr {
        self.inner.address
    }

    pub fn server_id(&self) -> &str {
        &self.inner.server_id
    }

    /// Asks the server whether this device is still paired.
    pub async fn check(&self) -> RemoteHealth {
        let answer = self
            .request(Method::GET, "/v1/hello")
            .timeout(SHORT_TIMEOUT)
            .send()
            .await;
        match answer {
            Ok(response) if response.status() == StatusCode::OK => {
                match response.json::<wire::Hello>().await {
                    Ok(hello) if hello.server_id == self.inner.server_id => RemoteHealth::Ready,
                    // Another server took the address; this one is elsewhere or gone.
                    _ => RemoteHealth::Unreachable,
                }
            }
            Ok(response) if response.status() == StatusCode::UNAUTHORIZED => RemoteHealth::Unpaired,
            Ok(_) => RemoteHealth::Unreachable,
            Err(_) => match self.connection_error() {
                RemoteError::Unpaired => RemoteHealth::Unpaired,
                _ => RemoteHealth::Unreachable,
            },
        }
    }

    /// Asks the server to forget this device. Best effort: unpairing here works regardless.
    pub async fn forget(&self) {
        let _ = self
            .request(Method::DELETE, "/v1/me")
            .timeout(SHORT_TIMEOUT)
            .send()
            .await;
    }

    /// Starts a torrent on the server and returns the index of the file it will stream.
    pub(crate) async fn add(
        &self,
        hash: InfoHash,
        file_idx: Option<u32>,
        trackers: &[String],
    ) -> Result<usize, RemoteError> {
        let body = wire::AddTorrent {
            info_hash: hash.to_string(),
            file_idx,
            trackers: trackers.to_vec(),
        };
        let response = self
            .request(Method::POST, "/v1/torrents")
            .timeout(ADD_TIMEOUT)
            .json(&body)
            .send()
            .await
            .map_err(|_| self.connection_error())?;
        let added: wire::Added = answer(response).await?;
        Ok(added.index)
    }

    /// The torrent's progress on the server, or `None` if the server does not have it.
    pub(crate) async fn status(
        &self,
        hash: InfoHash,
    ) -> Result<Option<TorrentStatus>, RemoteError> {
        let response = self
            .request(Method::GET, &format!("/v1/torrents/{hash}"))
            .timeout(SHORT_TIMEOUT)
            .send()
            .await
            .map_err(|_| self.connection_error())?;
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(None);
        }
        let status: wire::Status = answer(response).await?;
        Ok(Some(status.into()))
    }

    /// Tells the server this device is done with the torrent.
    pub(crate) async fn stop(&self, hash: InfoHash) {
        let _ = self
            .request(Method::DELETE, &format!("/v1/torrents/{hash}"))
            .timeout(SHORT_TIMEOUT)
            .send()
            .await;
    }

    /// A request for the torrent's file, with the player's `Range`.
    pub(crate) fn stream(
        &self,
        method: &hyper::Method,
        hash: InfoHash,
        index: usize,
        range: Option<&HeaderValue>,
    ) -> reqwest::RequestBuilder {
        let method = if method == hyper::Method::HEAD {
            Method::HEAD
        } else {
            Method::GET
        };
        let mut request = self
            .request(method, &format!("/v1/torrents/{hash}/{index}"))
            .header(header::ACCEPT_ENCODING, "identity");
        if let Some(range) = range {
            request = request.header(header::RANGE, range.clone());
        }
        request
    }

    /// Why a request could not be sent: a server with another certificate was reset (or is
    /// an impostor), so this device must pair again; anything else is a network problem.
    pub(crate) fn connection_error(&self) -> RemoteError {
        if self.inner.verifier.take_mismatch() {
            RemoteError::Unpaired
        } else {
            RemoteError::Unreachable
        }
    }

    fn request(&self, method: Method, path: &str) -> reqwest::RequestBuilder {
        self.inner
            .http
            .request(method, format!("{}{path}", self.inner.base))
            .bearer_auth(&self.inner.token)
    }
}

impl fmt::Debug for RemoteServer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // The token stays out: debug output ends up in logs.
        f.debug_struct("RemoteServer")
            .field("name", &self.inner.name)
            .field("address", &self.inner.address)
            .finish_non_exhaustive()
    }
}

/// Pairs with the server at `address` (`192.168.1.20` or `192.168.1.20:47630`) using the
/// code it shows, and returns the pairing for [`RemoteServer::save`]. `device_name` is how
/// this device appears in the server's list.
pub async fn pair(address: &str, code: &str, device_name: &str) -> Result<RemoteServer, PairError> {
    let address = net::parse_server_address(address).map_err(|_| PairError::InvalidAddress)?;
    let code = pairing::normalize_code(code).ok_or(PairError::InvalidCode)?;
    let verifier = PinnedVerifier::first_seen();
    let http = http_client(Arc::clone(&verifier)).map_err(|_| PairError::Protocol)?;
    let base = format!("https://{address}");

    let (exchange, message) = ClientExchange::start(&code);
    let start = wire::PairStart {
        message: secret::to_hex(&message),
    };
    let mut started =
        post::<_, wire::PairStarted>(&http, &format!("{base}/v1/pair/start"), &start).await;
    if let Err(PairFailure::TooSoon(wait)) = started {
        // Someone else tried a moment ago; one retry after the wait the server asked for.
        tokio::time::sleep(wait).await;
        started = post(&http, &format!("{base}/v1/pair/start"), &start).await;
    }
    let started = started.map_err(PairFailure::into_error)?;
    let fingerprint = verifier.fingerprint().ok_or(PairError::Protocol)?;
    let server_message = secret::from_hex(&started.message).ok_or(PairError::Protocol)?;
    let key = exchange
        .finish(&server_message)
        .ok_or(PairError::Protocol)?;

    let finish = wire::PairFinish {
        exchange: started.exchange,
        proof: secret::to_hex(&key.proof(&fingerprint)),
        name: clients::clean_name(device_name),
    };
    let paired: wire::Paired = post(&http, &format!("{base}/v1/pair/finish"), &finish)
        .await
        .map_err(PairFailure::into_error)?;
    // The verifier refuses any certificate but the first, so both requests reached the
    // server whose fingerprint was proven.
    let proof = secret::from_hex(&paired.proof).ok_or(PairError::Protocol)?;
    if !key.verify_server(&fingerprint, &paired.server_id, &paired.token, &proof) {
        return Err(PairError::NotGenuine);
    }
    if !is_hex(&paired.token, 64) || !is_hex(&paired.server_id, 32) {
        return Err(PairError::Protocol);
    }
    log::info!("lan: paired with another device");
    RemoteServer::new(
        address,
        paired.server_id,
        clients::clean_name(&paired.server_name),
        paired.token,
        fingerprint,
    )
    .map_err(|_| PairError::Protocol)
}

/// How a pairing request failed, before it is mapped to a [`PairError`].
enum PairFailure {
    TooSoon(Duration),
    Error(PairError),
}

impl PairFailure {
    fn into_error(self) -> PairError {
        match self {
            Self::TooSoon(_) => PairError::TooManyAttempts,
            Self::Error(error) => error,
        }
    }
}

async fn post<B: Serialize, T: DeserializeOwned>(
    http: &reqwest::Client,
    url: &str,
    body: &B,
) -> Result<T, PairFailure> {
    let response = http
        .post(url)
        .timeout(SHORT_TIMEOUT)
        .json(body)
        .send()
        .await
        .map_err(|_| PairFailure::Error(PairError::Unreachable))?;
    let status = response.status();
    if status.is_success() {
        return response
            .json()
            .await
            .map_err(|_| PairFailure::Error(PairError::Protocol));
    }
    let wait = response
        .headers()
        .get(header::RETRY_AFTER)
        .and_then(|value| value.to_str().ok()?.parse::<u64>().ok())
        .map_or(Duration::from_secs(1), |seconds| {
            Duration::from_secs(seconds.min(5))
        });
    let failure: Option<wire::Failure> = response.json().await.ok();
    let code = failure
        .as_ref()
        .map_or("", |failure| failure.error.as_str());
    Err(match code {
        "too_soon" => PairFailure::TooSoon(wait),
        "no_code" | "unknown_exchange" => PairFailure::Error(PairError::NoCode),
        "wrong_code" => PairFailure::Error(PairError::WrongCode),
        "too_many_attempts" => PairFailure::Error(PairError::TooManyAttempts),
        "too_many_devices" => PairFailure::Error(PairError::TooManyDevices),
        _ => PairFailure::Error(PairError::Protocol),
    })
}

/// The body of a successful answer, or the error it reports.
async fn answer<T: DeserializeOwned>(response: reqwest::Response) -> Result<T, RemoteError> {
    let status = response.status();
    if status.is_success() {
        return response.json().await.map_err(|_| RemoteError::Protocol);
    }
    if status == StatusCode::UNAUTHORIZED {
        return Err(RemoteError::Unpaired);
    }
    let failure: Option<wire::Failure> = response.json().await.ok();
    Err(match failure {
        Some(failure) if failure.error == "torrents_paused" => RemoteError::Paused,
        Some(failure) if status == StatusCode::UNPROCESSABLE_ENTITY => {
            RemoteError::Torrent(failure.message.unwrap_or(failure.error))
        }
        _ if status.is_server_error() => RemoteError::Unreachable,
        _ => RemoteError::Protocol,
    })
}

/// The HTTP client for one server: TLS pinned by `verifier`, no system proxy (it could see
/// the token), no redirects (the server never sends any), no Referer.
fn http_client(verifier: Arc<PinnedVerifier>) -> Result<reqwest::Client, TorrentError> {
    reqwest::Client::builder()
        .tls_backend_preconfigured(tls::client_config(verifier)?)
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .referer(false)
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(READ_TIMEOUT)
        .pool_idle_timeout(Duration::from_secs(30))
        .user_agent(concat!("StremioNow/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|error| TorrentError::Io(std::io::Error::other(error)))
}

fn is_hex(text: &str, len: usize) -> bool {
    text.len() == len && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> RemoteServer {
        RemoteServer::new(
            "192.168.1.20:47630".parse().expect("address"),
            "0".repeat(32),
            "PC".to_owned(),
            "a".repeat(64),
            [3; 32],
        )
        .expect("server")
    }

    #[test]
    fn pairings_round_trip_and_keep_the_token_out_of_debug() {
        let server = sample();
        let saved = server.save();
        let loaded = RemoteServer::load(&saved).expect("loads");
        assert_eq!(loaded.address(), server.address());
        assert_eq!(loaded.server_id(), server.server_id());
        assert_eq!(loaded.name(), "PC");
        assert_eq!(loaded.save(), saved);
        assert!(!format!("{server:?}").contains(&"a".repeat(64)));
    }

    #[test]
    fn saved_pairings_must_point_at_the_local_network() {
        let saved = sample().save().replace("192.168.1.20", "8.8.8.8");
        assert!(RemoteServer::load(&saved).is_err());
        assert!(
            sample()
                .at("8.8.8.8:47630".parse().expect("address"))
                .is_err()
        );
        assert!(
            sample()
                .at("10.0.0.9:47630".parse().expect("address"))
                .is_ok()
        );
        assert!(RemoteServer::load("{}").is_err());
    }

    #[tokio::test]
    async fn pairing_checks_its_input_first() {
        assert_eq!(
            pair("8.8.8.8", "123456", "TV").await.err(),
            Some(PairError::InvalidAddress)
        );
        assert_eq!(
            pair("192.168.1.20", "12345", "TV").await.err(),
            Some(PairError::InvalidCode)
        );
    }
}
