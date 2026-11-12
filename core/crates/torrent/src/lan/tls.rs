// SPDX-License-Identifier: GPL-3.0-or-later
//! TLS between devices: a self-signed certificate per server, pinned by its clients.
//!
//! No certificate authority can vouch for a device on a home network, so clients trust the
//! one certificate they saw while pairing. Pairing proves that certificate belongs to the
//! device whose code the user typed (see `pairing`), and every later connection must
//! present exactly that certificate.

use std::error::Error;
use std::io;
use std::sync::{Arc, Mutex, PoisonError};

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, WebPkiSupportedAlgorithms};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{CertificateError, DigitallySignedStruct, SignatureScheme};

use crate::TorrentError;

/// SHA-256 of a certificate in DER form.
pub(crate) type Fingerprint = [u8; 32];

/// The server's certificate and private key.
pub(crate) struct Identity {
    pub(crate) cert: CertificateDer<'static>,
    pub(crate) key: PrivatePkcs8KeyDer<'static>,
}

impl Identity {
    /// A fresh ECDSA P-256 key and a certificate for it. The names in it do not matter:
    /// clients check the fingerprint, not the name.
    pub(crate) fn generate() -> Result<Self, TorrentError> {
        let generated = rcgen::generate_simple_self_signed(vec!["stremionow.local".to_owned()])
            .map_err(io::Error::other)?;
        Ok(Self {
            cert: generated.cert.der().clone(),
            key: PrivatePkcs8KeyDer::from(generated.signing_key.serialize_der()),
        })
    }

    pub(crate) fn fingerprint(&self) -> Fingerprint {
        fingerprint(&self.cert)
    }

    /// TLS 1.3 only, HTTP/1.1: both ends are this app, so nothing older is needed.
    pub(crate) fn server_config(&self) -> Result<Arc<rustls::ServerConfig>, TorrentError> {
        let mut config = rustls::ServerConfig::builder_with_provider(provider())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(io::Error::other)?
            .with_no_client_auth()
            .with_single_cert(
                vec![self.cert.clone()],
                PrivateKeyDer::Pkcs8(self.key.clone_key()),
            )
            .map_err(io::Error::other)?;
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(Arc::new(config))
    }
}

pub(crate) fn fingerprint(cert: &[u8]) -> Fingerprint {
    let digest = ring::digest::digest(&ring::digest::SHA256, cert);
    let mut out = [0u8; 32];
    out.copy_from_slice(digest.as_ref());
    out
}

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// A client configuration that accepts only the server certificate `verifier` allows.
pub(crate) fn client_config(
    verifier: Arc<PinnedVerifier>,
) -> Result<rustls::ClientConfig, TorrentError> {
    let mut config = rustls::ClientConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(io::Error::other)?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(config)
}

/// Accepts one server certificate, chosen by its fingerprint.
///
/// While pairing nothing is pinned yet: the verifier accepts the first certificate it sees,
/// remembers it, and refuses any other one afterwards. The pairing exchange then proves the
/// remembered certificate is the right server's; only after that is it saved as the pin.
#[derive(Debug)]
pub(crate) struct PinnedVerifier {
    pinned: Mutex<Option<Fingerprint>>,
    algorithms: WebPkiSupportedAlgorithms,
}

impl PinnedVerifier {
    /// Accepts only the certificate with `fingerprint`.
    pub(crate) fn pinned(fingerprint: Fingerprint) -> Arc<Self> {
        Arc::new(Self {
            pinned: Mutex::new(Some(fingerprint)),
            algorithms: provider().signature_verification_algorithms,
        })
    }

    /// Accepts the first certificate presented, then only that one.
    pub(crate) fn first_seen() -> Arc<Self> {
        Arc::new(Self {
            pinned: Mutex::new(None),
            algorithms: provider().signature_verification_algorithms,
        })
    }

    /// The certificate accepted so far, if any.
    pub(crate) fn fingerprint(&self) -> Option<Fingerprint> {
        *self.pinned.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// Whether `error`, or one of its causes, is this verifier refusing a certificate other than
/// the pinned one. Read from the failed request itself, so concurrent requests cannot take
/// each other's answer.
pub(crate) fn is_pin_mismatch(error: &(dyn Error + 'static)) -> bool {
    let mut current = Some(error);
    while let Some(error) = current {
        if matches!(
            error.downcast_ref::<rustls::Error>(),
            Some(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure
            ))
        ) {
            return true;
        }
        // An I/O error's `source` skips the error it wraps, which is where TLS puts its own.
        current = match error.downcast_ref::<std::io::Error>() {
            Some(io) => io.get_ref().map(|inner| inner as &(dyn Error + 'static)),
            None => error.source(),
        };
    }
    false
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let seen = fingerprint(end_entity);
        let mut pinned = self.pinned.lock().unwrap_or_else(PoisonError::into_inner);
        match *pinned {
            Some(expected) if crate::secret::constant_time_eq_bytes(&seen, &expected) => {
                Ok(ServerCertVerified::assertion())
            }
            Some(_) => Err(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            )),
            None => {
                *pinned = Some(seen);
                Ok(ServerCertVerified::assertion())
            }
        }
    }

    // The handshake signature proves the server holds the certificate's private key, so a
    // copied certificate alone does not pass the pin.
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identities_are_unique_and_usable() {
        let first = Identity::generate().expect("identity");
        let second = Identity::generate().expect("identity");
        assert_ne!(first.fingerprint(), second.fingerprint());
        assert!(first.server_config().is_ok());
    }

    #[test]
    fn first_seen_pins_the_first_certificate() {
        let first = Identity::generate().expect("identity");
        let other = Identity::generate().expect("identity");
        let verifier = PinnedVerifier::first_seen();
        let name = ServerName::try_from("192.168.1.2").expect("name");
        let check = |cert: &CertificateDer<'_>| {
            verifier
                .verify_server_cert(cert, &[], &name, &[], UnixTime::now())
                .is_ok()
        };
        assert!(check(&first.cert));
        assert!(check(&first.cert));
        assert!(!check(&other.cert), "a second certificate is refused");
        assert_eq!(verifier.fingerprint(), Some(first.fingerprint()));

        let pinned = PinnedVerifier::pinned(other.fingerprint());
        let refused = pinned
            .verify_server_cert(&first.cert, &[], &name, &[], UnixTime::now())
            .expect_err("another certificate");
        assert!(is_pin_mismatch(&refused));
        let wrapped = std::io::Error::other(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            refused,
        ));
        assert!(is_pin_mismatch(&wrapped), "found inside nested I/O errors");
        assert!(!is_pin_mismatch(&std::io::Error::other(
            "connection refused"
        )));
    }
}
