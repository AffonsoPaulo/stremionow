// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data.secrets

import com.sun.jna.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Round trips through the real secret store of the machine running the tests, under a test
 * service or schema so the app's own items are never touched.
 *
 * Where the store is missing (no Secret Service on a Linux box without a keyring, for example),
 * the test is skipped; CI sets `STREMIONOW_REQUIRE_SECRET_STORE=true` so that it fails instead,
 * proving the store was exercised.
 */
class SecretBackendTest {
    @get:Rule val temporary = TemporaryFolder()

    private val required = System.getenv("STREMIONOW_REQUIRE_SECRET_STORE") == "true"

    @Test
    fun dpapiRoundTrip() {
        assumeTrue(Platform.isWindows())
        roundTrip(DpapiSecrets(temporary.newFolder("secrets")))
    }

    @Test
    fun keychainRoundTrip() {
        assumeTrue(Platform.isMac())
        roundTrip(KeychainSecrets("StremioNow tests"))
    }

    @Test
    fun secretServiceRoundTrip() {
        assumeTrue(Platform.isLinux())
        val schema = "io.github.affonsopaulo.stremionow.Test"
        try {
            roundTrip(LibsecretSecrets(schema))
        } catch (failure: SecretStoreUnavailableException) {
            if (!required) throw failure
            // A keyring without a default collection (a bare CI session): the in-memory session
            // collection still proves the calls, the schema and the D-Bus round trip.
            System.err.println("Default collection unusable (${failure.message}); trying the session collection")
            roundTrip(LibsecretSecrets(schema, collection = "session"))
        }
    }

    @Test
    fun failingStoreKeepsNothingAndNeverThrows() {
        val broken =
            object : SecretBackend {
                override val description = "Broken"

                override fun save(
                    name: String,
                    value: String,
                ): Unit = throw SecretStoreUnavailableException("locked")

                override fun load(name: String): String = throw UnsatisfiedLinkError("libsecret-1.so.0")

                override fun clear(name: String): Unit = throw IllegalStateException("denied")
            }
        val store = SessionFallback(broken)
        store.save("key", "value")
        assertNull(store.load("key"))
        store.clear("key")
    }

    @Test
    fun noStoreKeepsNothing() {
        val store = SessionFallback(null)
        store.save("key", "value")
        assertNull(store.load("key"))
        store.clear("key")
    }

    /**
     * Saves, reads, replaces and clears a secret. A store that cannot be used skips the test,
     * unless it is [required]: it may answer a lookup yet refuse to save (a locked keyring with
     * no one to unlock it), so that holds for every step, not only the first.
     */
    private fun roundTrip(backend: SecretBackend) {
        val name = "test_" + UUID.randomUUID().toString().replace("-", "")
        try {
            roundTrip(backend, name)
        } catch (failure: SecretStoreUnavailableException) {
            if (required) throw failure
            assumeNoException("${backend.description} is not available here: ${failure.message}", failure)
        }
    }

    private fun roundTrip(
        backend: SecretBackend,
        name: String,
    ) {
        try {
            assertNull(backend.load(name))
            backend.save(name, "first value ✓ ação")
            assertEquals("first value ✓ ação", backend.load(name))
            backend.save(name, "second value")
            assertEquals("second value", backend.load(name))
            backend.clear(name)
            assertNull(backend.load(name))
            // Clearing what is not there is not an error.
            backend.clear(name)
        } finally {
            runCatching { backend.clear(name) }
        }
    }
}
