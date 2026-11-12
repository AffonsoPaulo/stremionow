// SPDX-License-Identifier: GPL-3.0-or-later
//! Tells the app about panics in the core, so it can save a crash report.
//!
//! UniFFI catches a panic inside an exported call and raises it in Kotlin as an
//! `InternalException` that carries only the panic message. Panics on the core's own threads
//! (the async runtime, the torrent engine) never reach Kotlin at all. The hook installed here
//! sees every panic, wherever it happens, and hands its message, source location and thread to
//! the app's [`PanicListener`] before the default hook runs. The app redacts and stores it; the
//! core itself writes nothing.

use std::cell::Cell;
use std::panic::{self, PanicHookInfo};
use std::sync::{Arc, Once, RwLock};

/// What the core knows about a panic when it happens.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PanicDetails {
    /// The panic message. It can contain anything the failing code formatted into it,
    /// addresses included, so the app must redact it before showing or storing it.
    pub message: String,
    /// Source location, as `file:line:column`.
    pub location: String,
    /// Name of the thread that panicked, or an empty string for unnamed threads.
    pub thread: String,
}

/// Receives the core's panics. Called on the panicking thread, before it unwinds; keep it
/// short and never let it throw.
#[uniffi::export(callback_interface)]
pub trait PanicListener: Send + Sync {
    fn on_panic(&self, details: PanicDetails);
}

/// A crash the debug menu can cause on purpose, to test crash reports on a device.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TestCrash {
    /// Panics inside this call; Kotlin receives an `InternalException`.
    PanicInCall,
    /// Panics on a new core thread; the app keeps running.
    PanicInBackground,
    /// Aborts the process, as a native crash would.
    Abort,
}

static INSTALL: Once = Once::new();
static LISTENER: RwLock<Option<Arc<dyn PanicListener>>> = RwLock::new(None);

thread_local! {
    /// Set while this thread reports a panic. A panic inside the listener aborts the process
    /// (Rust does not unwind a panic raised while processing another), so the listener must
    /// never fail; this only keeps such a panic from being reported to it again on the way.
    static REPORTING: Cell<bool> = const { Cell::new(false) };
}

/// Sends every later panic in the core to `listener`, replacing any earlier listener. The
/// previous panic hook (by default, the one that prints to standard error) still runs after it.
#[uniffi::export]
pub fn set_panic_listener(listener: Box<dyn PanicListener>) {
    INSTALL.call_once(|| {
        let previous = panic::take_hook();
        panic::set_hook(Box::new(move |info| {
            notify(info);
            previous(info);
        }));
    });
    let listener: Arc<dyn PanicListener> = Arc::from(listener);
    match LISTENER.write() {
        Ok(mut slot) => *slot = Some(listener),
        Err(poisoned) => *poisoned.into_inner() = Some(listener),
    }
}

/// Causes `kind` of crash. For the debug menu only; release builds never call it.
#[uniffi::export]
pub fn trigger_test_crash(kind: TestCrash) {
    const MESSAGE: &str = "test crash requested from the debug menu";
    match kind {
        TestCrash::PanicInCall => panic!("{MESSAGE}"),
        TestCrash::PanicInBackground => {
            let _ = std::thread::Builder::new()
                .name("test-crash".to_owned())
                .spawn(|| panic!("{MESSAGE}"));
        }
        TestCrash::Abort => std::process::abort(),
    }
}

fn notify(info: &PanicHookInfo<'_>) {
    if REPORTING.with(|reporting| reporting.replace(true)) {
        return;
    }
    // Clone the listener out so the lock is not held while the app runs.
    let listener = LISTENER
        .read()
        .ok()
        .and_then(|slot| slot.as_ref().map(Arc::clone));
    if let Some(listener) = listener {
        listener.on_panic(details(info));
    }
    REPORTING.with(|reporting| reporting.set(false));
}

fn details(info: &PanicHookInfo<'_>) -> PanicDetails {
    let payload = info.payload();
    let message = payload
        .downcast_ref::<&str>()
        .map(|text| (*text).to_owned())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "(panic without a message)".to_owned());
    let location = info
        .location()
        .map(|location| {
            format!(
                "{}:{}:{}",
                location.file(),
                location.line(),
                location.column()
            )
        })
        .unwrap_or_default();
    let thread = std::thread::current().name().unwrap_or_default().to_owned();
    PanicDetails {
        message,
        location,
        thread,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Default)]
    struct Recorder(Mutex<Vec<PanicDetails>>);

    impl PanicListener for Arc<Recorder> {
        fn on_panic(&self, details: PanicDetails) {
            self.0.lock().expect("recorder lock").push(details);
        }
    }

    // The hook and the listener are process-wide, so one test covers them in sequence rather
    // than racing other tests for the slot.
    #[test]
    fn panics_reach_the_listener_with_message_location_and_thread() {
        let recorder = Arc::new(Recorder::default());
        set_panic_listener(Box::new(Arc::clone(&recorder)));

        let result = std::thread::Builder::new()
            .name("worker".to_owned())
            .spawn(|| panic!("formatted {}", 42))
            .expect("spawn")
            .join();
        assert!(result.is_err());
        let result = std::thread::spawn(|| std::panic::panic_any(7_u8)).join();
        assert!(result.is_err());
        let result = std::thread::spawn(|| trigger_test_crash(TestCrash::PanicInCall)).join();
        assert!(result.is_err());

        let seen = recorder.0.lock().expect("recorder lock").clone();
        let ours: Vec<_> = seen
            .iter()
            .filter(|details| details.location.contains("panics.rs"))
            .collect();
        assert_eq!(ours.len(), 3, "{seen:?}");
        assert_eq!(ours[0].message, "formatted 42");
        assert_eq!(ours[0].thread, "worker");
        assert!(ours[0].location.contains(':'));
        assert_eq!(ours[1].message, "(panic without a message)");
        assert_eq!(ours[2].message, "test crash requested from the debug menu");
    }
}
