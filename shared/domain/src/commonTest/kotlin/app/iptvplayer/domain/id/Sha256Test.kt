package app.iptvplayer.domain.id

import app.iptvplayer.domain.fixtures.IdFixture
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun sha(text: String): String = hex(Sha256.digest(text.encodeToByteArray()))

    @Test
    fun fips180KnownAnswers() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun oneMillionA() {
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(
                Sha256.digest(
                    ByteArray(1_000_000) {
                        'a'.code.toByte()
                    },
                ),
            ),
        )
    }

    @Test
    fun paddingBoundaries() {
        // Messages of 55, 56 and 64 bytes straddle the single/double padding block boundary.
        assertEquals(sha("a".repeat(55)).length, 64)
        val lengths = listOf(0, 1, 55, 56, 57, 63, 64, 65, 119, 120)
        assertEquals(lengths.size, lengths.map { sha("x".repeat(it)) }.toSet().size)
    }

    @Test
    fun referenceImplementationVectors() {
        for (vector in IdFixture.sha256) assertEquals(vector.hex, sha(vector.input), "sha256 of ${vector.input.take(20)}")
    }

    @Test
    fun base32MatchesRfc4648LowercaseWithoutPadding() {
        val cases = mapOf(
            "" to "",
            "f" to "my",
            "fo" to "mzxq",
            "foo" to "mzxw6",
            "foob" to "mzxw6yq",
            "fooba" to "mzxw6ytb",
            "foobar" to "mzxw6ytboi",
        )
        for ((input, expected) in cases) assertEquals(expected, Base32Lower.encode(input.encodeToByteArray()), input)
    }
}
