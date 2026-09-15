package app.iptvplayer.domain

import app.iptvplayer.domain.id.Sha256
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Cross-checks the pure-Kotlin SHA-256 against the JDK implementation on many random inputs. */
class JvmCrossCheckTest {
    @Test
    fun sha256MatchesJdkMessageDigest() {
        val random = Random(20260914)
        for (length in (0..300) + listOf(1_000, 4_096, 65_537)) {
            val input = random.nextBytes(length)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(input), Sha256.digest(input), "length $length")
        }
    }
}
