package app.iptvplayer.domain.text

import app.iptvplayer.domain.fixtures.IdFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextNormalizationTest {
    @Test
    fun normKeyMatchesReferenceVectors() {
        assertTrue(IdFixture.text.isNotEmpty())
        for (vector in IdFixture.text) assertEquals(vector.normKey, TextNormalization.normKey(vector.input), "normKey(${vector.input})")
    }

    @Test
    fun matchNormalizeMatchesReferenceVectors() {
        for (vector in IdFixture.text) {
            assertEquals(vector.matchNormalize, TextNormalization.matchNormalize(vector.input), "matchNormalize(${vector.input})")
        }
    }

    @Test
    fun normKeyIsIdempotent() {
        for (vector in IdFixture.text) assertEquals(vector.normKey, TextNormalization.normKey(vector.normKey), vector.input)
    }

    @Test
    fun normKeyKeepsQualityTagsSoHdAndSdStayDistinct() {
        assertTrue(TextNormalization.normKey("Example HD") != TextNormalization.normKey("Example SD"))
        assertEquals(TextNormalization.matchNormalize("Example HD"), TextNormalization.matchNormalize("Example SD"))
    }

    @Test
    fun whitespaceSetIsExplicit() {
        for (c in listOf('\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001F', '\u0085', ' ', '\u00A0', '\u2003', '\u2028', '\u2029')) {
            assertTrue(TextNormalization.isNormWhitespace(c), "U+${c.code.toString(16)}")
        }
        for (c in listOf('a', '_', '\u200B', '\u0000')) assertTrue(!TextNormalization.isNormWhitespace(c), "U+${c.code.toString(16)}")
    }
}
