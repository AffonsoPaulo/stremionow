// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data.secrets

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * Linux and other freedesktop systems: secrets go to the Secret Service (GNOME Keyring, KWallet,
 * KeePassXC and others) through libsecret, in the default collection (the login keyring). Each
 * one is an item of the schema [schemaName] whose `name` attribute is the secret's name.
 *
 * Without libsecret, a D-Bus session or a Secret Service, every call throws
 * [SecretStoreUnavailableException]. A locked keyring may make the service ask the user to unlock
 * it; a refusal behaves like a missing value.
 */
internal class LibsecretSecrets(
    private val schemaName: String,
    /** Collection to store in; null is the default one (the login keyring). */
    private val collection: String? = null,
) : SecretBackend {
    override val description = "Secret Service"

    private val api: Api by lazy {
        try {
            Api()
        } catch (failure: LinkageError) {
            throw SecretStoreUnavailableException("libsecret-1 is not installed", failure)
        }
    }

    /** The schema, created once and kept for the life of the process. */
    private val schema: Pointer by lazy {
        api.withTable(stringValues = false, mapOf(ATTRIBUTE to null)) { types ->
            // Every attribute is SECRET_SCHEMA_ATTRIBUTE_STRING (0), stored as a NULL value.
            checkNotNull(api.secret.secret_schema_newv(schemaName, SECRET_SCHEMA_NONE, types)) {
                "secret_schema_newv failed"
            }
        }
    }

    override fun save(
        name: String,
        value: String,
    ) {
        val password = nativeUtf8(value)
        try {
            val stored =
                attributes(name) { attributes ->
                    call("save") { error ->
                        api.secret.secret_password_storev_sync(
                            schema,
                            attributes,
                            collection,
                            "StremioNow ($name)",
                            password,
                            null,
                            error,
                        )
                    }
                }
            if (!stored) throw SecretStoreUnavailableException("Secret Service refused to save")
        } finally {
            password.wipe()
        }
    }

    override fun load(name: String): String? {
        val password =
            attributes(name) { attributes ->
                call("read") { error -> api.secret.secret_password_lookupv_sync(schema, attributes, null, error) }
            } ?: return null
        return try {
            password.getString(0, "UTF-8")
        } finally {
            // Wipes the memory before freeing it.
            api.secret.secret_password_free(password)
        }
    }

    override fun clear(name: String) {
        attributes(name) { attributes ->
            call("clear") { error -> api.secret.secret_password_clearv_sync(schema, attributes, null, error) }
        }
    }

    private fun <T> attributes(
        name: String,
        block: (Pointer) -> T,
    ): T = api.withTable(stringValues = true, mapOf(ATTRIBUTE to name), block)

    /** Runs a libsecret call that reports failures through a `GError **`. */
    private fun <T> call(
        what: String,
        block: (PointerByReference) -> T,
    ): T {
        val error = PointerByReference()
        val result = block(error)
        val failure = error.value ?: return result
        val message = failure.getPointer(GERROR_MESSAGE_OFFSET)?.getString(0, "UTF-8") ?: "unknown error"
        api.glib.g_error_free(failure)
        throw SecretStoreUnavailableException("Secret Service could not $what: $message")
    }

    /** libsecret and GLib, loaded by their sonames through the system's library search. */
    private class Api {
        val glib: GLib = Native.load(GLIB, GLib::class.java)
        val secret: Secret = Native.load(LIBSECRET, Secret::class.java)
        private val glibLibrary = NativeLibrary.getInstance(GLIB)
        private val stringHash: Pointer = glibLibrary.getFunction("g_str_hash")
        private val stringEqual: Pointer = glibLibrary.getFunction("g_str_equal")

        /**
         * Runs [block] with a `GHashTable` of [entries]: strings to strings when [stringValues],
         * else strings to NULL. The table and its strings live until [block] returns.
         */
        fun <T> withTable(
            stringValues: Boolean,
            entries: Map<String, String?>,
            block: (Pointer) -> T,
        ): T {
            val strings = mutableListOf<Memory>()
            val table = checkNotNull(glib.g_hash_table_new(stringHash, stringEqual)) { "g_hash_table_new failed" }
            try {
                for ((key, value) in entries) {
                    val nativeKey = nativeUtf8(key).also(strings::add)
                    val nativeValue = if (stringValues && value != null) nativeUtf8(value).also(strings::add) else null
                    glib.g_hash_table_insert(table, nativeKey, nativeValue)
                }
                return block(table)
            } finally {
                glib.g_hash_table_unref(table)
                strings.forEach(Memory::close)
            }
        }
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface GLib : Library {
        fun g_hash_table_new(
            hash: Pointer,
            equal: Pointer,
        ): Pointer?

        fun g_hash_table_insert(
            table: Pointer,
            key: Pointer,
            value: Pointer?,
        ): Boolean

        fun g_hash_table_unref(table: Pointer)

        fun g_error_free(error: Pointer)
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Secret : Library {
        fun secret_schema_newv(
            name: String,
            flags: Int,
            attributeNamesAndTypes: Pointer,
        ): Pointer?

        fun secret_password_storev_sync(
            schema: Pointer,
            attributes: Pointer,
            collection: String?,
            label: String,
            password: Pointer,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean

        fun secret_password_lookupv_sync(
            schema: Pointer,
            attributes: Pointer,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Pointer?

        fun secret_password_clearv_sync(
            schema: Pointer,
            attributes: Pointer,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean

        fun secret_password_free(password: Pointer)
    }

    companion object {
        /** Schema of the app's items. */
        const val SCHEMA = "io.github.affonsopaulo.stremionow.Secret"

        private const val ATTRIBUTE = "name"
        private const val GLIB = "libglib-2.0.so.0"
        private const val LIBSECRET = "libsecret-1.so.0"
        private const val SECRET_SCHEMA_NONE = 0

        /** `GError` is `{ GQuark domain; gint code; gchar *message; }`. */
        private const val GERROR_MESSAGE_OFFSET = 8L
    }
}
