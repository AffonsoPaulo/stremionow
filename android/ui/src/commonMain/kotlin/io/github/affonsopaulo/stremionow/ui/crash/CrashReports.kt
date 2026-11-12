// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import io.github.affonsopaulo.stremionow.core.PanicDetails
import io.github.affonsopaulo.stremionow.core.PanicListener
import io.github.affonsopaulo.stremionow.core.TestCrash
import io.github.affonsopaulo.stremionow.core.setPanicListener
import io.github.affonsopaulo.stremionow.core.triggerTestCrash
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Opt-in crash reports without a server (see docs/adr/0007-crash-reports.md).
 *
 * [install] catches uncaught exceptions on every thread and panics in the Rust core, and, on
 * Android 11 and later, picks up native crashes and "not responding" exits on the next launch.
 * Each becomes a redacted report in the app's private storage. Nothing leaves the device: on
 * the next launch the interface asks, and only the user's tap opens a pre-filled GitHub issue
 * or the share sheet. With crash reports set to [Mode.Never], nothing is written at all.
 */
object CrashReports {
    /** The user's choice in Settings. */
    enum class Mode { Ask, Never }

    private const val MAX_PROMPT_FAILURES = 2

    @Volatile private var store: ReportStore? = null

    @Volatile private var recorder: CrashRecorder? = null

    @Volatile private var environment: CrashEnvironment? = null

    private val mutableMode = MutableStateFlow(Mode.Ask)
    internal val mode: StateFlow<Mode> = mutableMode.asStateFlow()

    private val mutablePending = MutableStateFlow<List<StoredReport>>(emptyList())

    /**
     * Reports saved before this launch, newest first. Reports written while the app runs wait
     * for the next launch, so the user is never interrupted mid-session.
     */
    internal val pending: StateFlow<List<StoredReport>> = mutablePending.asStateFlow()

    /**
     * Whether the interface may ask about reports this launch. It may not after the app closed
     * twice in a row, in this version, while the question was on screen: the question itself
     * would then be what crashes, and the app must stay usable.
     */
    @Volatile internal var promptAllowed = true
        private set

    private val promptCounted = AtomicBoolean(false)

    /** Whether this is a debug build, where the hidden test-crash menu exists. */
    internal val isDebugBuild: Boolean get() = environment?.debug == true

    /**
     * Starts catching crashes. Call it first thing when the process starts (Application.onCreate
     * on Android, `main` on the desktop). Later calls do nothing.
     *
     * With [listenToCore], it also loads the core to hear about its panics. Pass false where the
     * core's library is not reachable yet (the desktop sets up its path first) and call
     * [listenToCorePanics] once it is: a failed first load would break the bindings for good.
     */
    fun install(
        context: PlatformContext,
        isTv: Boolean,
        listenToCore: Boolean = true,
    ) {
        synchronized(this) {
            if (store != null) return
            val startedAt = System.currentTimeMillis()
            val reports = ReportStore(crashReportsDirectory(context))
            store = reports
            mutableMode.value = reports.readMode()
            val env = runCatching { crashEnvironment(context, isTv) }.getOrNull()
            environment = env
            if (env != null) recorder = CrashRecorder(reports, env, startedAt, uncaughtErrorsEndProcess)

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error -> onUncaught(thread, error, previous) }
            if (listenToCore) listenToCorePanics()

            // A few small files: cheap enough for the start, and the interface needs them to
            // decide whether to ask before anything else.
            mutablePending.value = runCatching { reports.load(env?.homeFolder) }.getOrDefault(emptyList())
            promptAllowed = reports.readPromptFailures(env?.app.orEmpty()) < MAX_PROMPT_FAILURES
        }
        // Asking the system about earlier exits is a call to another process and reads trace
        // files: keep it off the start-up path.
        thread(name = "crash-reports", isDaemon = true) {
            runCatching { collectProcessExits(context) }
        }
    }

    /** The question about a report is on screen; counted until [promptWorked]. */
    internal fun promptShown() {
        val reports = store ?: return
        if (!promptCounted.compareAndSet(false, true)) return
        val version = environment?.app.orEmpty()
        reports.writePromptFailures(reports.readPromptFailures(version) + 1, version)
    }

    /** The question stayed up long enough, or was answered: it does not crash the app. */
    internal fun promptWorked() {
        store?.writePromptFailures(0, environment?.app.orEmpty())
    }

    /** Hears about panics in the core from now on. Safe to call more than once. */
    fun listenToCorePanics() {
        // Loading the core can fail; a missing native library is itself a crash worth
        // reporting, and the uncaught-exception handler is already in place for it.
        runCatching { setPanicListener(Listener) }
    }

    /** Changes the user's choice. [Mode.Never] also deletes every saved report. */
    internal fun setMode(mode: Mode) {
        val reports = store ?: return
        mutableMode.value = mode
        reports.writeMode(mode)
        if (mode == Mode.Never) discardAll()
    }

    /** Deletes one report, once it was sent or dismissed. */
    internal fun delete(report: StoredReport) {
        store?.delete(report)
        mutablePending.value = mutablePending.value.filter { it.file != report.file }
    }

    /** Deletes every saved report. */
    internal fun discardAll() {
        store?.deleteAll()
        mutablePending.value = emptyList()
    }

    /** Crashes on purpose, for the debug menu. The message checks redaction on the device. */
    internal fun crashForTest(kind: TestCrashKind) {
        val message =
            "Test crash from the debug menu. Redaction check: https://example.com/secret/path?apikey=TESTKEY123, " +
                "someone@example.com, http://127.0.0.1:41234/0123456789abcdef0123456789abcdef/proxy/x"
        when (kind) {
            TestCrashKind.InterfaceThread -> throw IllegalStateException(message)
            TestCrashKind.BackgroundThread -> thread(name = "test-crash") { throw IllegalStateException(message) }
            TestCrashKind.CorePanicInCall -> triggerTestCrash(TestCrash.PANIC_IN_CALL)
            TestCrashKind.CorePanicInBackground -> triggerTestCrash(TestCrash.PANIC_IN_BACKGROUND)
            TestCrashKind.NativeAbort -> triggerTestCrash(TestCrash.ABORT)
            TestCrashKind.NotResponding -> freezeForTest()
        }
    }

    private fun onUncaught(
        thread: Thread,
        error: Throwable,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        try {
            if (mode.value != Mode.Never) recorder?.recordCrash(thread, error)
        } catch (_: Throwable) {
            // Never let reporting get in the way of the crash itself.
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // What the JVM does without a handler.
                System.err.print("Exception in thread \"${thread.name}\" ")
                error.printStackTrace()
            }
        }
    }

    /** Called from Rust, on the panicking thread: anything thrown here would abort the process. */
    private object Listener : PanicListener {
        override fun onPanic(details: PanicDetails) {
            try {
                if (mode.value != Mode.Never) recorder?.recordPanic(details)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Turns native crashes and "not responding" exits recorded by the system into reports. The
     * first launch with crash reports only notes the time, so exits from before are ignored.
     */
    private fun collectProcessExits(context: PlatformContext) {
        val reports = store ?: return
        val since = reports.readExitsSeen()
        if (since == null) {
            reports.writeExitsSeen(System.currentTimeMillis())
            return
        }
        val exits = processExitsSince(context, since)
        if (exits.isEmpty()) return
        reports.writeExitsSeen(maxOf(since, exits.maxOf { it.time }))
        if (mode.value == Mode.Never) return
        if (recorder?.recordExits(exits, pending.value) == true) {
            mutablePending.value = reports.load(environment?.homeFolder)
        }
    }
}

/** Crashes the debug menu can cause. */
internal enum class TestCrashKind {
    InterfaceThread,
    BackgroundThread,
    CorePanicInCall,
    CorePanicInBackground,
    NativeAbort,
    NotResponding,
}
