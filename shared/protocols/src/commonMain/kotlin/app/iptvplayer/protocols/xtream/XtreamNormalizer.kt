package app.iptvplayer.protocols.xtream

import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.id.ArtworkId
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.DerivedIdKind
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpisodeId
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.MovieId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.domain.id.SeasonId
import app.iptvplayer.domain.id.SeriesId
import app.iptvplayer.domain.id.StableIds
import app.iptvplayer.domain.model.Artwork
import app.iptvplayer.domain.model.ArtworkKind
import app.iptvplayer.domain.model.ArtworkOrigin
import app.iptvplayer.domain.model.CatchUpInfo
import app.iptvplayer.domain.model.CatchUpMode
import app.iptvplayer.domain.model.Channel
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.DomainLimits
import app.iptvplayer.domain.model.Episode
import app.iptvplayer.domain.model.ExternalIds
import app.iptvplayer.domain.model.IdentityHints
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.Program
import app.iptvplayer.domain.model.ProgramFlags
import app.iptvplayer.domain.model.Season
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.net.UrlCheck
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.net.UrlPolicy
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.domain.text.TextNormalization
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.json.HtmlEntities
import app.iptvplayer.protocols.json.LenientObject
import app.iptvplayer.protocols.m3u.M3uClassifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Xtream JSON elements → domain entities (docs/IPTV_PROTOCOLS.md §4.3). Stateless per element except for the category
 * table of the unit being imported. Never throws on malformed elements.
 */
internal class XtreamNormalizer(
    private val playlistId: PlaylistId,
    private val reporter: XtreamReporter,
    private val credentials: XtreamCredentials?,
    streamFormats: Set<StreamFormat>,
) {
    private val categories = HashMap<String, GroupId>()
    private val seenIds = HashSet<String>()

    /** Live protocol hint from what the account allows; the resolver picks the concrete output at playback time. */
    private val liveProtocol = when {
        StreamFormat.MPEG_TS in streamFormats -> StreamProtocol.PROGRESSIVE_TS
        StreamFormat.HLS in streamFormats -> StreamProtocol.HLS
        else -> StreamProtocol.UNKNOWN
    }

    fun category(element: JsonElement, kind: ContentKind, order: Int): ContentItem.Group? {
        val obj = lenient(element) ?: return null
        val id = obj.string("category_id") ?: return missingId("category_id")
        val title = cleanName(obj.string("category_name")) ?: "Category $id"
        val groupId = GroupId(StableIds.derive(DerivedIdKind.GROUP, playlistId.value, listOf(kind.name, "xtream", id)))
        categories[id] = groupId
        return ContentItem.Group(ChannelGroup(groupId, playlistId, kind, title, order, providerCategoryId = id))
    }

    fun liveStream(element: JsonElement): ContentItem.ChannelItem? {
        val obj = lenient(element) ?: return null
        val streamId = obj.string("stream_id") ?: return missingId("stream_id")
        val name = cleanName(obj.string("name")) ?: return noName()
        if (!seenIds.add("live|$streamId")) return duplicate()
        val channelId = ChannelId(StableIds.derive(DerivedIdKind.CHANNEL, playlistId.value, listOf("xtream", streamId)))
        val mediaSource = mediaSource(ContentRef(ContentType.CHANNEL, channelId.value), XtreamStreamKind.LIVE, streamId, null, liveProtocol)
        val logo = artwork(obj.string("stream_icon"), ArtworkKind.LOGO)
        val archiveDays = obj.int("tv_archive_duration")
        val tvgId = obj.string("epg_channel_id")
        val channel = Channel(
            id = channelId,
            playlistId = playlistId,
            groupIds = groups(obj),
            name = name,
            number = obj.int("num"),
            logo = logo?.id,
            tvgId = tvgId,
            providerStreamId = streamId,
            languages = emptyList(),
            countries = emptyList(),
            catchUp = if (obj.bool("tv_archive") == true) CatchUpInfo(CatchUpMode.XTREAM_TIMESHIFT, archiveDays, null) else null,
            mediaSourceIds = listOf(mediaSource.id),
            isAdult = obj.bool("is_adult"),
            extras = emptyMap(),
            identityHints = IdentityHints(StableIds.fingerprint("xtream|live|$streamId"), tvgId, TextNormalization.matchNormalize(name)),
        )
        return ContentItem.ChannelItem(channel, mediaSource, logo)
    }

    fun vodStream(element: JsonElement): ContentItem.MovieItem? {
        val obj = lenient(element) ?: return null
        val streamId = obj.string("stream_id") ?: return missingId("stream_id")
        val name = cleanName(obj.string("name")) ?: return noName()
        if (!seenIds.add("movie|$streamId")) return duplicate()
        val movieId = MovieId(StableIds.derive(DerivedIdKind.MOVIE, playlistId.value, listOf("xtream", streamId)))
        val extension = obj.string("container_extension")
        val mediaSource =
            mediaSource(ContentRef(ContentType.MOVIE, movieId.value), XtreamStreamKind.MOVIE, streamId, extension, protocolFor(extension))
        val poster = artwork(obj.string("stream_icon"), ArtworkKind.POSTER)
        val movie = Movie(
            id = movieId,
            playlistId = playlistId,
            groupIds = groups(obj),
            title = name,
            year = M3uClassifier.year(name),
            duration = null,
            plot = null,
            genres = emptyList(),
            rating = obj.string("rating")?.takeUnless { it == "0" },
            releaseDate = null,
            poster = poster?.id,
            backdrop = null,
            mediaSourceIds = listOf(mediaSource.id),
            providerStreamId = streamId,
            externalIds = ExternalIds(tmdb = obj.string("tmdb")),
            addedAt = obj.epochSeconds("added"),
        )
        return ContentItem.MovieItem(movie, mediaSource, poster)
    }

    fun series(element: JsonElement): ContentItem.SeriesItem? {
        val obj = lenient(element) ?: return null
        val providerId = obj.string("series_id") ?: return missingId("series_id")
        val name = cleanName(obj.string("name")) ?: return noName()
        if (!seenIds.add("series|$providerId")) return duplicate()
        val backdrop = (obj.array("backdrop_path")?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content
        val series = Series(
            id = seriesId(providerId),
            playlistId = playlistId,
            groupIds = groups(obj),
            title = name,
            year = (obj.string("releaseDate") ?: obj.string("release_date"))?.take(4)?.toIntOrNull(),
            plot = obj.string("plot")?.take(DomainLimits.MAX_DESCRIPTION_LENGTH),
            genres = obj.string("genre")?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            rating = obj.string("rating")?.takeUnless { it == "0" },
            poster = artwork(obj.string("cover"), ArtworkKind.POSTER)?.id,
            backdrop = artwork(backdrop, ArtworkKind.BACKDROP)?.id,
            providerSeriesId = providerId,
            externalIds = ExternalIds(tmdb = obj.string("tmdb")),
            lastModifiedAt = obj.epochSeconds("last_modified"),
        )
        return ContentItem.SeriesItem(series)
    }

    fun seriesId(providerSeriesId: String): SeriesId =
        SeriesId(StableIds.derive(DerivedIdKind.SERIES, playlistId.value, listOf("xtream", providerSeriesId)))

    /** `get_series_info`: seasons from the `seasons` array plus every season that has episodes; episodes keyed or listed. */
    fun seriesInfo(document: JsonElement, seriesId: SeriesId): List<ContentItem> {
        val root = lenient(document) ?: return emptyList()
        val items = ArrayList<ContentItem>()
        val seasonMeta = HashMap<Int, LenientObject>()
        root.array("seasons")?.forEach { element ->
            val season = LenientObject.of(element, reporter::mismatch) ?: return@forEach
            season.int("season_number")?.let { seasonMeta[it] = season }
        }
        val episodesBySeason = LinkedHashMap<Int, MutableList<LenientObject>>()
        when (val episodes = root.raw("episodes")) {
            is JsonObject -> episodes.forEach { (key, value) ->
                (value as? JsonArray)?.forEach { e ->
                    LenientObject.of(e, reporter::mismatch)?.let {
                        add(
                            episodesBySeason,
                            it.int("season") ?: key.toIntOrNull(),
                            it,
                        )
                    }
                }
            }
            is JsonArray -> episodes.forEach { group ->
                val list = group as? JsonArray ?: JsonArray(listOf(group))
                list.forEach { e -> LenientObject.of(e, reporter::mismatch)?.let { add(episodesBySeason, it.int("season"), it) } }
            }
            else -> Unit
        }
        val seasonNumbers = (seasonMeta.keys + episodesBySeason.keys).sorted()
        for (number in seasonNumbers) {
            val meta = seasonMeta[number]
            val seasonId = SeasonId(StableIds.derive(DerivedIdKind.SEASON, seriesId.value, listOf(number.toString())))
            items += ContentItem.SeasonItem(
                Season(
                    seasonId,
                    seriesId,
                    number,
                    cleanName(meta?.string("name")),
                    artwork(meta?.string("cover"), ArtworkKind.SEASON_POSTER)?.id,
                    meta?.int("episode_count"),
                ),
            )
            for (episode in episodesBySeason[number].orEmpty()) episode(episode, seriesId, seasonId)?.let { items += it }
        }
        return items
    }

    private fun add(target: MutableMap<Int, MutableList<LenientObject>>, season: Int?, episode: LenientObject) {
        if (season == null) {
            reporter.mismatch("season")
            return
        }
        target.getOrPut(season) { ArrayList() }.add(episode)
    }

    private fun episode(obj: LenientObject, seriesId: SeriesId, seasonId: SeasonId): ContentItem.EpisodeItem? {
        val providerId = obj.string("id") ?: return missingId("id")
        val number = obj.int("episode_num") ?: return missingId("episode_num")
        val episodeId = EpisodeId(StableIds.derive(DerivedIdKind.EPISODE, seriesId.value, listOf("xtream", providerId)))
        val extension = obj.string("container_extension")
        val mediaSource =
            mediaSource(
                ContentRef(ContentType.EPISODE, episodeId.value),
                XtreamStreamKind.SERIES,
                providerId,
                extension,
                protocolFor(extension),
            )
        val info = obj.obj("info")
        val episode = Episode(
            id = episodeId,
            seasonId = seasonId,
            seriesId = seriesId,
            episodeNumber = number,
            title = cleanName(obj.string("title")),
            plot = info?.string("plot")?.take(DomainLimits.MAX_DESCRIPTION_LENGTH),
            duration = info?.let { duration(it) },
            airDate = info?.string("releasedate") ?: info?.string("air_date"),
            still = artwork(info?.string("movie_image"), ArtworkKind.THUMBNAIL)?.id,
            mediaSourceIds = listOf(mediaSource.id),
            providerEpisodeId = providerId,
        )
        return ContentItem.EpisodeItem(episode, mediaSource)
    }

    /** `get_short_epg`: base64 title/description; UTC epoch timestamps are authoritative over local time strings. */
    fun shortEpg(document: JsonElement, key: EpgChannelKey): List<Program> {
        val listings = lenient(document)?.array("epg_listings") ?: return emptyList()
        return listings.mapIndexedNotNull { index, element ->
            reporter.index = index.toLong()
            val obj = LenientObject.of(element, reporter::mismatch) ?: return@mapIndexedNotNull null
            val start = obj.epochSeconds("start_timestamp")
            val end = obj.epochSeconds("stop_timestamp")
            if (start == null || end == null || end <= start) {
                reporter.info(XtreamDiagnosticCodes.INVALID_PROGRAMME_TIME, "Programme without valid start/stop timestamps skipped")
                return@mapIndexedNotNull null
            }
            val title = base64(obj.string("title"))?.let { cleanName(it) } ?: return@mapIndexedNotNull null
            Program(
                id = ProgramId(
                    StableIds.derive(DerivedIdKind.PROGRAM, key.epgSourceId.value, listOf(key.channelId, start.epochSeconds.toString())),
                ),
                epgChannelKey = key,
                start = start,
                end = end,
                title = title,
                subtitle = null,
                description = base64(obj.string("description"))?.take(DomainLimits.MAX_DESCRIPTION_LENGTH),
                categories = emptyList(),
                episode = null,
                artwork = emptyList(),
                rating = null,
                flags = ProgramFlags(),
                language = obj.string("lang"),
            )
        }.also { reporter.index = null }
    }

    private fun base64(value: String?): String? {
        if (value == null) return null
        return try {
            Base64.decode(value).decodeToString()
        } catch (_: IllegalArgumentException) {
            reporter.info(XtreamDiagnosticCodes.INVALID_BASE64, "Text is not valid base64; kept as received")
            value
        }
    }

    private fun duration(info: LenientObject): Duration? {
        info.long("duration_secs")?.let { return it.seconds }
        val text = info.string("duration") ?: return null
        val parts = text.split(':').map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).seconds
            2 -> (parts[0] * 60 + parts[1]).seconds
            else -> null
        }
    }

    private fun groups(obj: LenientObject): List<GroupId> {
        val ids = obj.stringList("category_ids").ifEmpty { obj.stringList("category_id") }
        return ids.distinct().mapNotNull { id ->
            categories[id] ?: run {
                reporter.info(XtreamDiagnosticCodes.UNKNOWN_CATEGORY, "Item references a category that is not in the category list")
                null
            }
        }
    }

    private fun mediaSource(
        owner: ContentRef,
        kind: XtreamStreamKind,
        streamId: String,
        extension: String?,
        protocol: StreamProtocol,
    ): MediaSource {
        val id = MediaSourceId(StableIds.derive(DerivedIdKind.MEDIA_SOURCE, owner.id, listOf("xtream", kind.name.lowercase(), streamId)))
        return MediaSource(
            id,
            owner,
            MediaLocator.XtreamStream(kind, streamId, extension),
            protocol,
            MediaHeaders(),
            drm = null,
            codecHints = null,
            priority = 0,
        )
    }

    private fun artwork(raw: String?, kind: ArtworkKind): Artwork? {
        val value = raw?.trim()?.ifEmpty { null } ?: return null
        val check = UrlPolicy.check(value.replace(" ", "%20"), UrlContext.ARTWORK)
        if (check !is UrlCheck.Allowed) {
            reporter.info(XtreamDiagnosticCodes.INVALID_ARTWORK_URL, "Artwork URL rejected by policy")
            return null
        }
        val template =
            UrlTemplate.fromUrl(check.url.toUrlString(), credentials?.username?.unsafeValue(), credentials?.password?.unsafeValue())
                ?: return null
        return Artwork(
            ArtworkId(StableIds.derive(DerivedIdKind.ARTWORK, "", listOf(template.template))),
            template,
            kind,
            ArtworkOrigin.PROVIDER,
            null,
            null,
        )
    }

    private fun lenient(element: JsonElement): LenientObject? = LenientObject.of(element, reporter::mismatch) ?: run {
        reporter.info(XtreamDiagnosticCodes.NOT_AN_OBJECT, "List element is not a JSON object")
        null
    }

    private fun cleanName(raw: String?): String? =
        raw?.let { TextNormalization.collapse(HtmlEntities.decode(it)) }?.ifEmpty { null }?.take(DomainLimits.MAX_NAME_LENGTH)

    private fun <T> missingId(field: String): T? {
        reporter.warning(XtreamDiagnosticCodes.MISSING_ID, "Item without '$field' skipped")
        return null
    }

    private fun <T> noName(): T? {
        reporter.warning(XtreamDiagnosticCodes.NO_USABLE_NAME, "Item without a usable name skipped")
        return null
    }

    private fun <T> duplicate(): T? {
        reporter.info(XtreamDiagnosticCodes.DUPLICATE_ID, "Item with an already imported id skipped")
        return null
    }

    companion object {
        fun protocolFor(extension: String?): StreamProtocol = when (extension?.lowercase()) {
            "m3u8" -> StreamProtocol.HLS
            "ts" -> StreamProtocol.PROGRESSIVE_TS
            "mp4", "m4v", "mov" -> StreamProtocol.PROGRESSIVE_MP4
            "mkv" -> StreamProtocol.MATROSKA
            else -> StreamProtocol.UNKNOWN
        }
    }
}
