package app.iptvplayer.platform.apple

import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.SensitiveUrl
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * [SecretStore] on the Apple Keychain (docs/SECURITY.md §3, ADR-0015): one generic-password item per credential
 * reference, readable after the first unlock and **only on this device** — never synced to iCloud Keychain and never
 * carried to another device by a backup. The item's account is a hash of the reference, as Android's file names are, and
 * the value is the same small JSON document Android encrypts. Nothing here is logged.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class KeychainSecretStore(private val service: String = "app.iptvplayer.secrets") : SecretStore {
    private val mutex = Mutex()

    override suspend fun put(ref: CredentialRef, bundle: SecretBundle): Unit = io {
        deleteItem(ref)
        val status = withQuery(ref) { query ->
            add(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val data = retained(encode(bundle).toNSData())
            try {
                CFDictionaryAddValue(query, kSecValueData, data)
                SecItemAdd(query, null)
            } finally {
                CFRelease(data)
            }
        }
        check(status == errSecSuccess) { "Keychain refused the secret (status $status)" }
    }

    override suspend fun get(ref: CredentialRef): SecretBundle? = io {
        withQuery(ref) { query ->
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                when (val status = SecItemCopyMatching(query, result.ptr)) {
                    errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.let(::decode)
                    errSecItemNotFound -> null
                    else -> error("Keychain read failed (status $status)")
                }
            }
        }
    }

    override suspend fun delete(ref: CredentialRef): Unit = io { deleteItem(ref) }

    override suspend fun exists(ref: CredentialRef): Boolean = get(ref) != null

    private fun deleteItem(ref: CredentialRef) {
        val status = withQuery(ref) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed (status $status)" }
    }

    /** A query naming this store's item for [ref]; everything retained for it is released afterwards. */
    private fun <T> withQuery(ref: CredentialRef, block: (CFMutableDictionaryRef) -> T): T {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        val serviceValue = retained(NSString.create(string = service))
        val accountValue = retained(NSString.create(string = account(ref)))
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceValue)
            CFDictionaryAddValue(query, kSecAttrAccount, accountValue)
            return block(query)
        } finally {
            CFRelease(serviceValue)
            CFRelease(accountValue)
            CFRelease(query)
        }
    }

    private fun add(query: CFMutableDictionaryRef, key: CFTypeRef?, value: CFTypeRef?) {
        CFDictionaryAddValue(query, key, value)
    }

    private fun retained(value: Any): CFTypeRef = CFBridgingRetain(value)!!

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun account(ref: CredentialRef): String {
        val input = ref.value.encodeToByteArray()
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        input.usePinned { source -> digest.usePinned { target -> CC_SHA256(source.addressOf(0), input.size.toUInt(), target.addressOf(0)) } }
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    private fun encode(bundle: SecretBundle): ByteArray {
        val fields = buildMap {
            bundle.username?.let { put("username", JsonPrimitive(it.unsafeValue())) }
            bundle.password?.let { put("password", JsonPrimitive(it.unsafeValue())) }
            bundle.secretUrl?.let { put("secretUrl", JsonPrimitive(it.unsafeRawValue())) }
            if (bundle.secretHeaders.isNotEmpty()) {
                put("headers", JsonObject(bundle.secretHeaders.mapValues { JsonPrimitive(it.value.unsafeValue()) }))
            }
        }
        return JsonObject(fields).toString().encodeToByteArray()
    }

    private fun decode(bytes: ByteArray): SecretBundle {
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        return SecretBundle(
            username = json["username"]?.jsonPrimitive?.content?.let { Secret(it) },
            password = json["password"]?.jsonPrimitive?.content?.let { Secret(it) },
            secretUrl = json["secretUrl"]?.jsonPrimitive?.content?.let { SensitiveUrl.of(it) },
            secretHeaders = json["headers"]?.jsonObject?.mapValues { Secret(it.value.jsonPrimitive.content) }.orEmpty(),
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
