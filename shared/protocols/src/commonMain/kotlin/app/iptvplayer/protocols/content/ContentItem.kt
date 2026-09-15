package app.iptvplayer.protocols.content

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.model.Artwork
import app.iptvplayer.domain.model.Channel
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.EpgChannel
import app.iptvplayer.domain.model.Episode
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.Program
import app.iptvplayer.domain.model.Season
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.security.Redactor
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.UrlTemplate

/** Credentials of the playlist source, used only to replace their values with placeholders (ADR-0015). */
public class SourceCredentials(public val username: Secret<String>?, public val password: Secret<String>?) {
    override fun toString(): String = "SourceCredentials(${Redactor.MARK})"
}

/** Normalized, protocol-neutral import output, emitted incrementally by M3U and Xtream importers. */
public sealed interface ContentItem {
    public data class Group(public val group: ChannelGroup) : ContentItem

    public data class ChannelItem(public val channel: Channel, public val mediaSource: MediaSource, public val logo: Artwork?) :
        ContentItem

    /** An identical entry listed again in another group: same channel, extra membership. */
    public data class GroupMembership(public val channelId: ChannelId, public val groupId: GroupId) : ContentItem

    public data class MovieItem(public val movie: Movie, public val mediaSource: MediaSource, public val poster: Artwork?) : ContentItem

    public data class SeriesItem(public val series: Series) : ContentItem

    public data class SeasonItem(public val season: Season) : ContentItem

    public data class EpisodeItem(public val episode: Episode, public val mediaSource: MediaSource) : ContentItem

    /** A channel as defined by an EPG source (`<channel id>`). */
    public data class EpgChannelItem(public val channel: EpgChannel, public val icon: Artwork?) : ContentItem

    /** A normalized guide programme and the artwork it references. */
    public data class ProgramItem(public val program: Program, public val artwork: List<Artwork>) : ContentItem

    /** EPG URL advertised by the source (M3U `url-tvg` / `x-tvg-url`, Xtream `xmltv.php`). */
    public data class EpgHint(public val url: UrlTemplate, public val timeShiftMinutes: Int?) : ContentItem

    /** A sensitive stream header value (Cookie, Authorization) that must go to the SecretStore under [ref]. */
    public class SensitiveHeader(public val ref: CredentialRef, public val name: String, public val value: Secret<String>) : ContentItem {
        override fun toString(): String = "SensitiveHeader(ref=${ref.value}, name=$name, value=${Redactor.MARK})"
    }
}

public data class ImportCounts(
    public val entries: Int = 0,
    public val channels: Int = 0,
    public val groups: Int = 0,
    public val movies: Int = 0,
    public val series: Int = 0,
    public val seasons: Int = 0,
    public val episodes: Int = 0,
    public val collapsedDuplicates: Int = 0,
    public val rejectedEntries: Int = 0,
    public val epgHints: Int = 0,
)
