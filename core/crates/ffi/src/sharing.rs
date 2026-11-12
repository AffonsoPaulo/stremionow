// SPDX-License-Identifier: GPL-3.0-or-later
//! Serving torrents to other devices, and using another device for torrents (ADR 0008).
//!
//! Secrets cross this boundary only through [`SecretSink`]: the server's state (its TLS key)
//! and a client's pairing (its token) are handed to the app for its encrypted store, and
//! handed back at the next launch. The core keeps them in memory only.

use std::time::Duration;

use stremionow_torrent::{
    LanConfig, LanStatus, PairError, RemoteHealth, RemoteServer, TorrentError,
};

use crate::core::StremioCore;
use crate::records::CoreError;

/// How long discovery listens for answers by default; servers answer within a second.
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(3);

/// Receives a secret to keep in the app's encrypted store, replacing the previous one.
#[uniffi::export(callback_interface)]
pub trait SecretSink: Send + Sync {
    fn save(&self, value: String);
}

/// The running server, as Settings shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ServeStatus {
    pub device_name: String,
    /// Addresses with the port, such as "192.168.1.20:47630". Empty while off the network.
    pub addresses: Vec<String>,
    /// The pairing code on screen, if any.
    pub pairing_code: Option<String>,
    /// Seconds the code has left.
    pub pairing_seconds_left: u32,
    pub devices: Vec<PairedDeviceInfo>,
}

/// A device paired with this one.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PairedDeviceInfo {
    pub id: String,
    pub name: String,
    /// Milliseconds since the Unix epoch.
    pub paired_at_ms: i64,
    pub streaming: bool,
}

/// A server found on the network.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FoundDevice {
    pub name: String,
    pub server_id: String,
    pub address: String,
}

/// The device this one uses for torrents.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OtherDevice {
    pub name: String,
    pub address: String,
    pub server_id: String,
}

/// Whether the device used for torrents can be reached.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OtherDeviceHealth {
    Ready,
    Unreachable,
    /// It no longer knows this device (removed there, or reset): pair again.
    Unpaired,
}

/// Why pairing failed, for the message shown.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum PairingError {
    #[error("the address is not on the local network")]
    InvalidAddress,
    #[error("the code must have 6 digits")]
    InvalidCode,
    #[error("the other device cannot be reached")]
    Unreachable,
    #[error("no pairing code is showing on the other device")]
    NoCode,
    #[error("the code is wrong")]
    WrongCode,
    #[error("too many attempts")]
    TooManyAttempts,
    #[error("the other device has too many paired devices")]
    TooManyDevices,
    #[error("the other device could not prove it is the one showing the code")]
    NotGenuine,
    #[error("unexpected answer from the other device")]
    Failed,
}

impl From<PairError> for PairingError {
    fn from(error: PairError) -> Self {
        match error {
            PairError::InvalidAddress => Self::InvalidAddress,
            PairError::InvalidCode => Self::InvalidCode,
            PairError::Unreachable => Self::Unreachable,
            PairError::NoCode => Self::NoCode,
            PairError::WrongCode => Self::WrongCode,
            PairError::TooManyAttempts => Self::TooManyAttempts,
            PairError::TooManyDevices => Self::TooManyDevices,
            PairError::NotGenuine => Self::NotGenuine,
            PairError::Protocol => Self::Failed,
        }
    }
}

impl From<&RemoteServer> for OtherDevice {
    fn from(server: &RemoteServer) -> Self {
        Self {
            name: server.name().to_owned(),
            address: server.address().to_string(),
            server_id: server.server_id().to_owned(),
        }
    }
}

fn serve_status(status: LanStatus) -> ServeStatus {
    ServeStatus {
        device_name: status.device_name,
        addresses: status
            .addresses
            .iter()
            .map(|address| format!("{address}:{}", status.port))
            .collect(),
        pairing_seconds_left: status.pairing.as_ref().map_or(0, |pairing| {
            u32::try_from(pairing.expires_in.as_secs()).unwrap_or(0)
        }),
        pairing_code: status.pairing.map(|pairing| pairing.code),
        devices: status
            .devices
            .into_iter()
            .map(|device| PairedDeviceInfo {
                id: device.id,
                name: device.name,
                paired_at_ms: i64::try_from(device.paired_at)
                    .unwrap_or(0)
                    .saturating_mul(1000),
                streaming: device.streaming,
            })
            .collect(),
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Starts serving torrents to paired devices on the home network, named `device_name`.
    /// `saved_state` is what `sink` received last time (`None` the first time). Stops the
    /// use of another device for torrents: a device does one or the other.
    pub async fn start_serving(
        &self,
        device_name: String,
        saved_state: Option<String>,
        sink: Box<dyn SecretSink>,
    ) -> Result<ServeStatus, CoreError> {
        self.stop_serving().await;
        let server = self.streaming_server().await?;
        let config = LanConfig {
            device_name,
            saved_state,
            on_state_change: Box::new(move |state| sink.save(state)),
        };
        let lan = self
            .streaming
            .run(async move { server.serve_lan(config).await })
            .await?
            .map_err(CoreError::from)?;
        let status = serve_status(lan.status());
        self.streaming.set_lan(Some(lan));
        self.apply_network_policy();
        Ok(status)
    }

    /// Stops serving. Torrents only other devices were using stop too.
    pub async fn stop_serving(&self) {
        if let Some(lan) = self.streaming.set_lan(None) {
            let _ = self.streaming.run(async move { lan.stop().await }).await;
        }
    }

    /// The running server, or `None` when not serving.
    pub fn serve_status(&self) -> Option<ServeStatus> {
        self.streaming.with_lan(|lan| serve_status(lan.status()))
    }

    /// Shows a new pairing code, valid for a few minutes and one device.
    pub fn new_pairing_code(&self) -> Result<ServeStatus, CoreError> {
        self.streaming
            .with_lan(|lan| {
                lan.new_pairing_code()?;
                Ok(serve_status(lan.status()))
            })
            .unwrap_or(Err(TorrentError::NoLocalNetwork))
            .map_err(CoreError::from)
    }

    /// Withdraws the pairing code.
    pub fn cancel_pairing_code(&self) {
        self.streaming
            .with_lan(stremionow_torrent::LanServer::cancel_pairing);
    }

    /// Unpairs a device; it has to pair again to use this one.
    pub async fn remove_paired_device(&self, id: String) {
        if let Some(lan) = self.streaming.lan() {
            let _ = self
                .streaming
                .run(async move { lan.remove_device(&id).await })
                .await;
        }
    }

    /// Tells the core whether the device is on mobile data. With "Only on Wi-Fi" set, no
    /// torrent starts on this device meanwhile, for itself or for others.
    pub fn set_on_mobile_data(&self, on_mobile_data: bool) {
        self.streaming.set_on_mobile_data(on_mobile_data);
        self.apply_network_policy();
    }

    /// Servers announcing themselves on the home network, without this device's own. On
    /// Android the app holds a multicast lock meanwhile.
    pub async fn discover_other_devices(&self) -> Vec<FoundDevice> {
        let own = self.streaming.with_lan(|lan| lan.status().server_id);
        self.streaming
            .run(stremionow_torrent::discover(DISCOVERY_TIMEOUT, own))
            .await
            .unwrap_or_default()
            .into_iter()
            .map(|server| FoundDevice {
                name: server.name,
                server_id: server.server_id,
                address: server.address.to_string(),
            })
            .collect()
    }

    /// Pairs with the device at `address` using the code it shows, and uses it for
    /// torrents from now on. `device_name` is how this device appears there. The pairing
    /// goes to `sink`, for the app's encrypted store; pass it to
    /// [`StremioCore::use_other_device`] at the next launch.
    pub async fn pair_with_device(
        &self,
        address: String,
        code: String,
        device_name: String,
        sink: Box<dyn SecretSink>,
    ) -> Result<OtherDevice, PairingError> {
        let paired = self
            .streaming
            .run(async move { stremionow_torrent::pair(&address, &code, &device_name).await })
            .await
            .map_err(|_| PairingError::Failed)??;
        sink.save(paired.save());
        let device = OtherDevice::from(&paired);
        self.streaming.set_remote(Some((paired, sink)));
        Ok(device)
    }

    /// Uses the device paired earlier for torrents. `saved` is what the sink of
    /// [`StremioCore::pair_with_device`] received; `sink` receives it again when the device
    /// moves to another address.
    pub fn use_other_device(
        &self,
        saved: String,
        sink: Box<dyn SecretSink>,
    ) -> Result<OtherDevice, CoreError> {
        let server = RemoteServer::load(&saved)?;
        let device = OtherDevice::from(&server);
        self.streaming.set_remote(Some((server, sink)));
        Ok(device)
    }

    /// The device used for torrents, if any.
    pub fn other_device(&self) -> Option<OtherDevice> {
        self.streaming
            .remote()
            .map(|server| OtherDevice::from(&server))
    }

    /// Checks that the device used for torrents answers and still knows this one. If it
    /// moved to another address, finds it there.
    pub async fn check_other_device(&self) -> OtherDeviceHealth {
        let Some(server) = self.streaming.remote() else {
            return OtherDeviceHealth::Unreachable;
        };
        let checked = self
            .streaming
            .run(async move { server.check().await })
            .await;
        match checked {
            Ok(RemoteHealth::Ready) => OtherDeviceHealth::Ready,
            Ok(RemoteHealth::Unpaired) => OtherDeviceHealth::Unpaired,
            _ => match self.streaming.rediscover().await {
                Some(_) => OtherDeviceHealth::Ready,
                None => OtherDeviceHealth::Unreachable,
            },
        }
    }

    /// Stops using the other device and asks it to forget this one. The app deletes the
    /// saved pairing.
    pub async fn forget_other_device(&self) {
        if let Some(server) = self.streaming.set_remote(None) {
            let _ = self
                .streaming
                .run(async move { server.forget().await })
                .await;
        }
    }
}

impl StremioCore {
    /// Applies "Only on Wi-Fi" to everything that downloads torrents on this device.
    pub(crate) fn apply_network_policy(&self) {
        let wifi_only = self
            .settings()
            .map(|settings| settings.torrent_wifi_only)
            .unwrap_or(false);
        self.streaming.apply_network_policy(wifi_only);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use super::*;

    /// Collects what the core hands to the app's secret store.
    #[derive(Clone, Default)]
    struct Collected(Arc<Mutex<Vec<String>>>);

    impl SecretSink for Collected {
        fn save(&self, value: String) {
            self.0.lock().expect("lock").push(value);
        }
    }

    fn saved_pairing(address: &str) -> String {
        format!(
            r#"{{"version":1,"address":"{address}","server_id":"{}","name":"Living room PC","token":"{}","fingerprint":"{}"}}"#,
            "0".repeat(32),
            "a".repeat(64),
            "03".repeat(32),
        )
    }

    #[tokio::test]
    async fn a_saved_pairing_is_used_until_forgotten() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        assert_eq!(core.other_device(), None);

        let device = core
            .use_other_device(
                saved_pairing("192.168.1.20:47630"),
                Box::new(Collected::default()),
            )
            .expect("pairing");
        assert_eq!(device.name, "Living room PC");
        assert_eq!(device.address, "192.168.1.20:47630");
        assert_eq!(core.other_device(), Some(device));

        core.forget_other_device().await;
        assert_eq!(core.other_device(), None);
    }

    #[test]
    fn pairings_outside_the_local_network_are_refused() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        for saved in [
            saved_pairing("8.8.8.8:47630"),
            "{}".to_owned(),
            String::new(),
        ] {
            assert!(
                core.use_other_device(saved.clone(), Box::new(Collected::default()))
                    .is_err(),
                "{saved}"
            );
        }
        assert_eq!(core.other_device(), None);
    }

    #[tokio::test]
    async fn pairing_codes_need_the_server_running() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        assert_eq!(core.serve_status(), None);
        assert!(matches!(
            core.new_pairing_code(),
            Err(CoreError::NoLocalNetwork)
        ));
        core.cancel_pairing_code();
        core.stop_serving().await;
        assert_eq!(
            core.pair_with_device(
                "8.8.8.8".into(),
                "123456".into(),
                "TV".into(),
                Box::new(Collected::default())
            )
            .await
            .err(),
            Some(PairingError::InvalidAddress)
        );
    }
}
