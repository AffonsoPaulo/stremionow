// SPDX-License-Identifier: GPL-3.0-or-later
//! Paired devices and the state the server keeps between runs.
//!
//! The server never stores tokens, only their SHA-256: tokens are 256 random bits, so the
//! hash cannot be reversed, and a copy of the saved state does not let anyone in. The saved
//! state does hold the TLS private key, which is why the app keeps it in its encrypted
//! secret store rather than in the database.

use std::time::{SystemTime, UNIX_EPOCH};

use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};

use super::tls::Identity;
use crate::{TorrentError, secret};

/// Devices paired at once. A home has a handful; the limit bounds what the state can grow to.
pub(crate) const MAX_CLIENTS: usize = 16;

/// Longest device name kept, in characters.
const MAX_NAME_CHARS: usize = 40;

/// Random bytes per token: 256 bits, sent as 64 hex digits.
const TOKEN_BYTES: usize = 32;

/// Version of the saved state's format.
const STATE_VERSION: u32 = 1;

/// A paired device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Client {
    /// Random id, used to remove the device.
    pub(crate) id: String,
    pub(crate) name: String,
    token_hash: [u8; 32],
    /// Seconds since the Unix epoch.
    pub(crate) paired_at: u64,
}

/// The server's lasting state: its id, certificate, paired devices and home network.
pub(crate) struct ServerState {
    pub(crate) server_id: String,
    pub(crate) identity: Identity,
    clients: Vec<Client>,
    /// Identities of the network where the user turned serving on (see `network`).
    home: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct Saved {
    version: u32,
    server_id: String,
    cert: String,
    key: String,
    clients: Vec<SavedClient>,
    #[serde(default)]
    home: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct SavedClient {
    id: String,
    name: String,
    token_sha256: String,
    paired_at: u64,
}

impl ServerState {
    /// A new server identity with no paired devices.
    pub(crate) fn generate() -> Result<Self, TorrentError> {
        Ok(Self {
            server_id: secret::random_hex()?,
            identity: Identity::generate()?,
            clients: Vec::new(),
            home: Vec::new(),
        })
    }

    /// The state saved by [`Self::save`], or `None` if it cannot be read, in which case the
    /// server starts over with a new identity and its devices must pair again.
    pub(crate) fn load(saved: &str) -> Option<Self> {
        let saved: Saved = serde_json::from_str(saved).ok()?;
        if saved.version != STATE_VERSION || secret::from_hex(&saved.server_id)?.len() != 16 {
            return None;
        }
        let cert = CertificateDer::from(secret::from_hex(&saved.cert)?);
        let key = PrivatePkcs8KeyDer::from(secret::from_hex(&saved.key)?);
        let identity = Identity { cert, key };
        // A key that does not match its certificate would only fail at the first handshake.
        identity.server_config().ok()?;
        let clients = saved
            .clients
            .into_iter()
            .take(MAX_CLIENTS)
            .filter_map(|client| {
                let hash: [u8; 32] = secret::from_hex(&client.token_sha256)?.try_into().ok()?;
                Some(Client {
                    id: client.id,
                    name: clean_name(&client.name),
                    token_hash: hash,
                    paired_at: client.paired_at,
                })
            })
            .collect();
        let home = saved
            .home
            .into_iter()
            .filter(|network| secret::from_hex(network).is_some_and(|bytes| bytes.len() == 32))
            .take(8)
            .collect();
        Some(Self {
            server_id: saved.server_id,
            identity,
            clients,
            home,
        })
    }

    /// The state as text, for the app's secret store.
    pub(crate) fn save(&self) -> String {
        let saved = Saved {
            version: STATE_VERSION,
            server_id: self.server_id.clone(),
            cert: secret::to_hex(&self.identity.cert),
            key: secret::to_hex(self.identity.key.secret_pkcs8_der()),
            clients: self
                .clients
                .iter()
                .map(|client| SavedClient {
                    id: client.id.clone(),
                    name: client.name.clone(),
                    token_sha256: secret::to_hex(&client.token_hash),
                    paired_at: client.paired_at,
                })
                .collect(),
            home: self.home.clone(),
        };
        // Plain strings and numbers always serialize.
        serde_json::to_string(&saved).unwrap_or_default()
    }

    pub(crate) fn clients(&self) -> &[Client] {
        &self.clients
    }

    /// Whether the device with this id is still paired.
    pub(crate) fn is_paired(&self, id: &str) -> bool {
        self.clients.iter().any(|client| client.id == id)
    }

    /// The home network's identities; empty until the user chooses one.
    pub(crate) fn home(&self) -> &[String] {
        &self.home
    }

    /// Makes `networks` the home network. Returns whether that changed anything.
    pub(crate) fn set_home(&mut self, mut networks: Vec<String>) -> bool {
        networks.sort_unstable();
        networks.dedup();
        networks.truncate(8);
        let changed = networks != self.home;
        self.home = networks;
        changed
    }

    /// The device `token` belongs to. Every stored hash is compared in full, so timing does
    /// not reveal which device, if any, came close.
    pub(crate) fn authenticate(&self, token: &str) -> Option<&Client> {
        let hash = token_hash(token);
        let mut found = None;
        for client in &self.clients {
            if secret::constant_time_eq_bytes(&hash, &client.token_hash) {
                found = Some(client);
            }
        }
        found
    }

    /// Pairs a new device and returns it with its token, or `None` when [`MAX_CLIENTS`] are
    /// paired already.
    pub(crate) fn add(&mut self, name: &str) -> Result<Option<(Client, String)>, TorrentError> {
        if self.clients.len() >= MAX_CLIENTS {
            return Ok(None);
        }
        let token = secret::to_hex(&secret::random_bytes::<TOKEN_BYTES>()?);
        let client = Client {
            id: secret::random_hex()?,
            name: clean_name(name),
            token_hash: token_hash(&token),
            paired_at: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |since| since.as_secs()),
        };
        self.clients.push(client.clone());
        Ok(Some((client, token)))
    }

    /// Unpairs a device. Returns whether it was paired.
    pub(crate) fn remove(&mut self, id: &str) -> bool {
        let before = self.clients.len();
        self.clients.retain(|client| client.id != id);
        self.clients.len() != before
    }
}

fn token_hash(token: &str) -> [u8; 32] {
    super::tls::fingerprint(token.as_bytes())
}

/// A device name fit to show: control characters dropped, whitespace collapsed, bounded.
pub(crate) fn clean_name(name: &str) -> String {
    let cleaned: String = name
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_NAME_CHARS)
        .collect();
    if cleaned.is_empty() {
        "StremioNow".to_owned()
    } else {
        cleaned
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_authenticate_their_device_only() {
        let mut state = ServerState::generate().expect("state");
        let (tv, tv_token) = state.add("Living room TV").expect("add").expect("room");
        let (phone, phone_token) = state.add("Phone").expect("add").expect("room");
        assert_eq!(tv_token.len(), 64, "256-bit tokens");
        assert_eq!(state.authenticate(&tv_token).map(|c| &c.id), Some(&tv.id));
        assert_eq!(
            state.authenticate(&phone_token).map(|c| &c.id),
            Some(&phone.id)
        );
        assert!(state.authenticate("").is_none());
        assert!(state.authenticate(&tv_token[..63]).is_none());
        assert!(state.authenticate(&tv_token.to_uppercase()).is_none());

        assert!(state.remove(&tv.id));
        assert!(!state.remove(&tv.id));
        assert!(state.authenticate(&tv_token).is_none());
        assert!(state.authenticate(&phone_token).is_some());
    }

    #[test]
    fn state_round_trips_without_tokens() {
        let mut state = ServerState::generate().expect("state");
        assert!(state.set_home(vec!["ab".repeat(32)]));
        assert!(!state.set_home(vec!["ab".repeat(32)]), "unchanged");
        let (_, token) = state.add("TV").expect("add").expect("room");
        let saved = state.save();
        assert!(!saved.contains(&token), "only the token's hash is saved");

        let loaded = ServerState::load(&saved).expect("loads");
        assert_eq!(loaded.home(), state.home());
        assert_eq!(loaded.server_id, state.server_id);
        assert_eq!(loaded.identity.fingerprint(), state.identity.fingerprint());
        assert_eq!(loaded.clients(), state.clients());
        assert!(loaded.authenticate(&token).is_some());
    }

    #[test]
    fn unreadable_state_is_dropped() {
        for saved in [
            "",
            "{}",
            "not json",
            r#"{"version":2,"server_id":"00","cert":"","key":"","clients":[]}"#,
        ] {
            assert!(ServerState::load(saved).is_none(), "{saved}");
        }
    }

    #[test]
    fn the_number_of_devices_is_bounded() {
        let mut state = ServerState::generate().expect("state");
        for i in 0..MAX_CLIENTS {
            assert!(state.add(&format!("Device {i}")).expect("add").is_some());
        }
        assert!(state.add("One too many").expect("add").is_none());
    }

    #[test]
    fn names_are_cleaned() {
        assert_eq!(clean_name("  Fire\tTV \n Stick "), "Fire TV Stick");
        assert_eq!(clean_name("TV\u{0007}\u{200b}"), "TV\u{200b}");
        assert_eq!(clean_name(""), "StremioNow");
        assert_eq!(clean_name(&"x".repeat(100)).chars().count(), MAX_NAME_CHARS);
    }
}
