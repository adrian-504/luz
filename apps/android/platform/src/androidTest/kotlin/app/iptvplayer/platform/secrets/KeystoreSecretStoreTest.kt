package app.iptvplayer.platform.secrets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.SensitiveUrl
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreSecretStore(context, keyAlias = "iptv-secrets-test")
    private val password = "CANARY-PW-7f3a9c-DO-NOT-LOG"

    @Test
    fun storesEncryptedAndReadsBack() = runBlocking {
        val ref = CredentialRef("ref-${System.nanoTime()}")
        store.put(
            ref,
            SecretBundle(
                Secret("canary-user"),
                Secret(password),
                SensitiveUrl.of("https://lists.example.com/p.m3u?token=$password"),
                mapOf("Cookie" to Secret("session=$password")),
            ),
        )
        assertTrue(store.exists(ref))
        val read = store.get(ref)!!
        assertEquals("canary-user", read.username?.unsafeValue())
        assertEquals(password, read.password?.unsafeValue())
        assertEquals("session=$password", read.secretHeaders["Cookie"]?.unsafeValue())

        val files = File(context.noBackupFilesDir, "secrets").listFiles().orEmpty()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(password in text || "canary-user" in text, "plaintext secret on disk")
            assertFalse(ref.value in file.name, "file names must not reveal references")
        }

        store.put(ref, SecretBundle(Secret("other"), Secret("second")))
        assertEquals("second", store.get(ref)!!.password?.unsafeValue(), "a put replaces the entry")
        store.delete(ref)
        assertFalse(store.exists(ref))
        assertNull(store.get(ref))
    }

    @Test
    fun corruptedEntriesAreDroppedInsteadOfCrashing() = runBlocking {
        val ref = CredentialRef("ref-corrupt-${System.nanoTime()}")
        store.put(ref, SecretBundle(Secret("u"), Secret("p")))
        File(context.noBackupFilesDir, "secrets").listFiles().orEmpty().forEach { file ->
            val bytes = file.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
            file.writeBytes(bytes)
        }
        assertNull(store.get(ref), "tampered ciphertext fails authentication")
        assertFalse(store.exists(ref), "and the entry is removed so the user can re-enter credentials")
    }
}
