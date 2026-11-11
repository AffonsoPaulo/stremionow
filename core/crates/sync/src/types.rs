// SPDX-License-Identifier: GPL-3.0-or-later
//! Account and addon collection types.
//!
//! Shapes follow stremio-core: `AuthResponse` and `User` (`src/types/api/response.rs`,
//! `src/types/profile/user.rs`) and `Descriptor` (`src/types/addon/descriptor.rs`).

use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use stremionow_protocol::Manifest;

use crate::SyncError;

/// A logged-in Stremio account: the auth key sent with every call, and the user it belongs to.
///
/// `Debug` redacts the auth key. Serialized as `{"authKey", "user"}`, the shape of the
/// `login` result, so it can be stored and restored as is.
#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub auth_key: String,
    pub user: User,
}

impl fmt::Debug for Session {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Session")
            .field("auth_key", &"<redacted>")
            .field("user", &self.user)
            .finish()
    }
}

/// A Stremio account as returned by `login` and `getUser`.
///
/// Only the fields an app needs are typed; the rest (GDPR consent, Trakt tokens, ...) is kept
/// in `extra`. `Debug` lists only the names of the extra fields, because they include tokens.
#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    #[serde(rename = "_id")]
    pub id: String,
    pub email: String,
    #[serde(default)]
    pub avatar: Option<String>,
    /// RFC 3339 timestamp.
    #[serde(default)]
    pub date_registered: Option<String>,
    /// RFC 3339 timestamp.
    #[serde(default)]
    pub last_modified: Option<String>,
    /// RFC 3339 timestamp; `None` without a premium subscription.
    #[serde(default, rename = "premium_expire")]
    pub premium_expire: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl fmt::Debug for User {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("User")
            .field("id", &self.id)
            .field("email", &self.email)
            .field("avatar", &self.avatar)
            .field("date_registered", &self.date_registered)
            .field("last_modified", &self.last_modified)
            .field("premium_expire", &self.premium_expire)
            .field("extra", &self.extra.keys().collect::<Vec<_>>())
            .finish()
    }
}

/// An addon in the account's collection (stremio-core `Descriptor`).
///
/// The manifest is kept as the JSON the server sent, so writing the collection back does not
/// drop fields this app does not model. Use [`RemoteAddon::parsed_manifest`] to read it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteAddon {
    pub manifest: Value,
    /// URL of the addon's `manifest.json`. Configured addons keep credentials in it.
    pub transport_url: String,
    #[serde(default)]
    pub flags: AddonFlags,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl RemoteAddon {
    /// An addon the user installed, with no flags set.
    pub fn new(transport_url: impl Into<String>, manifest: Value) -> Self {
        Self {
            manifest,
            transport_url: transport_url.into(),
            flags: AddonFlags::default(),
            extra: Map::new(),
        }
    }

    /// The manifest parsed into the protocol type.
    pub fn parsed_manifest(&self) -> Result<Manifest, SyncError> {
        Manifest::deserialize(&self.manifest).map_err(|e| SyncError::from_json(&e))
    }
}

/// Flags the official apps set on an addon (stremio-core `DescriptorFlags`).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddonFlags {
    /// Shipped by Stremio itself.
    #[serde(default)]
    pub official: bool,
    /// Cannot be uninstalled by the user.
    #[serde(default)]
    pub protected: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn user() -> User {
        serde_json::from_value(json!({
            "_id": "u1",
            "email": "user@example.com",
            "trakt": { "access_token": "trakt-secret" },
        }))
        .expect("valid user")
    }

    #[test]
    fn session_debug_redacts_the_auth_key() {
        let session = Session {
            auth_key: "secret-auth-key".to_owned(),
            user: user(),
        };
        let debug = format!("{session:?}");
        assert!(!debug.contains("secret-auth-key"), "{debug}");
        assert!(!debug.contains("trakt-secret"), "{debug}");
        assert!(debug.contains("u1"));
    }

    #[test]
    fn user_keeps_unknown_fields() {
        let value = serde_json::to_value(user()).expect("serializable");
        assert_eq!(value["trakt"]["access_token"], "trakt-secret");
    }

    #[test]
    fn parses_a_remote_manifest() {
        let addon = RemoteAddon::new(
            "https://example.com/manifest.json",
            json!({ "id": "org.example", "version": "1.0.0", "name": "Example" }),
        );
        let manifest = addon.parsed_manifest().expect("valid manifest");
        assert_eq!(manifest.id, "org.example");
    }
}
