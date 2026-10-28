package app.iptvplayer.tv.companion

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A phone that has paired with the companion: a name the viewer can recognise, and when. */
data class PairedPhone(val id: String, val name: String, val pairedAtMs: Long)

/**
 * Pairing for the phone companion (ADR-0050). The TV shows a one-time code that lasts five minutes; a phone that sends
 * it gets a long random token, which it sends with every request afterwards. Only a hash of each token is kept, so the
 * preferences file never holds anything that would let another device in.
 */
class CompanionPairing(private val preferences: SharedPreferences, private val nowMs: () -> Long) {
    private val random = SecureRandom()
    private var code: String? = null
    private var codeExpiresAtMs = 0L
    private var wrongCodes = 0
    private val mutablePhones = MutableStateFlow(load())

    val phones: StateFlow<List<PairedPhone>> = mutablePhones

    /** The code to show now; a new one once the last has expired or been used. */
    @Synchronized
    fun code(): String {
        val current = code
        if (current != null && nowMs() < codeExpiresAtMs) return current
        return newCode()
    }

    /** Trades a valid code for a token, or null. The code works once; too many wrong ones replace it. */
    @Synchronized
    fun pair(offered: String, name: String): String? {
        val current = code
        if (current == null || nowMs() >= codeExpiresAtMs || !sameCode(offered, current)) {
            if (++wrongCodes >= MAX_WRONG_CODES) newCode()
            return null
        }
        newCode()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val phone = PairedPhone(hash(token), name.take(MAX_NAME).ifBlank { DEFAULT_NAME }, nowMs())
        save(mutablePhones.value + phone)
        return token
    }

    fun allows(token: String?): Boolean = token != null && mutablePhones.value.any { it.id == hash(token) }

    @Synchronized
    fun forget(id: String) {
        save(mutablePhones.value.filterNot { it.id == id })
    }

    private fun newCode(): String {
        wrongCodes = 0
        codeExpiresAtMs = nowMs() + CODE_LIFETIME_MS
        return String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }).also { code = it }
    }

    private fun sameCode(offered: String, current: String): Boolean =
        MessageDigest.isEqual(offered.uppercase().filter { it.isLetterOrDigit() }.toByteArray(), current.toByteArray())

    private fun save(phones: List<PairedPhone>) {
        preferences.edit { putStringSet(KEY_PHONES, phones.map { "${it.id}|${it.pairedAtMs}|${it.name}" }.toSet()) }
        mutablePhones.value = phones
    }

    private fun load(): List<PairedPhone> = preferences.getStringSet(KEY_PHONES, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split('|', limit = 3)
        if (parts.size == 3) parts[1].toLongOrNull()?.let { PairedPhone(parts[0], parts[2], it) } else null
    }.sortedBy { it.pairedAtMs }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_PHONES = "companion_phones"

        // No 0/O or 1/I: the code may be typed from the screen.
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        const val CODE_LIFETIME_MS = 5 * 60_000L
        const val MAX_WRONG_CODES = 10
        const val TOKEN_BYTES = 32
        const val MAX_NAME = 40
        const val DEFAULT_NAME = "Phone"
    }
}
