package app.iptvplayer.platform.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.SensitiveUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] on the Android Keystore (docs/SECURITY.md §3.3, ADR-0015): a non-exportable AES-256-GCM key encrypts one
 * file per credential reference with a fresh IV per write. Files live in `noBackupFilesDir`, so they are never part of
 * cloud backups or device transfers; file names are hashes, not references. A key that became unusable (for example after
 * a factory reset of the keystore) makes entries unreadable: they are deleted and the user re-enters credentials.
 */
class KeystoreSecretStore(context: Context, private val keyAlias: String = "iptv-secrets-v1") : SecretStore {
    private val directory = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }
    private val mutex = Mutex()

    override suspend fun put(ref: CredentialRef, bundle: SecretBundle) = io {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(encode(bundle))
        val target = file(ref)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeBytes(cipher.iv + ciphertext)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("could not store secret")
        }
    }

    override suspend fun get(ref: CredentialRef): SecretBundle? = io {
        val file = file(ref)
        if (!file.isFile) return@io null
        try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            }
            decode(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (_: GeneralSecurityException) {
            file.delete()
            null
        }
    }

    override suspend fun delete(ref: CredentialRef) = io {
        file(ref).delete()
        Unit
    }

    override suspend fun exists(ref: CredentialRef): Boolean = io { file(ref).isFile }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun file(ref: CredentialRef): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(ref.value.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".bin")
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bundle: SecretBundle): ByteArray {
        val fields = buildMap {
            bundle.username?.let { put("username", JsonPrimitive(it.unsafeValue())) }
            bundle.password?.let { put("password", JsonPrimitive(it.unsafeValue())) }
            bundle.secretUrl?.let { put("secretUrl", JsonPrimitive(it.unsafeRawValue())) }
            if (bundle.secretHeaders.isNotEmpty()) {
                put(
                    "headers",
                    JsonObject(bundle.secretHeaders.mapValues { JsonPrimitive(it.value.unsafeValue()) }),
                )
            }
        }
        return JsonObject(fields).toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): SecretBundle {
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return SecretBundle(
            username = json["username"]?.jsonPrimitive?.content?.let { Secret(it) },
            password = json["password"]?.jsonPrimitive?.content?.let { Secret(it) },
            secretUrl = json["secretUrl"]?.jsonPrimitive?.content?.let { SensitiveUrl.of(it) },
            secretHeaders = json["headers"]?.jsonObject?.mapValues { Secret(it.value.jsonPrimitive.content) }.orEmpty(),
        )
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
