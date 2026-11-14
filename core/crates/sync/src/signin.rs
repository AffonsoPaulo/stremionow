// SPDX-License-Identifier: GPL-3.0-or-later
//! Sign-in that finishes somewhere else: a link code confirmed on another device, or Facebook
//! and Apple in the system browser.
//!
//! Shapes come from the official clients, since the servers are not open source:
//!
//! - **Link code** (stremio-core `LinkRequest`, `LinkCodeResponse`, `LinkAuthKey`, and the URLs
//!   its `unit_tests/link.rs` asserts): `GET https://link.stremio.com/api/v2/create?type=Create`
//!   answers `{"result": {"code", "link", "qrcode"}}`; `GET .../api/v2/read?type=Read&code=<code>`
//!   answers `{"result": {"authKey"}}` once the user has confirmed the code.
//! - **Facebook** (stremio-web `useFacebookLogin.ts`): the browser opens
//!   `https://www.strem.io/login-fb/<state>`, and `GET .../login-fb-get-acc/<state>` answers
//!   `{"user": {"email", "fbLoginToken"}}` once the user has signed in there. The app then logs
//!   in with `facebook: true` and the token as the password.
//! - **Apple** (stremio-web `useAppleLogin.ts`): the same with `login-apple` and
//!   `login-apple-get-acc`, which answers `{"user": {"token", "sub", "email", "name"}}`; the app
//!   then calls `authWithApple`.
//!
//! The clients poll: none of these endpoints push. Polling itself (how often, how many times,
//! cancelling) is left to the caller, as in stremio-core.

use std::fmt;

use serde::Deserialize;

/// A code to confirm on another device, and the page where it is confirmed.
///
/// The server also sends a QR code image; the app draws its own from [`LinkCode::link`]
/// instead of loading an image from the network.
#[derive(Clone, PartialEq, Eq, Deserialize)]
pub struct LinkCode {
    /// The code the user confirms, shown on screen.
    pub code: String,
    /// The page where the code is confirmed, for the QR code and the browser.
    pub link: String,
}

impl fmt::Debug for LinkCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Whoever confirms the code gets the session, so it stays out of logs.
        f.debug_struct("LinkCode")
            .field("code", &"<redacted>")
            .field("link", &"<redacted>")
            .finish()
    }
}

/// What the website hands over after a Facebook sign-in.
#[derive(Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FacebookCredentials {
    pub email: String,
    /// Sent as the password of a `login` with `facebook: true`.
    pub fb_login_token: String,
}

impl fmt::Debug for FacebookCredentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("FacebookCredentials")
            .field("email", &self.email)
            .field("fb_login_token", &"<redacted>")
            .finish()
    }
}

/// What the website hands over after an Apple sign-in, for `authWithApple`.
#[derive(Clone, PartialEq, Eq, Deserialize)]
pub struct AppleCredentials {
    pub token: String,
    pub sub: String,
    #[serde(default)]
    pub email: String,
    /// Apple does not always share a name; the official app sends an empty one then.
    #[serde(default, deserialize_with = "null_as_empty")]
    pub name: String,
}

impl fmt::Debug for AppleCredentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AppleCredentials")
            .field("token", &"<redacted>")
            .field("sub", &"<redacted>")
            .field("email", &self.email)
            .finish_non_exhaustive()
    }
}

fn null_as_empty<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<String, D::Error> {
    Ok(Option::<String>::deserialize(deserializer)?.unwrap_or_default())
}

/// The account key a confirmed link code gives (stremio-core `LinkAuthKey`).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LinkAuthKey {
    pub(crate) auth_key: String,
}

/// The `{"user": ...}` answer of the website's polling endpoints. Before the sign-in is done
/// it is missing, or the request fails; both mean "not yet".
#[derive(Deserialize)]
pub(crate) struct WebAccount<T> {
    pub(crate) user: Option<T>,
}

/// Whether `state` is one this crate's callers create: 32 lowercase hex digits (128 bits), as
/// stremio-web's `hat(128)`. It goes into a URL path, so nothing else is accepted.
pub fn is_valid_state(state: &str) -> bool {
    state.len() == 32
        && state
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// `bytes` as lowercase hex, for a sign-in state made of random bytes.
pub fn state_from_bytes(bytes: [u8; 16]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn states_are_32_hex_digits() {
        let state = state_from_bytes([0xab; 16]);
        assert_eq!(state, "ab".repeat(16));
        assert!(is_valid_state(&state));
        assert!(!is_valid_state("ab"));
        assert!(
            !is_valid_state(&"AB".repeat(16)),
            "uppercase is not produced"
        );
        assert!(!is_valid_state(&format!("{}/x", "a".repeat(30))));
    }

    #[test]
    fn debug_output_hides_secrets() {
        let link = LinkCode {
            code: "SECRET".into(),
            link: "https://www.stremio.com/link?code=SECRET".into(),
        };
        let facebook = FacebookCredentials {
            email: "me@example.com".into(),
            fb_login_token: "fb-secret".into(),
        };
        let apple: AppleCredentials = serde_json::from_value(
            json!({"token": "apple-secret", "sub": "sub-secret", "email": "me@example.com", "name": null}),
        )
        .expect("parses");
        let debug = format!("{link:?} {facebook:?} {apple:?}");
        for secret in ["SECRET", "fb-secret", "apple-secret", "sub-secret"] {
            assert!(!debug.contains(secret), "{debug}");
        }
        assert_eq!(apple.name, "");
    }
}
