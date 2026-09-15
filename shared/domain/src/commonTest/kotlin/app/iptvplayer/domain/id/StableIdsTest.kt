package app.iptvplayer.domain.id

import app.iptvplayer.domain.fixtures.IdFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StableIdsTest {
    @Test
    fun versionTagMatchesReferenceImplementation() {
        assertEquals(IdFixture.versionTag, StableIds.VERSION_TAG)
    }

    @Test
    fun derivedIdsMatchReferenceVectors() {
        assertTrue(IdFixture.ids.isNotEmpty())
        for (vector in IdFixture.ids) {
            val kind = DerivedIdKind.valueOf(vector.kind)
            assertEquals(vector.prefix, kind.prefix, "prefix for ${vector.kind}")
            assertEquals(vector.kindName, kind.kindName, "kind name for ${vector.kind}")
            assertEquals(vector.id, StableIds.derive(kind, vector.scope, vector.parts), "id for ${vector.kind} ${vector.parts}")
        }
    }

    @Test
    fun fingerprintsMatchReferenceVectors() {
        assertTrue(IdFixture.fingerprints.isNotEmpty())
        for (vector in IdFixture.fingerprints) assertEquals(vector.fingerprint, StableIds.fingerprint(vector.input), vector.input)
    }

    @Test
    fun derivedIdShapeIsPrefixUnderscoreAnd26Base32Characters() {
        for (kind in DerivedIdKind.entries) {
            val id = StableIds.derive(kind, "scope", listOf("a", "b"))
            val body = id.removePrefix(kind.prefix + "_")
            assertEquals(StableIds.BODY_LENGTH, body.length)
            assertTrue(body.all { it in 'a'..'z' || it in '2'..'7' }, id)
        }
    }

    @Test
    fun lengthPrefixingPreventsDelimiterAmbiguity() {
        assertNotEquals(
            StableIds.derive(DerivedIdKind.GROUP, "s", listOf("ab", "c")),
            StableIds.derive(DerivedIdKind.GROUP, "s", listOf("a", "bc")),
        )
        assertNotEquals(
            StableIds.derive(DerivedIdKind.CHANNEL, "s", listOf("")),
            StableIds.derive(DerivedIdKind.CHANNEL, "s", emptyList()),
        )
    }

    @Test
    fun kindAndScopeParticipateInTheHash() {
        val parts = listOf("xtream", "1001")
        assertNotEquals(
            StableIds.derive(DerivedIdKind.MOVIE, "p1", parts).substringAfter('_'),
            StableIds.derive(DerivedIdKind.SERIES, "p1", parts).substringAfter('_'),
        )
        assertNotEquals(StableIds.derive(DerivedIdKind.CHANNEL, "p1", parts), StableIds.derive(DerivedIdKind.CHANNEL, "p2", parts))
    }

    @Test
    fun derivationIsDeterministic() {
        repeat(3) {
            assertEquals(
                StableIds.derive(DerivedIdKind.PROGRAM, "e", listOf("x", "1")),
                StableIds.derive(DerivedIdKind.PROGRAM, "e", listOf("x", "1")),
            )
        }
    }

    @Test
    fun randomIdsAreUuidV4AndUnique() {
        val ids = List(1000) { StableIds.random() }
        assertEquals(ids.size, ids.toSet().size)
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(ids.all { uuid.matches(it) }, ids.first())
        assertNotEquals(ProviderId.random(), ProviderId.random())
    }
}
