// SPDX-License-Identifier: GPL-3.0-or-later
//! The server side: streaming torrents to paired devices over the home network.
//!
//! The API is deliberately small. Paired devices can add a torrent (info hash, file index,
//! trackers), read its progress, stream its file and stop it. There is no route that fetches
//! a URL, so the server cannot be turned into a proxy into its own network; trackers sent by
//! devices must resolve to public addresses (see [`crate::magnet::public_trackers`]).

use std::collections::{BTreeMap, HashMap};
use std::convert::Infallible;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use bytes::Bytes;
use http_body_util::{BodyExt, Full, Limited};
use hyper::body::Incoming;
use hyper::header::{self, HeaderValue};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioIo, TokioTimer};
use serde::Serialize;
use serde::de::DeserializeOwned;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;

use super::clients::ServerState;
use super::discovery::Announcement;
use super::net::{self, BindPolicy};
use super::pairing::{self, Pairing, Refusal};
use super::tls::Fingerprint;
use super::wire;
use crate::http::Body;
use crate::magnet::{self, InfoHash};
use crate::server::Holder;
use crate::{StreamServer, TorrentError, secret};

/// Port tried first, so addresses typed by hand stay the same; any free port otherwise.
pub(crate) const DEFAULT_PORT: u16 = 47630;

/// Connections served at once, from all devices.
const MAX_CONNECTIONS: usize = 48;

/// Connections one address may hold. A player opens one or two, plus status requests.
const MAX_CONNECTIONS_PER_PEER: usize = 8;

/// Time a device gets to finish the TLS handshake.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

/// Time a device gets to send request headers, as on the loopback server.
const HEADER_TIMEOUT: Duration = Duration::from_secs(5);

/// Time a device gets to send a request body.
const BODY_TIMEOUT: Duration = Duration::from_secs(5);

/// Largest request body: a torrent with 64 trackers fits easily.
const MAX_BODY_BYTES: usize = 32 * 1024;

/// Torrents one device may keep running. Playing a new one beyond that stops its oldest.
const MAX_TORRENTS_PER_DEVICE: usize = 3;

/// Files one device may stream at once. A player opens one, two while seeking; the limit
/// keeps one device from taking every stream slot of the engine, which this device's own
/// player shares.
const MAX_STREAMS_PER_DEVICE: usize = 3;

/// A torrent nobody streamed or asked about for this long is stopped: the device that
/// added it went to sleep, lost its network or crashed before stopping it.
const LEASE: Duration = Duration::from_secs(5 * 60);

/// How often leases are checked.
const LEASE_CHECK_INTERVAL: Duration = Duration::from_secs(30);

/// How often the interfaces are listed again, to follow address changes.
const INTERFACE_CHECK_INTERVAL: Duration = Duration::from_secs(15);

/// Settings for [`StreamServer::serve_lan`].
pub struct LanConfig {
    /// The name other devices see, such as "Living room PC".
    pub device_name: String,
    /// What the last run passed to `on_state_change`; `None` the first time, which creates a
    /// new identity.
    pub saved_state: Option<String>,
    /// Called with the new state whenever it changes (a device paired or was removed, or a
    /// new identity was created). The app keeps it in its secret store: it holds the TLS
    /// private key.
    pub on_state_change: Box<dyn Fn(String) + Send + Sync>,
}

/// A pairing code being shown.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingCode {
    pub code: String,
    pub expires_in: Duration,
}

/// A device paired with this server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    /// Seconds since the Unix epoch.
    pub paired_at: u64,
    /// Whether the device is streaming from this server right now.
    pub streaming: bool,
}

/// What Settings shows about the running server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanStatus {
    pub device_name: String,
    pub server_id: String,
    pub port: u16,
    /// Addresses other devices can reach it at; empty while the device is off the network.
    pub addresses: Vec<Ipv4Addr>,
    pub pairing: Option<PairingCode>,
    pub devices: Vec<PairedDevice>,
}

/// Serves torrents to paired devices on the home network until stopped or dropped.
pub struct LanServer {
    inner: Arc<Inner>,
}

struct Inner {
    stream: StreamServer,
    device_name: String,
    server_id: String,
    fingerprint: Fingerprint,
    acceptor: TlsAcceptor,
    port: u16,
    policy: BindPolicy,
    locked: Mutex<Locked>,
    on_state_change: Box<dyn Fn(String) + Send + Sync>,
    torrents_allowed: AtomicBool,
    listeners: Mutex<BTreeMap<Ipv4Addr, CancellationToken>>,
    connections: Arc<Semaphore>,
    per_peer: Arc<Mutex<HashMap<IpAddr, usize>>>,
    announcement: Mutex<Option<Announcement>>,
    shutdown: CancellationToken,
}

struct Locked {
    state: ServerState,
    pairing: Pairing,
    /// Torrents each device is using, by device id and info hash.
    holdings: HashMap<(String, InfoHash), Holding>,
}

struct Holding {
    last_used: Instant,
    /// One clone per open stream; more than one reference means it is streaming.
    streams: Arc<()>,
}

impl StreamServer {
    /// Starts serving torrents to other devices on every home-network address of this
    /// device (see [`net::is_lan_interface`]): never on a public, mobile or VPN interface,
    /// never on `0.0.0.0`. Fails with [`TorrentError::NoLocalNetwork`] when there is no such
    /// address. The server follows address changes by itself and stops when dropped.
    pub async fn serve_lan(&self, config: LanConfig) -> Result<LanServer, TorrentError> {
        LanServer::start(self.clone(), config, BindPolicy::Lan).await
    }

    /// Like [`Self::serve_lan`], but on `127.0.0.1` only and without announcing: for tests,
    /// and for the Android emulator, which reaches the host's loopback as `10.0.2.2`.
    /// Only built with the `dev-loopback` feature, which the apps never enable.
    #[cfg(feature = "dev-loopback")]
    pub async fn serve_loopback(&self, config: LanConfig) -> Result<LanServer, TorrentError> {
        LanServer::start(self.clone(), config, BindPolicy::Loopback).await
    }
}

impl LanServer {
    pub(crate) async fn start(
        stream: StreamServer,
        config: LanConfig,
        policy: BindPolicy,
    ) -> Result<Self, TorrentError> {
        let loaded = config.saved_state.as_deref().and_then(ServerState::load);
        let fresh = loaded.is_none();
        if config.saved_state.is_some() && fresh {
            log::warn!("lan: saved state unreadable; starting with a new identity");
        }
        let state = match loaded {
            Some(state) => state,
            None => ServerState::generate()?,
        };
        let addresses = policy.addresses();
        let Some(&first) = addresses.first() else {
            return Err(TorrentError::NoLocalNetwork);
        };
        policy.check(IpAddr::V4(first))?;
        let first_listener = match TcpListener::bind((first, DEFAULT_PORT)).await {
            Ok(listener) => listener,
            Err(error) => {
                log::info!("lan: port {DEFAULT_PORT} unavailable ({error}); using another");
                TcpListener::bind((first, 0)).await?
            }
        };
        let port = first_listener.local_addr()?.port();
        let inner = Arc::new(Inner {
            stream,
            device_name: super::clients::clean_name(&config.device_name),
            server_id: state.server_id.clone(),
            fingerprint: state.identity.fingerprint(),
            acceptor: TlsAcceptor::from(state.identity.server_config()?),
            port,
            policy,
            locked: Mutex::new(Locked {
                state,
                pairing: Pairing::default(),
                holdings: HashMap::new(),
            }),
            on_state_change: config.on_state_change,
            torrents_allowed: AtomicBool::new(true),
            listeners: Mutex::default(),
            connections: Arc::new(Semaphore::new(MAX_CONNECTIONS)),
            per_peer: Arc::default(),
            announcement: Mutex::new(None),
            shutdown: CancellationToken::new(),
        });
        if fresh {
            inner.save();
        }
        inner.listen(first, first_listener);
        for &address in &addresses[1..] {
            inner.bind(address).await;
        }
        if policy.announces() {
            *lock(&inner.announcement) =
                Announcement::start(&inner.device_name, &inner.server_id, port);
        }
        tokio::spawn(Arc::clone(&inner).watch_interfaces());
        tokio::spawn(Arc::clone(&inner).expire_leases());
        log::info!("lan: serving on port {port}");
        Ok(Self { inner })
    }

    /// What Settings shows: addresses, the pairing code and the paired devices.
    pub fn status(&self) -> LanStatus {
        let addresses = lock(&self.inner.listeners).keys().copied().collect();
        let now = Instant::now();
        let locked = self.inner.locked();
        let devices = locked
            .state
            .clients()
            .iter()
            .map(|client| PairedDevice {
                id: client.id.clone(),
                name: client.name.clone(),
                paired_at: client.paired_at,
                streaming: locked.holdings.iter().any(|((id, _), holding)| {
                    *id == client.id && Arc::strong_count(&holding.streams) > 1
                }),
            })
            .collect();
        LanStatus {
            device_name: self.inner.device_name.clone(),
            server_id: self.inner.server_id.clone(),
            port: self.inner.port,
            addresses,
            pairing: locked
                .pairing
                .current(now)
                .map(|(code, expires_in)| PairingCode { code, expires_in }),
            devices,
        }
    }

    /// Shows a new pairing code, replacing the previous one.
    pub fn new_pairing_code(&self) -> Result<PairingCode, TorrentError> {
        let now = Instant::now();
        let (code, expires) = self.inner.locked().pairing.new_code(now)?;
        Ok(PairingCode {
            code,
            expires_in: expires.duration_since(now),
        })
    }

    /// Withdraws the pairing code.
    pub fn cancel_pairing(&self) {
        self.inner.locked().pairing.cancel();
    }

    /// Unpairs a device and stops what it was playing. Returns whether it was paired.
    pub async fn remove_device(&self, id: &str) -> bool {
        let removed = self.inner.locked().state.remove(id);
        if removed {
            self.inner.save();
            self.inner.release_device(id).await;
        }
        removed
    }

    /// Whether devices may start torrents. The app turns it off while this device is on
    /// mobile data and the user allows torrents on Wi-Fi only; running torrents continue.
    pub fn set_torrents_allowed(&self, allowed: bool) {
        self.inner
            .torrents_allowed
            .store(allowed, Ordering::Relaxed);
    }

    /// The certificate fingerprint, for tests.
    #[cfg(test)]
    pub(crate) fn fingerprint(&self) -> Fingerprint {
        self.inner.fingerprint
    }

    /// Stops listening, withdraws the announcement and stops the torrents that only other
    /// devices were using.
    pub async fn stop(&self) {
        self.inner.shutdown.cancel();
        lock(&self.inner.announcement).take();
        let held: Vec<(String, InfoHash)> = self
            .inner
            .locked()
            .holdings
            .drain()
            .map(|(key, _)| key)
            .collect();
        for (id, hash) in held {
            self.inner
                .stream
                .shared
                .release(hash, &Holder::Device(id))
                .await;
        }
    }
}

impl Drop for LanServer {
    fn drop(&mut self) {
        self.inner.shutdown.cancel();
        lock(&self.inner.announcement).take();
        // Torrents still held by devices are stopped, if a runtime is around to do it.
        let held: Vec<(String, InfoHash)> = self
            .inner
            .locked()
            .holdings
            .drain()
            .map(|(key, _)| key)
            .collect();
        if let (false, Ok(runtime)) = (held.is_empty(), tokio::runtime::Handle::try_current()) {
            let shared = Arc::clone(&self.inner.stream.shared);
            runtime.spawn(async move {
                for (id, hash) in held {
                    shared.release(hash, &Holder::Device(id)).await;
                }
            });
        }
    }
}

impl std::fmt::Debug for LanServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LanServer")
            .field("port", &self.inner.port)
            .finish_non_exhaustive()
    }
}

/// A connection slot: one of [`MAX_CONNECTIONS`], counted against its peer too.
struct Slot {
    peer: IpAddr,
    per_peer: Arc<Mutex<HashMap<IpAddr, usize>>>,
    _permit: OwnedSemaphorePermit,
}

impl Drop for Slot {
    fn drop(&mut self) {
        let mut per_peer = lock(&self.per_peer);
        if let Some(count) = per_peer.get_mut(&self.peer) {
            *count -= 1;
            if *count == 0 {
                per_peer.remove(&self.peer);
            }
        }
    }
}

impl Inner {
    fn locked(&self) -> MutexGuard<'_, Locked> {
        lock(&self.locked)
    }

    /// Hands the current state to the app.
    fn save(&self) {
        let saved = self.locked().state.save();
        (self.on_state_change)(saved);
    }

    /// Binds `address` on the server's port and starts accepting there.
    async fn bind(self: &Arc<Self>, address: Ipv4Addr) {
        if self.policy.check(IpAddr::V4(address)).is_err() {
            return;
        }
        match TcpListener::bind((address, self.port)).await {
            Ok(listener) => self.listen(address, listener),
            Err(error) => log::warn!("lan: cannot listen on {address}:{}: {error}", self.port),
        }
    }

    fn listen(self: &Arc<Self>, address: Ipv4Addr, listener: TcpListener) {
        let stop = self.shutdown.child_token();
        if let Some(previous) = lock(&self.listeners).insert(address, stop.clone()) {
            previous.cancel();
        }
        tokio::spawn(Arc::clone(self).accept(listener, stop));
    }

    async fn accept(self: Arc<Self>, listener: TcpListener, stop: CancellationToken) {
        loop {
            let accepted = tokio::select! {
                () = stop.cancelled() => return,
                accepted = listener.accept() => accepted,
            };
            let (stream, peer) = match accepted {
                Ok(accepted) => accepted,
                Err(error) => {
                    log::warn!("lan: accept failed: {error}");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            };
            if !net::is_allowed_peer(peer.ip()) {
                continue;
            }
            let Some(slot) = self.admit(peer.ip()) else {
                continue;
            };
            tokio::spawn(Arc::clone(&self).serve_connection(stream, peer, slot, stop.clone()));
        }
    }

    /// A slot for a connection from `peer`, unless the server or the peer is at its limit.
    fn admit(&self, peer: IpAddr) -> Option<Slot> {
        let mut per_peer = lock(&self.per_peer);
        let count = per_peer.entry(peer).or_default();
        if *count >= MAX_CONNECTIONS_PER_PEER {
            return None;
        }
        let permit = Arc::clone(&self.connections).try_acquire_owned().ok()?;
        *count += 1;
        Some(Slot {
            peer,
            per_peer: Arc::clone(&self.per_peer),
            _permit: permit,
        })
    }

    async fn serve_connection(
        self: Arc<Self>,
        stream: TcpStream,
        peer: SocketAddr,
        slot: Slot,
        stop: CancellationToken,
    ) {
        let _slot = slot;
        let Ok(Ok(tls)) =
            tokio::time::timeout(HANDSHAKE_TIMEOUT, self.acceptor.accept(stream)).await
        else {
            return;
        };
        let inner = Arc::clone(&self);
        let service = service_fn(move |request| {
            let inner = Arc::clone(&inner);
            async move { Ok::<_, Infallible>(inner.handle(request).await) }
        });
        let connection = http1::Builder::new()
            .timer(TokioTimer::new())
            .header_read_timeout(HEADER_TIMEOUT)
            .serve_connection(TokioIo::new(tls), service);
        tokio::select! {
            () = stop.cancelled() => {}
            result = connection => {
                if let Err(error) = result {
                    log::debug!("lan: connection from {} ended: {error}", peer.ip());
                }
            }
        }
    }

    /// Routes a request. Only pairing works without a token.
    async fn handle(&self, request: Request<Incoming>) -> Response<Body> {
        let path = request.uri().path().to_owned();
        let segments: Vec<&str> = path.split('/').skip(1).collect();
        let method = request.method().clone();
        match (&method, segments.as_slice()) {
            (&Method::POST, ["v1", "pair", "start"]) => return self.pair_start(request).await,
            (&Method::POST, ["v1", "pair", "finish"]) => return self.pair_finish(request).await,
            _ => {}
        }
        let Some(device) = self.authenticate(&request) else {
            let mut response = failure(StatusCode::UNAUTHORIZED, "unpaired", None);
            response
                .headers_mut()
                .insert(header::CONNECTION, HeaderValue::from_static("close"));
            return response;
        };
        match (&method, segments.as_slice()) {
            (&Method::GET, ["v1", "hello"]) => json(
                StatusCode::OK,
                &wire::Hello {
                    server_id: self.server_id.clone(),
                    server_name: self.device_name.clone(),
                },
            ),
            (&Method::DELETE, ["v1", "me"]) => {
                if self.locked().state.remove(&device) {
                    self.save();
                }
                self.release_device(&device).await;
                crate::http::empty(StatusCode::NO_CONTENT)
            }
            (&Method::POST, ["v1", "torrents"]) => self.add_torrent(&device, request).await,
            (&Method::GET, ["v1", "torrents", hash]) => self.torrent_status(&device, hash),
            (&Method::DELETE, ["v1", "torrents", hash]) => {
                if let Some(hash) = InfoHash::parse(hash) {
                    self.release(&device, hash).await;
                }
                crate::http::empty(StatusCode::NO_CONTENT)
            }
            (&Method::GET | &Method::HEAD, ["v1", "torrents", hash, index]) => {
                self.stream_file(&device, hash, index, &request).await
            }
            (_, ["v1", "torrents", ..]) => crate::http::method_not_allowed(),
            _ => failure(StatusCode::NOT_FOUND, "not_found", None),
        }
    }

    /// The id of the device whose token the request carries.
    fn authenticate(&self, request: &Request<Incoming>) -> Option<String> {
        let value = request
            .headers()
            .get(header::AUTHORIZATION)?
            .to_str()
            .ok()?;
        let token = value.strip_prefix("Bearer ")?;
        if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            return None;
        }
        self.locked()
            .state
            .authenticate(token)
            .map(|client| client.id.clone())
    }

    async fn pair_start(&self, request: Request<Incoming>) -> Response<Body> {
        let start: wire::PairStart = match read_json(request).await {
            Ok(start) => start,
            Err(response) => return *response,
        };
        let Some(message) = secret::from_hex(&start.message) else {
            return refusal(Refusal::Malformed);
        };
        let started = self.locked().pairing.start(Instant::now(), &message);
        match started {
            Ok((exchange, message)) => json(
                StatusCode::OK,
                &wire::PairStarted {
                    exchange,
                    message: secret::to_hex(&message),
                },
            ),
            Err(refused) => refusal(refused),
        }
    }

    async fn pair_finish(&self, request: Request<Incoming>) -> Response<Body> {
        let finish: wire::PairFinish = match read_json(request).await {
            Ok(finish) => finish,
            Err(response) => return *response,
        };
        let Some(proof) = secret::from_hex(&finish.proof) else {
            return refusal(Refusal::Malformed);
        };
        let paired = {
            let mut locked = self.locked();
            let key =
                locked
                    .pairing
                    .finish(Instant::now(), &finish.exchange, &proof, &self.fingerprint);
            match key {
                Ok(key) => locked.state.add(&finish.name).map(|added| (key, added)),
                Err(refused) => return refusal(refused),
            }
        };
        match paired {
            Ok((key, Some((_, token)))) => {
                self.save();
                log::info!("lan: paired a new device");
                let proof = pairing::server_proof(&key, &self.fingerprint, &self.server_id, &token);
                json(
                    StatusCode::OK,
                    &wire::Paired {
                        token,
                        server_id: self.server_id.clone(),
                        server_name: self.device_name.clone(),
                        proof: secret::to_hex(&proof),
                    },
                )
            }
            Ok((_, None)) => failure(StatusCode::CONFLICT, "too_many_devices", None),
            Err(error) => failure(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal",
                Some(error.to_string()),
            ),
        }
    }

    async fn add_torrent(&self, device: &str, request: Request<Incoming>) -> Response<Body> {
        let add: wire::AddTorrent = match read_json(request).await {
            Ok(add) => add,
            Err(response) => return *response,
        };
        if !self.torrents_allowed.load(Ordering::Relaxed) {
            return failure(StatusCode::SERVICE_UNAVAILABLE, "torrents_paused", None);
        }
        let Some(hash) = InfoHash::parse(&add.info_hash) else {
            return failure(StatusCode::BAD_REQUEST, "invalid_info_hash", None);
        };
        let trackers = magnet::public_trackers(magnet::tracker_urls(&add.trackers)).await;
        let evicted = {
            let mut locked = self.locked();
            let evicted = oldest_beyond_limit(&locked.holdings, device, hash);
            if let Some(evicted) = evicted {
                locked.holdings.remove(&(device.to_owned(), evicted));
            }
            locked
                .holdings
                .entry((device.to_owned(), hash))
                .or_insert_with(|| Holding {
                    last_used: Instant::now(),
                    streams: Arc::new(()),
                })
                .last_used = Instant::now();
            evicted
        };
        if let Some(evicted) = evicted {
            self.stream
                .shared
                .release(evicted, &Holder::Device(device.to_owned()))
                .await;
        }
        let requested = add.file_idx.and_then(|index| usize::try_from(index).ok());
        let added = self
            .stream
            .shared
            .add_for(
                hash,
                Holder::Device(device.to_owned()),
                requested,
                &trackers,
            )
            .await;
        match added {
            Ok(index) => json(StatusCode::OK, &wire::Added { index }),
            Err(error) => {
                self.release(device, hash).await;
                let code = match error {
                    TorrentError::MetadataTimeout(_) => "metadata_timeout",
                    TorrentError::NoVideoFile => "no_video_file",
                    _ => "torrent_failed",
                };
                failure(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    code,
                    Some(error.to_string()),
                )
            }
        }
    }

    fn torrent_status(&self, device: &str, hash: &str) -> Response<Body> {
        let Some(hash) = self.touch(device, hash) else {
            return failure(StatusCode::NOT_FOUND, "not_found", None);
        };
        match self.stream.shared.engine.status(hash) {
            Some(status) => json(StatusCode::OK, &wire::Status::from(status)),
            None => failure(StatusCode::NOT_FOUND, "not_found", None),
        }
    }

    async fn stream_file(
        &self,
        device: &str,
        hash: &str,
        index: &str,
        request: &Request<Incoming>,
    ) -> Response<Body> {
        let Some(parsed) = self.touch(device, hash) else {
            return failure(StatusCode::NOT_FOUND, "not_found", None);
        };
        let (streams, open) = {
            let locked = self.locked();
            let open: usize = locked
                .holdings
                .iter()
                .filter(|((id, _), _)| id == device)
                .map(|(_, holding)| Arc::strong_count(&holding.streams) - 1)
                .sum();
            let streams = locked
                .holdings
                .get(&(device.to_owned(), parsed))
                .map(|holding| Arc::clone(&holding.streams));
            (streams, open)
        };
        let Some(streams) = streams else {
            return failure(StatusCode::NOT_FOUND, "not_found", None);
        };
        if open >= MAX_STREAMS_PER_DEVICE {
            return failure(StatusCode::SERVICE_UNAVAILABLE, "too_many_streams", None);
        }
        let range = request
            .headers()
            .get(header::RANGE)
            .and_then(|value| value.to_str().ok());
        let response = self
            .stream
            .shared
            .engine
            .respond(hash, index, request.method(), range)
            .await;
        if response.status() == StatusCode::NOT_FOUND {
            // The device holds the torrent, but another one switched it to a different file
            // (two episodes of one season pack at once). Distinct from "not found" so the
            // device's relay does not add it back and switch it again.
            return failure(StatusCode::CONFLICT, "file_switched", None);
        }
        // The holding counts as streaming, and so keeps its lease, while the body is open.
        response.map(|body| {
            body.map_frame(move |frame| {
                let _streaming = &streams;
                frame
            })
            .boxed_unsync()
        })
    }

    /// Marks a device's torrent as just used and returns its hash, if the device holds it.
    fn touch(&self, device: &str, hash: &str) -> Option<InfoHash> {
        let hash = InfoHash::parse(hash)?;
        let mut locked = self.locked();
        let holding = locked.holdings.get_mut(&(device.to_owned(), hash))?;
        holding.last_used = Instant::now();
        Some(hash)
    }

    async fn release(&self, device: &str, hash: InfoHash) {
        let held = self
            .locked()
            .holdings
            .remove(&(device.to_owned(), hash))
            .is_some();
        if held {
            self.stream
                .shared
                .release(hash, &Holder::Device(device.to_owned()))
                .await;
            self.prune();
        }
    }

    /// Stops everything a device was using, after it was removed.
    async fn release_device(&self, device: &str) {
        let held: Vec<InfoHash> = {
            let mut locked = self.locked();
            let held: Vec<InfoHash> = locked
                .holdings
                .keys()
                .filter(|(id, _)| id == device)
                .map(|(_, hash)| *hash)
                .collect();
            for hash in &held {
                locked.holdings.remove(&(device.to_owned(), *hash));
            }
            held
        };
        for hash in held {
            self.stream
                .shared
                .release(hash, &Holder::Device(device.to_owned()))
                .await;
        }
        self.prune();
    }

    fn prune(&self) {
        let stream = self.stream.clone();
        tokio::spawn(async move {
            if let Err(error) = stream.prune_cache().await {
                log::warn!("lan: cannot trim the cache: {error}");
            }
        });
    }

    /// Stops torrents whose device stopped using them without saying so.
    async fn expire_leases(self: Arc<Self>) {
        let mut ticks = tokio::time::interval(LEASE_CHECK_INTERVAL);
        loop {
            tokio::select! {
                () = self.shutdown.cancelled() => return,
                _ = ticks.tick() => {}
            }
            let expired: Vec<(String, InfoHash)> = {
                let now = Instant::now();
                let locked = self.locked();
                locked
                    .holdings
                    .iter()
                    .filter(|(_, holding)| {
                        Arc::strong_count(&holding.streams) == 1
                            && now.duration_since(holding.last_used) >= LEASE
                    })
                    .map(|(key, _)| key.clone())
                    .collect()
            };
            for (device, hash) in expired {
                log::info!("lan: lease of {hash} expired");
                self.release(&device, hash).await;
            }
        }
    }

    /// Follows address changes: listens on new home-network addresses, drops gone ones.
    async fn watch_interfaces(self: Arc<Self>) {
        let mut ticks = tokio::time::interval(INTERFACE_CHECK_INTERVAL);
        ticks.tick().await;
        loop {
            tokio::select! {
                () = self.shutdown.cancelled() => return,
                _ = ticks.tick() => {}
            }
            let current = self.policy.addresses();
            let gone: Vec<Ipv4Addr> = lock(&self.listeners)
                .keys()
                .filter(|address| !current.contains(address))
                .copied()
                .collect();
            for address in gone {
                if let Some(stop) = lock(&self.listeners).remove(&address) {
                    log::info!("lan: {address} went away");
                    stop.cancel();
                }
            }
            for address in current {
                if !lock(&self.listeners).contains_key(&address) {
                    log::info!("lan: listening on new address {address}");
                    self.bind(address).await;
                }
            }
        }
    }
}

/// The device's least recently used torrent, when adding `adding` would take it past
/// [`MAX_TORRENTS_PER_DEVICE`].
fn oldest_beyond_limit(
    holdings: &HashMap<(String, InfoHash), Holding>,
    device: &str,
    adding: InfoHash,
) -> Option<InfoHash> {
    let mine: Vec<(&InfoHash, &Holding)> = holdings
        .iter()
        .filter(|((id, _), _)| id == device)
        .map(|((_, hash), holding)| (hash, holding))
        .collect();
    if mine.len() < MAX_TORRENTS_PER_DEVICE || mine.iter().any(|(hash, _)| **hash == adding) {
        return None;
    }
    mine.into_iter()
        .min_by_key(|(_, holding)| holding.last_used)
        .map(|(hash, _)| *hash)
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    // Every update leaves the data consistent, so a panic elsewhere cannot corrupt it.
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Reads a small JSON body, or answers with the error to send.
async fn read_json<T: DeserializeOwned>(
    request: Request<Incoming>,
) -> Result<T, Box<Response<Body>>> {
    let body = Limited::new(request.into_body(), MAX_BODY_BYTES);
    let collected = tokio::time::timeout(BODY_TIMEOUT, body.collect())
        .await
        .map_err(|_| Box::new(failure(StatusCode::REQUEST_TIMEOUT, "timeout", None)))?
        .map_err(|_| Box::new(failure(StatusCode::PAYLOAD_TOO_LARGE, "too_large", None)))?;
    serde_json::from_slice(&collected.to_bytes())
        .map_err(|_| Box::new(failure(StatusCode::BAD_REQUEST, "malformed", None)))
}

fn json<T: Serialize>(status: StatusCode, value: &T) -> Response<Body> {
    let bytes = serde_json::to_vec(value).unwrap_or_default();
    let mut response = Response::new(
        Full::new(Bytes::from(bytes))
            .map_err(|never| match never {})
            .boxed_unsync(),
    );
    *response.status_mut() = status;
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

fn failure(status: StatusCode, error: &str, message: Option<String>) -> Response<Body> {
    json(
        status,
        &wire::Failure {
            error: error.to_owned(),
            message,
        },
    )
}

fn refusal(refused: Refusal) -> Response<Body> {
    let status = match refused {
        Refusal::NoCode | Refusal::UnknownExchange => StatusCode::GONE,
        Refusal::TooSoon(_) | Refusal::TooManyAttempts => StatusCode::TOO_MANY_REQUESTS,
        Refusal::WrongCode => StatusCode::FORBIDDEN,
        Refusal::Malformed => StatusCode::BAD_REQUEST,
    };
    let mut response = failure(status, refused.code(), None);
    if let Refusal::TooSoon(wait) = refused {
        let seconds = wait.as_secs().max(1);
        if let Ok(value) = HeaderValue::from_str(&seconds.to_string()) {
            response.headers_mut().insert(header::RETRY_AFTER, value);
        }
    }
    response
}

#[cfg(test)]
mod tests;
