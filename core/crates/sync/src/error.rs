// SPDX-License-Identifier: GPL-3.0-or-later
//! Errors of the account API.

/// Why a call to the Stremio account API failed.
///
/// Messages never contain the password, the auth key or response bodies, so they are safe to
/// log and to show in the app.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SyncError {
    /// The email is not registered or the password is wrong.
    #[error("wrong email or password")]
    InvalidCredentials,
    /// The auth key is no longer valid; the user has to log in again.
    #[error("the Stremio session has expired")]
    SessionExpired,
    /// The server could not be reached, or the request timed out.
    #[error("could not reach the Stremio API: {0}")]
    Network(String),
    /// The server answered with HTTP `status` and no API error envelope.
    #[error("the Stremio API answered with HTTP {0}")]
    Status(u16),
    /// The server reported an error not covered by the variants above.
    #[error("the Stremio API reported error {code}: {message}")]
    Server { code: u64, message: String },
    /// The response could not be understood.
    #[error("the Stremio API sent a response that could not be read: {0}")]
    InvalidResponse(String),
}

impl SyncError {
    /// Maps a transport error. The URL is dropped to keep error messages short and uniform;
    /// it never contains secrets here (they travel in the body), but the policy matches the
    /// addon client.
    pub(crate) fn from_reqwest(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            Self::Network("the request timed out".to_owned())
        } else if let Some(status) = error.status() {
            Self::Status(status.as_u16())
        } else {
            Self::Network(error.without_url().to_string())
        }
    }

    /// Describes a JSON error by category and position only: serde messages can quote the
    /// offending value, which may be an auth key.
    pub(crate) fn from_json(error: &serde_json::Error) -> Self {
        Self::InvalidResponse(format!(
            "unexpected {:?} at line {} column {}",
            error.classify(),
            error.line(),
            error.column()
        ))
    }
}
