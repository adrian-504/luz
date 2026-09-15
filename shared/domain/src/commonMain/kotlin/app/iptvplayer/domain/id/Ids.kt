package app.iptvplayer.domain.id

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/** Kinds of deterministic, content-derived IDs (docs/DOMAIN_MODEL.md §4, ADR-0017). */
public enum class DerivedIdKind(public val prefix: String, public val kindName: String) {
    CHANNEL("ch", "channel"),
    GROUP("grp", "group"),
    MOVIE("mov", "movie"),
    SERIES("ser", "series"),
    SEASON("sea", "season"),
    EPISODE("ep", "episode"),
    MEDIA_SOURCE("ms", "media_source"),
    PROGRAM("prog", "program"),
    ARTWORK("art", "artwork"),
}

public object StableIds {
    /** Part of every hash. Changing the derivation requires a new tag, an ADR and a data migration. */
    public const val VERSION_TAG: String = "iptv.id.v1"

    /** Length of the base32 body: 16 digest bytes = 128 bits → 26 characters. */
    public const val BODY_LENGTH: Int = 26

    /**
     * `prefix + "_" + base32lower(SHA-256(lp(VERSION_TAG) ‖ lp(kind) ‖ lp(scopeId) ‖ lp(part)…)[0..16))`
     * where `lp(s)` is the UTF-8 byte length as big-endian uint32 followed by the bytes.
     */
    public fun derive(kind: DerivedIdKind, scopeId: String, keyParts: List<String>): String =
        hash(kind.prefix, kind.kindName, scopeId, keyParts)

    private fun hash(prefix: String, kindName: String, scopeId: String, keyParts: List<String>): String {
        val fields = ArrayList<ByteArray>(keyParts.size + 3)
        fields.add(VERSION_TAG.encodeToByteArray())
        fields.add(kindName.encodeToByteArray())
        fields.add(scopeId.encodeToByteArray())
        keyParts.mapTo(fields) { it.encodeToByteArray() }

        val payload = ByteArray(fields.sumOf { it.size + 4 })
        var offset = 0
        for (field in fields) {
            val size = field.size
            payload[offset] = (size ushr 24).toByte()
            payload[offset + 1] = (size ushr 16).toByte()
            payload[offset + 2] = (size ushr 8).toByte()
            payload[offset + 3] = size.toByte()
            field.copyInto(payload, offset + 4)
            offset += size + 4
        }
        return prefix + "_" + Base32Lower.encode(Sha256.digest(payload).copyOf(16))
    }

    /**
     * Stable, non-reversible fingerprint of a value (e.g. a credential-free URL template) for identity hints, so
     * opaque provider tokens in URLs are not stored in hint columns. Same construction as [derive], kind
     * `fingerprint`, empty scope, prefix `fp`.
     */
    public fun fingerprint(value: String): String = hash("fp", "fingerprint", "", listOf(value))

    /** Random 128-bit ID (UUID v4, cryptographically secure source) for user-created entities. */
    public fun random(): String = Uuid.random().toString()
}

// Random IDs: user-created entities.

@JvmInline
public value class ProviderId(public val value: String) {
    public companion object {
        public fun random(): ProviderId = ProviderId(StableIds.random())
    }
}

@JvmInline
public value class PlaylistId(public val value: String) {
    public companion object {
        public fun random(): PlaylistId = PlaylistId(StableIds.random())
    }
}

@JvmInline
public value class EpgSourceId(public val value: String) {
    public companion object {
        public fun random(): EpgSourceId = EpgSourceId(StableIds.random())
    }
}

@JvmInline
public value class FavoriteId(public val value: String) {
    public companion object {
        public fun random(): FavoriteId = FavoriteId(StableIds.random())
    }
}

/** Handle to an entry in the platform secret store. Never the secret itself (ADR-0015). */
@JvmInline
public value class CredentialRef(public val value: String) {
    public companion object {
        public fun random(): CredentialRef = CredentialRef(StableIds.random())
    }
}

// Derived IDs: imported content, reproducible across refreshes and devices.

@JvmInline
public value class ChannelId(public val value: String)

@JvmInline
public value class GroupId(public val value: String)

@JvmInline
public value class MovieId(public val value: String)

@JvmInline
public value class SeriesId(public val value: String)

@JvmInline
public value class SeasonId(public val value: String)

@JvmInline
public value class EpisodeId(public val value: String)

@JvmInline
public value class MediaSourceId(public val value: String)

@JvmInline
public value class ProgramId(public val value: String)

@JvmInline
public value class ArtworkId(public val value: String)

/** A channel as identified inside one EPG source: `<channel id="…">` of that source. */
public data class EpgChannelKey(public val epgSourceId: EpgSourceId, public val channelId: String)
