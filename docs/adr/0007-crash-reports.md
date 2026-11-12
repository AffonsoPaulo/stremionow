# 0007. Opt-in crash reports without a server

Date: 2026-11-11
Status: Accepted

## Context

Crashes on devices we do not own are invisible to us unless users tell us. The usual answer, a crash-reporting SDK (Crashlytics, Sentry), sends data to a third party by default and needs a server or an account; StremioNow has neither, and its users trust it with debrid keys and a Stremio account. Whatever we collect must:

- work with no backend of ours;
- leave the device only when the user taps a button for it, after seeing what goes;
- never carry secrets or personal data: debrid and addon URLs hold API keys in their path or query, the loopback relay's URLs hold a per-process secret, the Stremio session has an auth key, and paths can name the user;
- add no onboarding step, and work on phones, TVs (usually without a browser) and the desktop.

## Decision

**What is caught.** `CrashReports.install` runs first thing in the process (`Application.onCreate` on Android, `main` on the desktop, before the native libraries are set up; it reaches the core only afterwards, since a failed first load of the bindings cannot be retried) and records:

| Source | How | Report kind |
| --- | --- | --- |
| Uncaught JVM exceptions, any thread | `Thread.setDefaultUncaughtExceptionHandler`, chained to the previous handler, which still runs (Android's kills the process; on the desktop we print the trace as the JVM would) | `crash` on Android, `error` on the desktop, where the process can go on |
| Rust panics | A panic hook in `stremionow-ffi` (`set_panic_listener`) calls a UniFFI callback with the message, `file:line:column` and thread name, then the previous hook. UniFFI turns a panic inside an exported call into `InternalException` carrying only the message, and panics on the core's own threads never reach Kotlin, so the hook is the only place that sees the location of every panic | `core-panic`; merged into the `crash` when the `InternalException` it raised goes uncaught within 30 s |
| Native crashes and ANRs (Android 11+) | `ApplicationExitInfo` on the next launch, off the main thread: `REASON_CRASH_NATIVE` (signal, description) and `REASON_ANR` (the main thread's stack from the ANR trace). A watermark file makes each exit count once; the first launch only sets it | `native-crash`, merged with a core panic just before it (a panic that aborted); `anr` |

The system ending the app for memory, an update or a swipe is not a crash and is ignored.

**The report.** Plain English text, whatever the interface language, so developers can read it: a header (format version, kind, time to the minute in UTC, app version with "(debug)", OS and version, device class `phone`/`tablet`/`tv`/`fire-tv`/`desktop`, ABI, locale, thread, uptime, occurrences, a one-line summary), then the details (stack trace, panic or system description), then the app's last 50 warnings from an in-memory ring buffer fed by `logWarning`. No device model, account or identifier. The summary is built from class and method names only, never from messages, and filtered to `[A-Za-z0-9 _.$:()<>-]`; it becomes the issue title.

**Redaction**, by `Redactor`, before the report is written and again when it is read back (so a report from an older version gets today's rules):

- addresses (`scheme://…`, percent-encoded or JSON-escaped too) keep only scheme and host: credentials, path, query and fragment become `[redacted]`;
- magnet links, `Bearer`/`Basic` credentials, values of `key=value` pairs whose key names a secret (`authKey`, `apikey`, `token`, `password`, …);
- email addresses, UUIDs (TorBox keys), hex strings of 16+ digits (the relay secret and tokens, info hashes), runs of 20+ key-like characters that mix letters and digits or are long runs of capitals (debrid keys, base64 configs, JWTs), IMDb ids (they tell what the user watches), IPv4 addresses other than loopback;
- the user's home folder (literal, and the `C:\Users\x`, `/home/x`, `/Users/x` shapes) becomes `~`, and anything under it outside app and tool folders becomes `~/[path]`; so do paths on Android's shared storage.

Every pattern starts matching only at the start of a run of characters, so the cost is linear even for hostile text. Tests feed it nasty inputs, check that it is idempotent, and time megabytes of adversarial text.

**Storage.** `crash-reports/` in private storage that is not backed up (`noBackupFilesDir` on Android, the app data folder on the desktop). Each report is written to a temporary file, synced and renamed, so a crash or a full disk mid-write leaves nothing half-written; leftovers are deleted on the next launch. The same crash again (same kind and summary) replaces its report and counts occurrences, so a crash loop leaves one file. At most 5 reports are kept, each for at most 30 days. The user's choice is a small file next to them, so it is known even when the crash is in the core's database. Details are capped at 1,500 lines and 64 KB, with repeated frames (recursion) folded.

**Consent.** No onboarding step. Settings › Privacy › Crash reports offers *Ask me before sending* (default) and *Never*; *Never* writes nothing and deletes what is saved. On the next launch after a crash, an `SnDialog`-style dialog says "StremioNow closed unexpectedly" (or "ran into a problem" when the app kept running) with **Don't send** (initial focus, so a stray OK press sends nothing), **View report** and **Send report**. Back postpones the question to the next launch. Reports written during a session wait for the next launch. *Don't send* deletes every saved report; sending deletes the one sent. If the newest report shows a crash loop (closed the app, happened more than once, within 15 s of start), the dialog shows before the app, which would otherwise crash before the user could answer. Should the dialog itself close the app twice in a row in one version (counted until it has been on screen for 10 s), it stops asking until the next version, so a bug in it cannot lock the user out.

**Sending without a server.** *Send report* opens `https://github.com/AffonsoPaulo/stremionow/issues/new?template=crash.yml&title=…&version=…&platform=…&report=…` in the browser. Issue forms ignore `body`; each field is filled from the query parameter named after its id in `.github/ISSUE_TEMPLATE/crash.yml`. The user sees the issue, from their own GitHub account, before submitting it. Values are form-encoded byte by byte (only unreserved characters stay), so nothing in a report can add a parameter or end the query; only HTTPS links to that page are opened, with `CATEGORY_BROWSABLE` on Android. Links are kept within 8,000 characters: the log is cut first, then each exception keeps 12, 6, 3 and finally 1 frame, and as a last resort the details are cut; a shortened report says so in its last line, and the app then keeps it open with **Copy report** so the full text can be pasted. The report page also has **Copy report** and, on Android phones, **Share** (the system share sheet).

**TV.** TVs rarely have a browser, and typing a GitHub password with a remote is painful, while a phone has both a browser and the user's GitHub session. So on TV, *Send report* shows a QR code of the issue link (also the fallback anywhere no browser opens the link). The link is limited to 1,200 characters for it, a version 26 or so code at the lowest error correction, which a phone reads from across a room on a 300 dp square; the report itself is readable on screen and scrolls with the D-pad. The code comes from Nayuki's QR Code generator (`io.nayuki:qrcodegen`, MIT, one small class with no dependencies).

**Testing on devices.** Debug builds (debuggable APKs, `./gradlew run` on the desktop, which sets `-Dstremionow.debug=true`) have a hidden menu: select the version line at the bottom of the Profile page seven times. It crashes on the interface thread or a background thread, panics in a core call or on a core thread, aborts in native code, or (Android) freezes the interface for 20 s. The core always exports `trigger_test_crash`, since UniFFI bindings must match the library; release builds have no way to reach it.

## Consequences

- No data leaves a device without a tap, and what leaves is what the user saw; nothing is collected when the user picks *Never*.
- We only learn about crashes users choose to report, and a report needs a GitHub account. That is the price of having no server.
- Redaction is heuristic. It errs towards removing too much; what it cannot know (a title in an exception message, a file name inside the app's torrent cache) the user can still delete in the form, which says the issue is public.
- Native crashes before Android 11, and native crashes on the desktop, leave no report unless a core panic preceded them.
- Release builds strip Rust symbols, so core reports carry the panic location but no Rust backtrace.
- One new dependency, `io.nayuki:qrcodegen`, in the shared interface.
