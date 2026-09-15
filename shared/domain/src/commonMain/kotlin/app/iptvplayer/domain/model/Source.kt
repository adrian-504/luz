package app.iptvplayer.domain.model

import app.iptvplayer.domain.capability.Capabilities
import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.security.UrlTemplate
import kotlin.time.Instant

/** Source protocol. Adding a protocol adds a value and a SourceAdapter; UI never switches on it (§5.2). */
public enum class ProtocolId { M3U, XTREAM, XMLTV, LOCAL_FILE }

public enum class TransportSecurity { TLS, CLEARTEXT, NOT_APPLICABLE }

public enum class AccountStatus { ACTIVE, EXPIRED, BANNED, DISABLED, UNKNOWN }

/** Provider account facts reported by the source (Xtream `user_info` / `server_info`). */
public data class ProviderAccount(
    public val status: AccountStatus,
    public val expiresAt: Instant?,
    public val isTrial: Boolean?,
    public val maxConnections: Int?,
    public val activeConnections: Int?,
    public val allowedOutputFormats: Set<StreamFormat>,
    public val serverTimezone: String?,
)

/** The remote service a source connects to. Holds no secrets: credentials are behind [credentialRef]. */
public data class Provider(
    public val id: ProviderId,
    public val displayName: String,
    public val protocol: ProtocolId,
    /** Scheme + host + port only, for UI identity. Never contains credentials. */
    public val endpointDisplay: String,
    public val endpoint: UrlTemplate?,
    public val credentialRef: CredentialRef?,
    public val capabilities: Capabilities,
    public val account: ProviderAccount?,
    public val transportSecurity: TransportSecurity,
    public val createdAt: Instant,
    public val updatedAt: Instant,
)

public enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

public sealed interface RefreshPolicy {
    public data object Manual : RefreshPolicy

    public data class Interval(public val hours: Int) : RefreshPolicy {
        init {
            require(hours > 0) { "hours must be positive" }
        }
    }

    public data class OnLaunchIfOlderThan(public val hours: Int) : RefreshPolicy {
        init {
            require(hours > 0) { "hours must be positive" }
        }
    }
}

public data class ContentSelection(
    public val live: Boolean = true,
    public val movies: Boolean = true,
    public val series: Boolean = true,
    public val epg: Boolean = true,
)

public enum class PreferredStreamFormat { HLS, MPEG_TS, AUTO }

/** Independently published parts of an import (docs/IPTV_PROTOCOLS.md §2). */
public enum class ImportUnit { LIVE, MOVIES, SERIES, EPG }

public enum class ImportStatus { NEVER, RUNNING, PUBLISHED, UNCHANGED, PARTIAL, FAILED }

public data class UnitImportState(
    public val unit: ImportUnit,
    public val status: ImportStatus,
    public val snapshotVersion: Long,
    public val startedAt: Instant?,
    public val finishedAt: Instant?,
    public val itemCount: Int,
    public val etag: String?,
    public val lastModified: String?,
    public val diagnosticsId: String?,
)

/** A user-configured source bound to a provider. */
public data class Playlist(
    public val id: PlaylistId,
    public val providerId: ProviderId,
    public val type: PlaylistType,
    public val displayName: String,
    public val enabled: Boolean,
    public val sortOrder: Int,
    public val isDefault: Boolean,
    public val refreshPolicy: RefreshPolicy,
    public val contentSelection: ContentSelection,
    public val preferredStreamFormat: PreferredStreamFormat,
    public val importStates: Map<ImportUnit, UnitImportState>,
)

public enum class EpgSourceOrigin { USER_URL, M3U_HEADER, XTREAM_XMLTV }

public enum class EpgFormat { XMLTV, XTREAM_SHORT_EPG }

public data class EpgSource(
    public val id: EpgSourceId,
    public val displayName: String,
    public val origin: EpgSourceOrigin,
    /** Template without credential values; secret URLs are referenced through [credentialRef]. */
    public val url: UrlTemplate?,
    public val credentialRef: CredentialRef?,
    public val format: EpgFormat,
    public val refreshPolicy: RefreshPolicy,
    public val timeShiftMinutes: Int,
    public val enabled: Boolean,
    public val importState: UnitImportState?,
)

public data class PlaylistEpgLink(public val playlistId: PlaylistId, public val epgSourceId: EpgSourceId, public val priority: Int)
