// SPDX-License-Identifier: GPL-3.0-or-later
//! Pairing: trading a short code shown on the server for a long per-device token.
//!
//! The code is typed on the client. It is never sent: both sides run SPAKE2, a
//! password-authenticated key exchange, with the code as the password. Each exchange gives
//! an eavesdropper nothing to test guesses against offline, and an active attacker one guess
//! per exchange. With the shared key, the client proves it knows the code and reports the
//! certificate fingerprint it saw; the server answers only if that is its own certificate,
//! and proves in turn that it knew the code. A device in the middle (ARP spoofing, a rogue
//! access point) would present its own certificate, so the fingerprints would not match and
//! the token would never be sent.
//!
//! Guessing online is bounded: a code lasts [`CODE_LIFETIME`], allows [`MAX_ATTEMPTS`]
//! exchanges in all and [`MAX_ATTEMPTS_PER_PEER`] from one address, and works once. Each
//! address may send one first message per [`MIN_ATTEMPT_INTERVAL`], whether or not a code
//! is showing, so polling for a code to appear is as slow as guessing. Several addresses
//! still can spend a code's attempts; the server's screen then says another device is
//! trying, and the user shows a new code.

use std::collections::HashMap;
use std::net::IpAddr;
use std::time::{Duration, Instant};

use ring::hmac;
use spake2::{Ed25519Group, Identity, Password, Spake2};

use super::tls::Fingerprint;
use crate::secret;

/// Digits in a pairing code: easy to type with a TV remote, and with the limits below an
/// attacker's chance of guessing one is about 1 in 100 000.
pub(crate) const CODE_DIGITS: usize = 6;

/// How long a code can be used after it is shown.
pub(crate) const CODE_LIFETIME: Duration = Duration::from_secs(3 * 60);

/// Exchanges allowed per code before it is withdrawn.
pub(crate) const MAX_ATTEMPTS: u32 = 10;

/// Exchanges one address may start per code. A person mistyping needs a couple.
pub(crate) const MAX_ATTEMPTS_PER_PEER: u32 = 3;

/// Shortest time between two first messages from one address.
pub(crate) const MIN_ATTEMPT_INTERVAL: Duration = Duration::from_secs(1);

/// Shortest time between two first messages from any addresses, bounding the work one
/// burst of addresses can cause.
const MIN_GLOBAL_INTERVAL: Duration = Duration::from_millis(100);

/// How long a started exchange waits for its second message.
const EXCHANGE_LIFETIME: Duration = Duration::from_secs(30);

/// Exchanges waiting for their second message at once.
const MAX_EXCHANGES: usize = 4;

const CLIENT_ID: &[u8] = b"stremionow client";
const SERVER_ID: &[u8] = b"stremionow server";
const CLIENT_CONFIRM: &[u8] = b"stremionow pairing v1 client";
const SERVER_CONFIRM: &[u8] = b"stremionow pairing v1 server";

/// Why the server turned down a pairing message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Refusal {
    /// No code is being shown, or it expired or was used.
    NoCode,
    /// Too soon after the previous exchange; retry after the given time.
    TooSoon(Duration),
    /// Too many guesses from this address, or the code was withdrawn after too many in all.
    TooManyAttempts,
    /// The exchange is unknown or expired.
    UnknownExchange,
    /// The client's proof does not match: a wrong code, or someone in the middle.
    WrongCode,
    /// The message is not a valid SPAKE2 message.
    Malformed,
}

impl Refusal {
    /// The `error` value of the JSON answer.
    pub(crate) fn code(self) -> &'static str {
        match self {
            Self::NoCode => "no_code",
            Self::TooSoon(_) => "too_soon",
            Self::TooManyAttempts => "too_many_attempts",
            Self::UnknownExchange => "unknown_exchange",
            Self::WrongCode => "wrong_code",
            Self::Malformed => "malformed",
        }
    }
}

/// The server's side: the code on screen and the exchanges in progress.
#[derive(Default)]
pub(crate) struct Pairing {
    code: Option<ActiveCode>,
    /// Latest first message per address, kept for [`MIN_ATTEMPT_INTERVAL`].
    recent: HashMap<IpAddr, Instant>,
    last_attempt: Option<Instant>,
    exchanges: HashMap<String, Exchange>,
    /// The last code was withdrawn after too many attempts.
    withdrawn: bool,
}

struct ActiveCode {
    digits: String,
    expires: Instant,
    attempts: u32,
    per_peer: HashMap<IpAddr, u32>,
}

struct Exchange {
    key: Vec<u8>,
    started: Instant,
    peer: IpAddr,
}

impl Pairing {
    /// Shows a new code, replacing any previous one, and returns it with its expiry.
    pub(crate) fn new_code(&mut self, now: Instant) -> std::io::Result<(String, Instant)> {
        let digits = random_code()?;
        let expires = now + CODE_LIFETIME;
        self.code = Some(ActiveCode {
            digits: digits.clone(),
            expires,
            attempts: 0,
            per_peer: HashMap::new(),
        });
        self.exchanges.clear();
        self.withdrawn = false;
        Ok((digits, expires))
    }

    /// Whether more than one address tried the code on screen, or the last code was
    /// withdrawn after too many attempts: someone else on the network is trying to pair.
    pub(crate) fn contested(&self) -> bool {
        self.withdrawn
            || self
                .code
                .as_ref()
                .is_some_and(|code| code.per_peer.len() > 1)
    }

    /// The code on screen and the time it has left, if it is still valid.
    pub(crate) fn current(&self, now: Instant) -> Option<(String, Duration)> {
        let code = self.code.as_ref()?;
        let left = code.expires.checked_duration_since(now)?;
        (!left.is_zero()).then(|| (code.digits.clone(), left))
    }

    /// Withdraws the code.
    pub(crate) fn cancel(&mut self) {
        self.code = None;
        self.exchanges.clear();
    }

    /// First message: the client's SPAKE2 message from `peer`. Returns the exchange id and
    /// the server's SPAKE2 message. Every call counts as a guess, right or wrong, and the
    /// rate limit applies before anything else is revealed, even whether a code is showing.
    pub(crate) fn start(
        &mut self,
        now: Instant,
        peer: IpAddr,
        client_message: &[u8],
    ) -> Result<(String, Vec<u8>), Refusal> {
        self.exchanges
            .retain(|_, exchange| now.duration_since(exchange.started) < EXCHANGE_LIFETIME);
        self.recent
            .retain(|_, last| now.duration_since(*last) < MIN_ATTEMPT_INTERVAL);
        if let Some(last) = self.recent.get(&peer) {
            return Err(Refusal::TooSoon(
                MIN_ATTEMPT_INTERVAL - now.duration_since(*last),
            ));
        }
        if let Some(last) = self.last_attempt {
            let since = now.duration_since(last);
            if since < MIN_GLOBAL_INTERVAL {
                return Err(Refusal::TooSoon(MIN_GLOBAL_INTERVAL - since));
            }
        }
        self.recent.insert(peer, now);
        self.last_attempt = Some(now);
        let Some(code) = self.code.as_mut() else {
            return Err(Refusal::NoCode);
        };
        if now >= code.expires {
            self.cancel();
            return Err(Refusal::NoCode);
        }
        if code.attempts >= MAX_ATTEMPTS {
            self.cancel();
            self.withdrawn = true;
            return Err(Refusal::TooManyAttempts);
        }
        let mine = code.per_peer.entry(peer).or_default();
        if *mine >= MAX_ATTEMPTS_PER_PEER {
            return Err(Refusal::TooManyAttempts);
        }
        *mine += 1;
        code.attempts += 1;
        let (state, server_message) = Spake2::<Ed25519Group>::start_b(
            &Password::new(code.digits.as_bytes()),
            &Identity::new(CLIENT_ID),
            &Identity::new(SERVER_ID),
        );
        let key = state
            .finish(client_message)
            .map_err(|_| Refusal::Malformed)?;
        if self.exchanges.len() >= MAX_EXCHANGES
            && let Some(oldest) = self
                .exchanges
                .iter()
                .min_by_key(|(_, exchange)| exchange.started)
                .map(|(id, _)| id.clone())
        {
            self.exchanges.remove(&oldest);
        }
        let id = secret::random_hex().map_err(|_| Refusal::Malformed)?;
        self.exchanges.insert(
            id.clone(),
            Exchange {
                key,
                started: now,
                peer,
            },
        );
        Ok((id, server_message))
    }

    /// Second message: the client's proof over the certificate fingerprint it saw. On
    /// success the code is used up and the shared key is returned, to prove the server's
    /// side in the answer.
    pub(crate) fn finish(
        &mut self,
        now: Instant,
        peer: IpAddr,
        exchange: &str,
        client_proof: &[u8],
        own_fingerprint: &Fingerprint,
    ) -> Result<Vec<u8>, Refusal> {
        let Some(exchange) = self.exchanges.remove(exchange) else {
            return Err(Refusal::UnknownExchange);
        };
        if exchange.peer != peer || now.duration_since(exchange.started) >= EXCHANGE_LIFETIME {
            return Err(Refusal::UnknownExchange);
        }
        if self.current(now).is_none() {
            return Err(Refusal::NoCode);
        }
        let key = hmac::Key::new(hmac::HMAC_SHA256, &exchange.key);
        if hmac::verify(&key, &client_message(own_fingerprint), client_proof).is_err() {
            return Err(Refusal::WrongCode);
        }
        self.cancel();
        Ok(exchange.key)
    }
}

/// What the client signs: the certificate it saw.
fn client_message(fingerprint: &Fingerprint) -> Vec<u8> {
    [CLIENT_CONFIRM, fingerprint.as_slice()].concat()
}

/// What the server signs: its certificate, its id and the token it hands out.
fn server_message(fingerprint: &Fingerprint, server_id: &str, token: &str) -> Vec<u8> {
    [
        SERVER_CONFIRM,
        fingerprint.as_slice(),
        server_id.as_bytes(),
        b"\0",
        token.as_bytes(),
    ]
    .concat()
}

/// The server's proof for the answer to a successful exchange.
pub(crate) fn server_proof(
    key: &[u8],
    fingerprint: &Fingerprint,
    server_id: &str,
    token: &str,
) -> Vec<u8> {
    let key = hmac::Key::new(hmac::HMAC_SHA256, key);
    hmac::sign(&key, &server_message(fingerprint, server_id, token))
        .as_ref()
        .to_vec()
}

/// The client's side of one exchange.
pub(crate) struct ClientExchange {
    state: Spake2<Ed25519Group>,
}

impl ClientExchange {
    /// Starts an exchange for `code` and returns the message to send.
    pub(crate) fn start(code: &str) -> (Self, Vec<u8>) {
        let (state, message) = Spake2::<Ed25519Group>::start_a(
            &Password::new(code.as_bytes()),
            &Identity::new(CLIENT_ID),
            &Identity::new(SERVER_ID),
        );
        (Self { state }, message)
    }

    /// Takes the server's message and returns the shared key, or `None` if it is malformed.
    pub(crate) fn finish(self, server_message: &[u8]) -> Option<ClientKey> {
        self.state.finish(server_message).ok().map(ClientKey)
    }
}

/// The key a client shares with the server after an exchange.
pub(crate) struct ClientKey(Vec<u8>);

impl ClientKey {
    /// The proof to send, over the certificate this client saw.
    pub(crate) fn proof(&self, fingerprint: &Fingerprint) -> Vec<u8> {
        let key = hmac::Key::new(hmac::HMAC_SHA256, &self.0);
        hmac::sign(&key, &client_message(fingerprint))
            .as_ref()
            .to_vec()
    }

    /// Whether the server's answer proves it knew the code, for this certificate, id and token.
    pub(crate) fn verify_server(
        &self,
        fingerprint: &Fingerprint,
        server_id: &str,
        token: &str,
        proof: &[u8],
    ) -> bool {
        let key = hmac::Key::new(hmac::HMAC_SHA256, &self.0);
        hmac::verify(&key, &server_message(fingerprint, server_id, token), proof).is_ok()
    }
}

/// A uniformly random code of [`CODE_DIGITS`] decimal digits.
fn random_code() -> std::io::Result<String> {
    const RANGE: u32 = 1_000_000;
    // Rejecting the top of the u32 range keeps every code equally likely.
    const LIMIT: u32 = u32::MAX - (u32::MAX % RANGE);
    loop {
        let value = u32::from_le_bytes(secret::random_bytes::<4>()?);
        if value < LIMIT {
            return Ok(format!("{:0width$}", value % RANGE, width = CODE_DIGITS));
        }
    }
}

/// Keeps the digits of what the user typed, so `123 456` and `123-456` work.
pub(crate) fn normalize_code(typed: &str) -> Option<String> {
    let digits: String = typed.chars().filter(char::is_ascii_digit).collect();
    (digits.len() == CODE_DIGITS
        && typed
            .chars()
            .all(|c| c.is_ascii_digit() || " -".contains(c)))
    .then_some(digits)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FINGERPRINT: Fingerprint = [7; 32];

    fn peer(last: u8) -> IpAddr {
        IpAddr::from([192, 168, 1, last])
    }

    /// Runs a whole exchange from address `.20` with `typed` as the client's code.
    fn exchange(
        pairing: &mut Pairing,
        now: Instant,
        typed: &str,
        seen: &Fingerprint,
    ) -> Result<Vec<u8>, Refusal> {
        exchange_from(pairing, now, peer(20), typed, seen)
    }

    fn exchange_from(
        pairing: &mut Pairing,
        now: Instant,
        from: IpAddr,
        typed: &str,
        seen: &Fingerprint,
    ) -> Result<Vec<u8>, Refusal> {
        let (client, message) = ClientExchange::start(typed);
        let (id, reply) = pairing.start(now, from, &message)?;
        let key = client.finish(&reply).ok_or(Refusal::Malformed)?;
        pairing.finish(now, from, &id, &key.proof(seen), &FINGERPRINT)
    }

    #[test]
    fn codes_are_six_random_digits() {
        let first = random_code().expect("code");
        assert_eq!(first.len(), CODE_DIGITS);
        assert!(first.bytes().all(|b| b.is_ascii_digit()));
        let distinct: std::collections::HashSet<_> =
            (0..50).map(|_| random_code().expect("code")).collect();
        assert!(distinct.len() > 40);
    }

    #[test]
    fn the_right_code_pairs_once() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        let (code, _) = pairing.new_code(now).expect("code");
        let key = exchange(&mut pairing, now, &code, &FINGERPRINT).expect("paired");
        assert!(!key.is_empty());
        assert_eq!(pairing.current(now), None, "the code is used up");
        let later = now + MIN_ATTEMPT_INTERVAL;
        assert_eq!(
            exchange(&mut pairing, later, &code, &FINGERPRINT),
            Err(Refusal::NoCode)
        );
    }

    #[test]
    fn both_sides_prove_the_same_key() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        let (code, _) = pairing.new_code(now).expect("code");
        let (client, message) = ClientExchange::start(&code);
        let (id, reply) = pairing.start(now, peer(20), &message).expect("started");
        let client_key = client.finish(&reply).expect("key");
        let server_key = pairing
            .finish(
                now,
                peer(20),
                &id,
                &client_key.proof(&FINGERPRINT),
                &FINGERPRINT,
            )
            .expect("paired");
        let proof = server_proof(&server_key, &FINGERPRINT, "server", "token");
        assert!(client_key.verify_server(&FINGERPRINT, "server", "token", &proof));
        assert!(!client_key.verify_server(&FINGERPRINT, "server", "other token", &proof));
        assert!(!client_key.verify_server(&[8; 32], "server", "token", &proof));
    }

    #[test]
    fn a_wrong_code_or_another_certificate_is_refused() {
        let mut pairing = Pairing::default();
        let mut now = Instant::now();
        let (code, _) = pairing.new_code(now).expect("code");
        let wrong = if code == "000000" { "000001" } else { "000000" };
        assert_eq!(
            exchange(&mut pairing, now, wrong, &FINGERPRINT),
            Err(Refusal::WrongCode)
        );
        now += MIN_ATTEMPT_INTERVAL;
        // Someone in the middle shows the client its own certificate.
        assert_eq!(
            exchange(&mut pairing, now, &code, &[9; 32]),
            Err(Refusal::WrongCode)
        );
        now += MIN_ATTEMPT_INTERVAL;
        assert!(exchange(&mut pairing, now, &code, &FINGERPRINT).is_ok());
    }

    #[test]
    fn guesses_are_rate_limited_per_address() {
        let mut pairing = Pairing::default();
        let mut now = Instant::now();
        // Polling before a code appears is limited too.
        assert_eq!(
            exchange(&mut pairing, now, "000000", &FINGERPRINT),
            Err(Refusal::NoCode)
        );
        assert!(matches!(
            exchange(&mut pairing, now, "000000", &FINGERPRINT),
            Err(Refusal::TooSoon(_))
        ));
        let (code, _) = pairing.new_code(now).expect("code");
        let wrong = if code == "000000" { "000001" } else { "000000" };
        for _ in 0..MAX_ATTEMPTS_PER_PEER {
            now += MIN_ATTEMPT_INTERVAL;
            assert_eq!(
                exchange(&mut pairing, now, wrong, &FINGERPRINT),
                Err(Refusal::WrongCode)
            );
        }
        now += MIN_ATTEMPT_INTERVAL;
        assert_eq!(
            exchange(&mut pairing, now, &code, &FINGERPRINT),
            Err(Refusal::TooManyAttempts),
            "one address cannot keep guessing"
        );
        assert!(!pairing.contested(), "one address is not someone else");
        // Another device still can pair with the same code, and the server notices.
        now += MIN_GLOBAL_INTERVAL;
        assert!(exchange_from(&mut pairing, now, peer(30), &code, &FINGERPRINT).is_ok());
    }

    #[test]
    fn many_addresses_spend_a_code_and_are_reported() {
        let mut pairing = Pairing::default();
        let mut now = Instant::now();
        let (code, _) = pairing.new_code(now).expect("code");
        let wrong = if code == "000000" { "000001" } else { "000000" };
        let mut address = 100;
        for _ in 0..MAX_ATTEMPTS {
            now += MIN_GLOBAL_INTERVAL;
            address += 1;
            assert_eq!(
                exchange_from(&mut pairing, now, peer(address), wrong, &FINGERPRINT),
                Err(Refusal::WrongCode)
            );
        }
        assert!(pairing.contested());
        now += MIN_GLOBAL_INTERVAL;
        assert_eq!(
            exchange(&mut pairing, now, &code, &FINGERPRINT),
            Err(Refusal::TooManyAttempts),
            "even the right code fails once the guesses are spent"
        );
        assert_eq!(pairing.current(now), None);
        assert!(
            pairing.contested(),
            "the screen says why the code went away"
        );
        pairing.new_code(now).expect("code");
        assert!(!pairing.contested());
    }

    #[test]
    fn bursts_from_many_addresses_are_slowed() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        pairing.new_code(now).expect("code");
        let _ = exchange_from(&mut pairing, now, peer(1), "000000", &FINGERPRINT);
        assert!(matches!(
            exchange_from(&mut pairing, now, peer(2), "000000", &FINGERPRINT),
            Err(Refusal::TooSoon(_))
        ));
    }

    #[test]
    fn codes_expire() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        let (code, expires) = pairing.new_code(now).expect("code");
        assert_eq!(expires, now + CODE_LIFETIME);
        assert!(pairing.current(now + CODE_LIFETIME / 2).is_some());
        let late = now + CODE_LIFETIME;
        assert_eq!(pairing.current(late), None);
        assert_eq!(
            exchange(&mut pairing, late, &code, &FINGERPRINT),
            Err(Refusal::NoCode)
        );
    }

    #[test]
    fn exchanges_expire_and_are_single_use() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        let (code, _) = pairing.new_code(now).expect("code");
        let (client, message) = ClientExchange::start(&code);
        let (id, reply) = pairing.start(now, peer(20), &message).expect("started");
        let proof = client.finish(&reply).expect("key").proof(&FINGERPRINT);
        assert_eq!(
            pairing.finish(now, peer(21), &id, &proof, &FINGERPRINT),
            Err(Refusal::UnknownExchange),
            "an exchange belongs to the address that started it"
        );
        let (client, message) = ClientExchange::start(&code);
        let later = now + MIN_ATTEMPT_INTERVAL;
        let (id, reply) = pairing.start(later, peer(20), &message).expect("started");
        let proof = client.finish(&reply).expect("key").proof(&FINGERPRINT);
        let late = later + EXCHANGE_LIFETIME;
        assert_eq!(
            pairing.finish(late, peer(20), &id, &proof, &FINGERPRINT),
            Err(Refusal::UnknownExchange)
        );
        assert_eq!(
            pairing.finish(later, peer(20), &id, &proof, &FINGERPRINT),
            Err(Refusal::UnknownExchange),
            "an exchange is gone after one answer"
        );
    }

    #[test]
    fn a_new_code_replaces_the_old_one() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        let (old, _) = pairing.new_code(now).expect("code");
        let (new, _) = pairing.new_code(now).expect("code");
        assert_eq!(
            pairing.current(now).map(|(code, _)| code),
            Some(new.clone())
        );
        if old != new {
            assert_eq!(
                exchange(&mut pairing, now, &old, &FINGERPRINT),
                Err(Refusal::WrongCode)
            );
        }
        pairing.cancel();
        assert_eq!(pairing.current(now), None);
    }

    #[test]
    fn malformed_messages_are_refused() {
        let mut pairing = Pairing::default();
        let now = Instant::now();
        pairing.new_code(now).expect("code");
        assert_eq!(
            pairing.start(now, peer(20), b"junk"),
            Err(Refusal::Malformed)
        );
    }

    #[test]
    fn typed_codes_keep_only_digits() {
        assert_eq!(normalize_code("123456").as_deref(), Some("123456"));
        assert_eq!(normalize_code(" 123 456 ").as_deref(), Some("123456"));
        assert_eq!(normalize_code("123-456").as_deref(), Some("123456"));
        assert_eq!(normalize_code("12345"), None);
        assert_eq!(normalize_code("1234567"), None);
        assert_eq!(normalize_code("12a456"), None);
    }
}
