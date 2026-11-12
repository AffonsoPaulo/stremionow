// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data.secrets

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * macOS: each secret is a generic password item in the user's default (login) keychain, with
 * service [service] and the secret's name as the account. The item's access list trusts the
 * program that created it; when another one asks (an updated runtime, for example), macOS asks
 * the user, and a refusal leaves the app signed out for that session.
 *
 * It goes through Security.framework (`SecItem*`), never the `security` command, which would put
 * the secret in a process argument list.
 */
internal class KeychainSecrets(
    private val service: String,
) : SecretBackend {
    override val description = "Keychain"

    private val api: Api by lazy {
        try {
            Api()
        } catch (failure: LinkageError) {
            throw SecretStoreUnavailableException("Security.framework could not be loaded", failure)
        }
    }

    override fun save(
        name: String,
        value: String,
    ) = scope {
        val data = data(value)
        val query = query(name)
        var status = api.security.SecItemUpdate(query, dictionary(api.kSecValueData to data))
        if (status == ERR_ITEM_NOT_FOUND) {
            val attributes =
                dictionary(
                    api.kSecClass to api.kSecClassGenericPassword,
                    api.kSecAttrService to string(service),
                    api.kSecAttrAccount to string(name),
                    api.kSecAttrLabel to string("$service ($name)"),
                    api.kSecValueData to data,
                )
            status = api.security.SecItemAdd(attributes, null)
            // Added by someone else since the update: update again.
            if (status == ERR_DUPLICATE_ITEM) {
                status = api.security.SecItemUpdate(query, dictionary(api.kSecValueData to data))
            }
        }
        check(status, "save")
    }

    override fun load(name: String): String? =
        scope {
            val query =
                dictionary(
                    api.kSecClass to api.kSecClassGenericPassword,
                    api.kSecAttrService to string(service),
                    api.kSecAttrAccount to string(name),
                    api.kSecReturnData to api.kCFBooleanTrue,
                    api.kSecMatchLimit to api.kSecMatchLimitOne,
                )
            val result = PointerByReference()
            val status = api.security.SecItemCopyMatching(query, result)
            if (status == ERR_ITEM_NOT_FOUND) return@scope null
            check(status, "read")
            val data = result.value ?: return@scope null
            own(data)
            val bytes = api.cf.CFDataGetBytePtr(data).getByteArray(0, api.cf.CFDataGetLength(data).toInt())
            try {
                String(bytes, Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        }

    override fun clear(name: String) =
        scope {
            val status = api.security.SecItemDelete(query(name))
            if (status != ERR_ITEM_NOT_FOUND) check(status, "clear")
        }

    private fun CfScope.query(name: String): Pointer =
        dictionary(
            api.kSecClass to api.kSecClassGenericPassword,
            api.kSecAttrService to string(service),
            api.kSecAttrAccount to string(name),
        )

    private fun check(
        status: Int,
        what: String,
    ) {
        if (status == ERR_SUCCESS) return
        val reason =
            when (status) {
                ERR_USER_CANCELED, ERR_AUTH_FAILED -> "access denied"
                ERR_INTERACTION_NOT_ALLOWED -> "keychain locked and no prompt possible"
                ERR_NO_SUCH_KEYCHAIN, ERR_NOT_AVAILABLE -> "no keychain"
                else -> "error"
            }
        throw SecretStoreUnavailableException("Keychain could not $what: $reason (OSStatus $status)")
    }

    /** Core Foundation objects made during one operation, released when it ends. */
    private inner class CfScope {
        private val owned = mutableListOf<Pointer>()

        fun own(reference: Pointer): Pointer = reference.also(owned::add)

        fun string(value: String): Pointer {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val reference =
                api.cf.CFStringCreateWithBytes(null, bytes, bytes.size.toLong(), CF_STRING_ENCODING_UTF8, 0)
            return own(checkNotNull(reference) { "CFStringCreateWithBytes failed" })
        }

        /** The secret as CFData; the temporary native copy is wiped once Core Foundation has its own. */
        fun data(value: String): Pointer {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val memory = Memory(bytes.size.coerceAtLeast(1).toLong())
            try {
                memory.write(0, bytes, 0, bytes.size)
                val reference = api.cf.CFDataCreate(null, memory, bytes.size.toLong())
                return own(checkNotNull(reference) { "CFDataCreate failed" })
            } finally {
                bytes.fill(0)
                memory.wipe()
            }
        }

        fun dictionary(vararg entries: Pair<Pointer, Pointer>): Pointer {
            val keys = Memory(Native.POINTER_SIZE.toLong() * entries.size)
            val values = Memory(Native.POINTER_SIZE.toLong() * entries.size)
            entries.forEachIndexed { index, (key, value) ->
                keys.setPointer(index.toLong() * Native.POINTER_SIZE, key)
                values.setPointer(index.toLong() * Native.POINTER_SIZE, value)
            }
            val reference =
                api.cf.CFDictionaryCreate(
                    null,
                    keys,
                    values,
                    entries.size.toLong(),
                    api.keyCallBacks,
                    api.valueCallBacks,
                )
            return own(checkNotNull(reference) { "CFDictionaryCreate failed" })
        }

        fun releaseAll() {
            owned.asReversed().forEach(api.cf::CFRelease)
            owned.clear()
        }
    }

    private inline fun <T> scope(block: CfScope.() -> T): T {
        val scope = CfScope()
        try {
            return scope.block()
        } finally {
            scope.releaseAll()
        }
    }

    /** Security.framework and Core Foundation, loaded by absolute path (never searched for). */
    private class Api {
        val cf: CoreFoundation = Native.load(CORE_FOUNDATION, CoreFoundation::class.java)
        val security: Security = Native.load(SECURITY, Security::class.java)
        private val cfLibrary = NativeLibrary.getInstance(CORE_FOUNDATION)
        private val securityLibrary = NativeLibrary.getInstance(SECURITY)

        val keyCallBacks: Pointer = cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
        val valueCallBacks: Pointer = cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")
        val kCFBooleanTrue: Pointer = cfLibrary.constant("kCFBooleanTrue")

        val kSecClass = securityLibrary.constant("kSecClass")
        val kSecClassGenericPassword = securityLibrary.constant("kSecClassGenericPassword")
        val kSecAttrService = securityLibrary.constant("kSecAttrService")
        val kSecAttrAccount = securityLibrary.constant("kSecAttrAccount")
        val kSecAttrLabel = securityLibrary.constant("kSecAttrLabel")
        val kSecValueData = securityLibrary.constant("kSecValueData")
        val kSecReturnData = securityLibrary.constant("kSecReturnData")
        val kSecMatchLimit = securityLibrary.constant("kSecMatchLimit")
        val kSecMatchLimitOne = securityLibrary.constant("kSecMatchLimitOne")

        /** A framework's exported `const CFTypeRef` variable. */
        private fun NativeLibrary.constant(name: String): Pointer =
            checkNotNull(getGlobalVariableAddress(name).getPointer(0)) { "$name is null" }
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface CoreFoundation : Library {
        fun CFStringCreateWithBytes(
            allocator: Pointer?,
            bytes: ByteArray,
            length: Long,
            encoding: Int,
            isExternalRepresentation: Byte,
        ): Pointer?

        fun CFDataCreate(
            allocator: Pointer?,
            bytes: Pointer,
            length: Long,
        ): Pointer?

        fun CFDataGetLength(data: Pointer): Long

        fun CFDataGetBytePtr(data: Pointer): Pointer

        fun CFDictionaryCreate(
            allocator: Pointer?,
            keys: Pointer,
            values: Pointer,
            count: Long,
            keyCallBacks: Pointer,
            valueCallBacks: Pointer,
        ): Pointer?

        fun CFRelease(reference: Pointer)
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Security : Library {
        fun SecItemAdd(
            attributes: Pointer,
            result: PointerByReference?,
        ): Int

        fun SecItemCopyMatching(
            query: Pointer,
            result: PointerByReference,
        ): Int

        fun SecItemUpdate(
            query: Pointer,
            attributesToUpdate: Pointer,
        ): Int

        fun SecItemDelete(query: Pointer): Int
    }

    companion object {
        /** Keychain service of the app's items. */
        const val SERVICE = "StremioNow"

        private const val CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        private const val SECURITY = "/System/Library/Frameworks/Security.framework/Security"
        private const val CF_STRING_ENCODING_UTF8 = 0x08000100

        private const val ERR_SUCCESS = 0
        private const val ERR_USER_CANCELED = -128
        private const val ERR_NOT_AVAILABLE = -25291
        private const val ERR_AUTH_FAILED = -25293
        private const val ERR_NO_SUCH_KEYCHAIN = -25294
        private const val ERR_DUPLICATE_ITEM = -25299
        private const val ERR_ITEM_NOT_FOUND = -25300
        private const val ERR_INTERACTION_NOT_ALLOWED = -25308
    }
}
