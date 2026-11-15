// SPDX-License-Identifier: GPL-3.0-or-later
//! The server side: streaming torrents to paired devices over the home network.
//!
//! The API is deliberately small. Paired devices can add a torrent (info hash, file index,
//! trackers), read its progress, stream its file and stop it. There is no route that fetches
//! a URL, so the server cannot be turned into a proxy into its own network; trackers sent by
//! devices must be UDP trackers resolving to public addresses (see
//! [`crate::magnet::public_trackers`]).
//!
//! The server only listens on the network where the user turned it on (see `network`), and
//! pauses elsewhere: on another Wi-Fi, a VPN, or a network it cannot identify.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::convert::Infallible;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use bytes::Bytes;
use futures::StreamExt;
use http_body_util::{BodyExt, BodyStream, Full, Limited, StreamBody};
use hyper::body::Incoming;
use hyper::header::{self, HeaderValue};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioIo, TokioTimer};
use serde::Serialize;
use serde::de::DeserializeOwned;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Notify;
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;

use super::clients::ServerState;
use super::discovery::Announcement;
use super::limits::{Limits, Slot};
use super::net::{self, BindPolicy};
use super::pairing::{self, Pairing, Refusal};
use super::tls::Fingerprint;
use super::wire;
use crate::files::{self, FileRequest};
use crate::http::Body;
use crate::magnet::{self, InfoHash};
use crate::server::Holder;
use crate::{StreamServer, TorrentError, secret};

/// Port tried first, so addresses typed by hand stay the same; any free port otherwise.
pub(crate) const DEFAULT_PORT: u16 = 47630;

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

/// A torrent nobody streamed for this long is stopped: the device that added it went to
/// sleep, lost its network or crashed before stopping it. Progress requests do not count,
/// so a client that forgot a torrent but keeps polling does not hold it forever.
const LEASE: Duration = Duration::from_secs(5 * 60);

/// How often leases are checked.
const LEASE_CHECK_INTERVAL: Duration = Duration::from_secs(30);

/// How often the interfaces are listed again, to follow address and network changes.
const INTERFACE_CHECK_INTERVAL: Duration = Duration::from_secs(15);

/// Settings for [`StreamServer::serve_lan`].
pub struct LanConfig {
    /// The name other devices see, chosen by the user; announced on the network, so the
    /// app's default should not be the owner's name.
    pub device_name: String,
    /// What the last run passed to `on_state_change`; `None` the first time, which creates a
    /// new identity.
    pub saved_state: Option<String>,
    /// Called with the new state whenever it changes (a device paired or was removed, the
    /// home network changed, or a new identity was created). The app keeps it in its secret
    /// store: it holds the TLS private key.
    pub on_state_change: Box<dyn Fn(String) + Send + Sync>,
    /// What the app knows about the current network, for systems that do not let the core
    /// see the gateway (Android): see [`LanServer::set_network_hint`].
    pub network_hint: Option<String>,
    /// Make the current network the home network. True when the user turns serving on;
    /// false when the app restores it at launch, so it never adopts whatever network the
    /// device happens to be on then.
    pub serve_on_current_network: bool,
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

/// Why the server is not listening.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PauseReason {
    /// The device is not on any home network.
    NoNetwork,
    /// The device is on a network other than the one where serving was turned on, or on one
    /// it cannot identify.
    OtherNetwork,
}

/// What Settings shows about the running server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanStatus {
    pub device_name: String,
    pub server_id: String,
    pub port: u16,
    /// Addresses other devices can reach it at; empty while paused.
    pub addresses: Vec<Ipv4Addr>,
    pub paused: Option<PauseReason>,
    pub pairing: Option<PairingCode>,
    /// Another device tried the code on screen, or spent the last one.
    pub pairing_contested: bool,
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
    policy: BindPolicy,
    locked: Mutex<Locked>,
    on_state_change: Box<dyn Fn(String) + Send + Sync>,
    /// Held while the state is serialized and handed to the app, so saves arrive in order.
    saving: Mutex<()>,
    torrents_allowed: AtomicBool,
    /// The port, once a first listener chose it.
    port: Mutex<u16>,
    listeners: Mutex<BTreeMap<Ipv4Addr, Listener>>,
    /// Addresses being served, which the announcement follows.
    served: Arc<Mutex<HashSet<IpAddr>>>,
    network: Mutex<NetworkState>,
    /// Serializes reconciling listeners with the network.
    reconciling: tokio::sync::Mutex<()>,
    /// Wakes the network watcher early.
    network_changed: Notify,
    limits: Arc<Limits>,
    announcement: Mutex<Option<Announcement>>,
    shutdown: CancellationToken,
}

#[derive(Default)]
struct NetworkState {
    hint: Option<String>,
    paused: Option<PauseReason>,
}

struct Locked {
    state: ServerState,
    pairing: Pairing,
    /// Torrents each device is using, by device id and info hash.
    holdings: HashMap<(String, InfoHash), Holding>,
    /// Cancelled when a device is removed, which ends its open streams.
    revoked: HashMap<String, CancellationToken>,
}

struct Holding {
    last_used: Instant,
    /// One clone per open stream; more than one reference means it is streaming.
    streams: Arc<()>,
}

impl StreamServer {
    /// Starts serving torrents to other devices on the home network: only on interfaces on
    /// the network where the user turned serving on (see `network`), never on a public,
    /// mobile, VPN or virtual interface, never on `0.0.0.0`. Elsewhere it waits, paused,
    /// and resumes when the device is back. Stops when dropped.
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
        let mut changed = loaded.is_none();
        if config.saved_state.is_some() && changed {
            log::warn!("lan: saved state unreadable; starting with a new identity");
        }
        let mut state = match loaded {
            Some(state) => state,
            None => ServerState::generate()?,
        };
        if config.serve_on_current_network {
            let current = current_networks(policy, config.network_hint.as_deref());
            if !current.is_empty() {
                changed |= state.set_home(current);
            }
        }
        let inner = Arc::new(Inner {
            stream,
            device_name: super::clients::clean_name(&config.device_name),
            server_id: state.server_id.clone(),
            fingerprint: state.identity.fingerprint(),
            acceptor: TlsAcceptor::from(state.identity.server_config()?),
            policy,
            locked: Mutex::new(Locked {
                state,
                pairing: Pairing::default(),
                holdings: HashMap::new(),
                revoked: HashMap::new(),
            }),
            on_state_change: config.on_state_change,
            saving: Mutex::new(()),
            torrents_allowed: AtomicBool::new(true),
            port: Mutex::new(0),
            listeners: Mutex::default(),
            served: Arc::default(),
            network: Mutex::new(NetworkState {
                hint: config.network_hint,
                paused: None,
            }),
            reconciling: tokio::sync::Mutex::new(()),
            network_changed: Notify::new(),
            limits: Arc::default(),
            announcement: Mutex::new(None),
            shutdown: CancellationToken::new(),
        });
        if changed {
            inner.save();
        }
        inner.reconcile().await;
        tokio::spawn(Arc::clone(&inner).watch_network());
        tokio::spawn(Arc::clone(&inner).expire_leases());
        Ok(Self { inner })
    }

    /// What Settings shows: addresses or why it is paused, the pairing code and the paired
    /// devices.
    pub fn status(&self) -> LanStatus {
        let addresses = lock(&self.inner.listeners).keys().copied().collect();
        let paused = lock(&self.inner.network).paused;
        let port = *lock(&self.inner.port);
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
            port,
            addresses,
            paused,
            pairing: locked
                .pairing
                .current(now)
                .map(|(code, expires_in)| PairingCode { code, expires_in }),
            pairing_contested: locked.pairing.contested(),
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

    /// Unpairs a device and ends what it was streaming. Returns whether it was paired.
    pub async fn remove_device(&self, id: &str) -> bool {
        let removed = self.inner.revoke(id);
        if removed {
            self.inner.save();
        }
        self.inner.release_device(id).await;
        removed
    }

    /// Makes the network the device is on now the home network, after the user confirmed
    /// it, and resumes serving there. Fails when the device is on no identifiable network.
    pub async fn serve_on_this_network(&self) -> Result<(), TorrentError> {
        let hint = lock(&self.inner.network).hint.clone();
        let current = current_networks(self.inner.policy, hint.as_deref());
        if current.is_empty() {
            return Err(TorrentError::NoLocalNetwork);
        }
        let changed = self.inner.locked().state.set_home(current);
        if changed {
            self.inner.save();
        }
        self.inner.reconcile().await;
        Ok(())
    }

    /// Tells the server what the app knows about the network it is on now (Android: from
    /// the system's link properties), and checks the network again at once.
    pub fn set_network_hint(&self, hint: Option<String>) {
        let mut network = lock(&self.inner.network);
        if network.hint != hint {
            network.hint = hint;
            self.inner.network_changed.notify_one();
        }
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
            .field("port", &*lock(&self.inner.port))
            .finish_non_exhaustive()
    }
}

/// Identities of the networks the device is on now.
fn current_networks(policy: BindPolicy, hint: Option<&str>) -> Vec<String> {
    policy
        .candidates(hint)
        .into_iter()
        .filter_map(|candidate| candidate.network)
        .collect()
}

impl Inner {
    fn locked(&self) -> MutexGuard<'_, Locked> {
        lock(&self.locked)
    }

    /// Hands the current state to the app. Serializing and handing over happen under one
    /// lock, so a later state never arrives before an earlier one: a removed device's token
    /// hash cannot come back with a stale save.
    fn save(&self) {
        let _saving = lock(&self.saving);
        let saved = self.locked().state.save();
        (self.on_state_change)(saved);
    }

    /// Unpairs a device and ends its open streams. Returns whether it was paired.
    fn revoke(&self, id: &str) -> bool {
        let mut locked = self.locked();
        if let Some(revoked) = locked.revoked.remove(id) {
            revoked.cancel();
        }
        locked.state.remove(id)
    }

    /// The token that ends a device's streams when it is removed.
    fn revocation(&self, id: &str) -> CancellationToken {
        self.locked()
            .revoked
            .entry(id.to_owned())
            .or_default()
            .clone()
    }

    /// Listens on exactly the candidate interfaces that are on the home network, announces
    /// on them, and records why it is paused when there are none.
    async fn reconcile(self: &Arc<Self>) {
        let _reconciling = self.reconciling.lock().await;
        if self.shutdown.is_cancelled() {
            return;
        }
        let hint = lock(&self.network).hint.clone();
        let candidates = self.policy.candidates(hint.as_deref());
        let home = self.locked().state.home().to_vec();
        let wanted: Vec<Ipv4Addr> = candidates
            .iter()
            .filter(|candidate| {
                candidate
                    .network
                    .as_ref()
                    .is_some_and(|network| home.contains(network))
            })
            .map(|candidate| candidate.address)
            .collect();
        let gone: Vec<Ipv4Addr> = lock(&self.listeners)
            .keys()
            .filter(|address| !wanted.contains(address))
            .copied()
            .collect();
        for address in gone {
            let removed = lock(&self.listeners).remove(&address);
            if let Some(listener) = removed {
                log::info!("lan: no longer serving on {address}");
                // Wait for the socket to close, so "paused" is never reported while a
                // listener on another network still accepts connections.
                listener.close().await;
            }
        }
        for &address in &wanted {
            if !lock(&self.listeners).contains_key(&address) {
                self.bind(address).await;
            }
        }
        let listening: HashSet<IpAddr> = lock(&self.listeners)
            .keys()
            .map(|address| IpAddr::V4(*address))
            .collect();
        let serving = !listening.is_empty();
        *lock(&self.served) = listening;
        lock(&self.network).paused = match (candidates.is_empty(), serving) {
            (_, true) => None,
            (true, false) => Some(PauseReason::NoNetwork),
            (false, false) => Some(PauseReason::OtherNetwork),
        };
        let mut announcement = lock(&self.announcement);
        if !serving {
            announcement.take();
        } else if announcement.is_none() && self.policy.announces() {
            let port = *lock(&self.port);
            *announcement = Announcement::start(
                &self.device_name,
                &self.server_id,
                port,
                Arc::clone(&self.served),
            );
        }
    }

    /// Binds `address` on the server's port and accepts connections there. The port is chosen
    /// on the first bind, and again when it is taken while nothing listens (another program
    /// took it while serving was paused); the announcement then moves to the new port.
    async fn bind(self: &Arc<Self>, address: Ipv4Addr) {
        if self.policy.check(IpAddr::V4(address)).is_err() {
            return;
        }
        let port = *lock(&self.port);
        let mut bound = if port != 0 {
            TcpListener::bind((address, port)).await
        } else {
            Err(std::io::Error::other("no port chosen yet"))
        };
        // While other addresses listen on the port, all of them must keep sharing it: the
        // announcement names a single port.
        let may_move = port == 0 || lock(&self.listeners).is_empty();
        if let Err(error) = &bound
            && may_move
        {
            if port != 0 {
                log::info!("lan: port {port} unavailable ({error}); choosing again");
            }
            bound = match TcpListener::bind((address, DEFAULT_PORT)).await {
                Ok(listener) => Ok(listener),
                Err(error) => {
                    log::info!("lan: port {DEFAULT_PORT} unavailable ({error}); using another");
                    TcpListener::bind((address, 0)).await
                }
            };
        }
        let listener = match bound {
            Ok(listener) => listener,
            Err(error) => {
                log::warn!("lan: cannot listen on {address}: {error}");
                return;
            }
        };
        if let Ok(local) = listener.local_addr()
            && local.port() != port
        {
            *lock(&self.port) = local.port();
            // `reconcile` announces again, on the new port.
            lock(&self.announcement).take();
        }
        log::info!("lan: serving on {address}");
        let stop = self.shutdown.child_token();
        let task = tokio::spawn(Arc::clone(self).accept(listener, stop.clone()));
        let previous = lock(&self.listeners).insert(address, Listener { stop, task });
        if let Some(previous) = previous {
            previous.close().await;
        }
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
            if !net::is_allowed_peer(self.policy, peer.ip()) {
                continue;
            }
            let Some(slot) = self.limits.admit(peer.ip()) else {
                continue;
            };
            tokio::spawn(Arc::clone(&self).serve_connection(stream, peer, slot, stop.clone()));
        }
    }

    async fn serve_connection(
        self: Arc<Self>,
        stream: TcpStream,
        peer: SocketAddr,
        slot: Slot,
        stop: CancellationToken,
    ) {
        let slot = Arc::new(slot);
        let closed = slot.closed().clone();
        let handshake = tokio::time::timeout(HANDSHAKE_TIMEOUT, self.acceptor.accept(stream));
        let tls = tokio::select! {
            () = closed.cancelled() => return,
            tls = handshake => match tls {
                Ok(Ok(tls)) => tls,
                _ => return,
            },
        };
        let inner = Arc::clone(&self);
        let service = service_fn(move |request| {
            let inner = Arc::clone(&inner);
            let slot = Arc::clone(&slot);
            async move { Ok::<_, Infallible>(inner.handle(request, peer.ip(), &slot).await) }
        });
        let connection = http1::Builder::new()
            .timer(TokioTimer::new())
            .header_read_timeout(HEADER_TIMEOUT)
            .serve_connection(TokioIo::new(tls), service);
        tokio::select! {
            () = stop.cancelled() => {}
            () = closed.cancelled() => {}
            result = connection => {
                if let Err(error) = result {
                    log::debug!("lan: connection from {} ended: {error}", peer.ip());
                }
            }
        }
    }

    /// Routes a request. Only pairing works without a token.
    async fn handle(
        &self,
        request: Request<Incoming>,
        peer: IpAddr,
        slot: &Slot,
    ) -> Response<Body> {
        let path = request.uri().path().to_owned();
        let segments: Vec<&str> = path.split('/').skip(1).collect();
        let method = request.method().clone();
        match (&method, segments.as_slice()) {
            (&Method::POST, ["v1", "pair", "start"]) => {
                return self.pair_start(request, peer).await;
            }
            (&Method::POST, ["v1", "pair", "finish"]) => {
                return self.pair_finish(request, peer).await;
            }
            _ => {}
        }
        let Some(device) = self.authenticate(&request) else {
            let mut response = failure(StatusCode::UNAUTHORIZED, "unpaired");
            response
                .headers_mut()
                .insert(header::CONNECTION, HeaderValue::from_static("close"));
            return response;
        };
        if !slot.authenticate() {
            return failure(StatusCode::SERVICE_UNAVAILABLE, "busy");
        }
        match (&method, segments.as_slice()) {
            (&Method::GET, ["v1", "hello"]) => json(
                StatusCode::OK,
                &wire::Hello {
                    server_id: self.server_id.clone(),
                    server_name: self.device_name.clone(),
                },
            ),
            (&Method::DELETE, ["v1", "me"]) => {
                if self.revoke(&device) {
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
            _ => failure(StatusCode::NOT_FOUND, "not_found"),
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

    async fn pair_start(&self, request: Request<Incoming>, peer: IpAddr) -> Response<Body> {
        let start: wire::PairStart = match read_json(request).await {
            Ok(start) => start,
            Err(response) => return *response,
        };
        let Some(message) = secret::from_hex(&start.message) else {
            return refusal(Refusal::Malformed);
        };
        let started = self.locked().pairing.start(Instant::now(), peer, &message);
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

    async fn pair_finish(&self, request: Request<Incoming>, peer: IpAddr) -> Response<Body> {
        let finish: wire::PairFinish = match read_json(request).await {
            Ok(finish) => finish,
            Err(response) => return *response,
        };
        let Some(proof) = secret::from_hex(&finish.proof) else {
            return refusal(Refusal::Malformed);
        };
        let paired = {
            let mut locked = self.locked();
            let key = locked.pairing.finish(
                Instant::now(),
                peer,
                &finish.exchange,
                &proof,
                &self.fingerprint,
            );
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
            Ok((_, None)) => failure(StatusCode::CONFLICT, "too_many_devices"),
            Err(error) => {
                log::warn!("lan: cannot pair: {error}");
                failure(StatusCode::INTERNAL_SERVER_ERROR, "internal")
            }
        }
    }

    async fn add_torrent(&self, device: &str, request: Request<Incoming>) -> Response<Body> {
        let add: wire::AddTorrent = match read_json(request).await {
            Ok(add) => add,
            Err(response) => return *response,
        };
        if !self.torrents_allowed.load(Ordering::Relaxed) {
            return failure(StatusCode::SERVICE_UNAVAILABLE, "torrents_paused");
        }
        let Some(hash) = InfoHash::parse(&add.info_hash) else {
            return failure(StatusCode::BAD_REQUEST, "invalid_info_hash");
        };
        let trackers = magnet::public_trackers(magnet::tracker_urls(&add.trackers)).await;
        let holder = Holder::Device(device.to_owned());
        // The device may have been removed while the tracker names were resolved; its
        // earlier torrent beyond the limit is let go of before the holding is recorded, so a
        // DELETE arriving meanwhile always finds something consistent.
        let evicted = {
            let locked = self.locked();
            if !locked.state.is_paired(device) {
                return failure(StatusCode::UNAUTHORIZED, "unpaired");
            }
            oldest_beyond_limit(&locked.holdings, device, hash)
        };
        if let Some(evicted) = evicted {
            self.release(device, evicted).await;
        }
        {
            let mut locked = self.locked();
            if !locked.state.is_paired(device) {
                return failure(StatusCode::UNAUTHORIZED, "unpaired");
            }
            locked
                .holdings
                .entry((device.to_owned(), hash))
                .or_insert_with(|| Holding {
                    last_used: Instant::now(),
                    streams: Arc::new(()),
                })
                .last_used = Instant::now();
        }
        let request = FileRequest {
            // An index this system cannot even represent names no file.
            index: add
                .file_idx
                .map(|index| usize::try_from(index).unwrap_or(usize::MAX)),
            hint: add.file_hint.as_deref().map(files::bounded_hint),
        };
        let added = self
            .stream
            .shared
            .add_for(hash, holder.clone(), request, &trackers)
            .await;
        // A DELETE, a removal or the lease may have let go of the holding meanwhile; the
        // engine must not keep a torrent nobody holds.
        let still_held = {
            let locked = self.locked();
            locked.state.is_paired(device)
                && locked.holdings.contains_key(&(device.to_owned(), hash))
        };
        match added {
            Ok(index) if still_held => json(StatusCode::OK, &wire::Added { index }),
            Ok(_) => {
                self.release(device, hash).await;
                self.stream.shared.release(hash, &holder).await;
                failure(StatusCode::GONE, "stopped")
            }
            Err(error) => {
                self.release(device, hash).await;
                // Details stay in this device's log; the other device gets a code.
                log::warn!("lan: torrent {hash} failed for a paired device: {error}");
                let code = match error {
                    TorrentError::MetadataTimeout(_) => "metadata_timeout",
                    TorrentError::NoVideoFile => "no_video_file",
                    TorrentError::NoSuchFile(_) => "no_such_file",
                    TorrentError::UnsafeFileName => "unsafe_file_name",
                    TorrentError::NotEnoughSpace { .. } => "not_enough_space",
                    TorrentError::Stopped => "stopped",
                    _ => "torrent_failed",
                };
                failure(StatusCode::UNPROCESSABLE_ENTITY, code)
            }
        }
    }

    fn torrent_status(&self, device: &str, hash: &str) -> Response<Body> {
        let Some(hash) = InfoHash::parse(hash) else {
            return failure(StatusCode::NOT_FOUND, "not_found");
        };
        if !self
            .locked()
            .holdings
            .contains_key(&(device.to_owned(), hash))
        {
            return failure(StatusCode::NOT_FOUND, "not_found");
        }
        let holder = Holder::Device(device.to_owned());
        match self.stream.shared.engine.status(hash, &holder) {
            Some(status) => json(StatusCode::OK, &wire::Status::from(status)),
            None => failure(StatusCode::NOT_FOUND, "not_found"),
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
            return failure(StatusCode::NOT_FOUND, "not_found");
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
            return failure(StatusCode::NOT_FOUND, "not_found");
        };
        if open >= MAX_STREAMS_PER_DEVICE {
            return failure(StatusCode::SERVICE_UNAVAILABLE, "too_many_streams");
        }
        // Every file someone plays stays served, so two episodes of one season pack play at
        // once. A file nobody plays any more (the device moved on to another) is a conflict,
        // which the device's relay must not answer by adding the torrent again. A torrent
        // that is gone or failed is "not found", which it may.
        let requested = index.parse::<usize>().unwrap_or(usize::MAX);
        match self.stream.shared.engine.serves(parsed, requested) {
            None => return failure(StatusCode::NOT_FOUND, "not_found"),
            Some(false) => return failure(StatusCode::CONFLICT, "file_switched"),
            Some(true) => {}
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
        // The holding counts as streaming, and so keeps its lease, while the body is open;
        // removing the device ends the body.
        let revoked = self.revocation(device);
        response.map(|body| {
            let frames = BodyStream::new(body)
                .take_until(revoked.cancelled_owned())
                .map(move |frame| {
                    let _streaming = &streams;
                    frame
                });
            StreamBody::new(frames).boxed_unsync()
        })
    }

    /// Marks a device's torrent as just streamed and returns its hash, if the device holds it.
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

    /// Follows address and network changes: serves new home-network addresses, stops on
    /// gone ones, and pauses on other networks.
    async fn watch_network(self: Arc<Self>) {
        let mut ticks = tokio::time::interval(INTERFACE_CHECK_INTERVAL);
        ticks.tick().await;
        loop {
            tokio::select! {
                () = self.shutdown.cancelled() => return,
                _ = ticks.tick() => {}
                () = self.network_changed.notified() => {}
            }
            self.reconcile().await;
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
        .map_err(|_| Box::new(failure(StatusCode::REQUEST_TIMEOUT, "timeout")))?
        .map_err(|_| Box::new(failure(StatusCode::PAYLOAD_TOO_LARGE, "too_large")))?;
    serde_json::from_slice(&collected.to_bytes())
        .map_err(|_| Box::new(failure(StatusCode::BAD_REQUEST, "malformed")))
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

/// An error answer. Only a code: what went wrong in detail stays in this device's log.
fn failure(status: StatusCode, error: &str) -> Response<Body> {
    json(
        status,
        &wire::Failure {
            error: error.to_owned(),
            message: None,
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
    let mut response = failure(status, refused.code());
    if let Refusal::TooSoon(wait) = refused {
        let seconds = wait.as_secs().max(1);
        if let Ok(value) = HeaderValue::from_str(&seconds.to_string()) {
            response.headers_mut().insert(header::RETRY_AFTER, value);
        }
    }
    response
}

/// A socket the server accepts connections on, and the task doing it.
struct Listener {
    stop: CancellationToken,
    task: tokio::task::JoinHandle<()>,
}

impl Listener {
    /// Stops accepting and waits until the socket is closed.
    async fn close(self) {
        self.stop.cancel();
        let _ = self.task.await;
    }
}

#[cfg(test)]
mod tests;
