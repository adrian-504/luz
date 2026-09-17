package app.iptvplayer.protocols.m3u

import app.iptvplayer.domain.id.ArtworkId
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.DerivedIdKind
import app.iptvplayer.domain.id.EpisodeId
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.MovieId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.SeasonId
import app.iptvplayer.domain.id.SeriesId
import app.iptvplayer.domain.id.StableIds
import app.iptvplayer.domain.library.TitleCleaner
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
import app.iptvplayer.domain.model.DrmDescriptor
import app.iptvplayer.domain.model.DrmScheme
import app.iptvplayer.domain.model.Episode
import app.iptvplayer.domain.model.ExternalIds
import app.iptvplayer.domain.model.IdentityHints
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.Season
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.net.UrlCheck
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.net.UrlPolicy
import app.iptvplayer.domain.net.UrlRejection
import app.iptvplayer.domain.ports.DiagnosticLocation
import app.iptvplayer.domain.ports.DiagnosticSeverity
import app.iptvplayer.domain.ports.DiagnosticSink
import app.iptvplayer.domain.ports.ImportDiagnostic
import app.iptvplayer.domain.security.PercentEncoding
import app.iptvplayer.domain.security.Redactor
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.domain.text.TextNormalization
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.content.ImportCounts
import app.iptvplayer.protocols.content.SourceCredentials

internal enum class M3uContentClass { LIVE, MOVIE, SERIES }

/** Live / movie / series classification (docs/IPTV_PROTOCOLS.md §3.2). Heuristic; the user can reclassify groups. */
internal object M3uClassifier {
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")
    private val MOVIE_WORDS = setOf("movie", "movies", "film", "films", "vod", "cinema")
    private val SERIES_WORDS = setOf("series", "serie", "season", "seasons", "shows")

    fun classify(tvgType: String?, url: String, groups: List<String>, isRadio: Boolean): M3uContentClass {
        when (tvgType?.trim()?.lowercase()) {
            "live", "channel", "radio" -> return M3uContentClass.LIVE
            "movie", "movies", "vod" -> return M3uContentClass.MOVIE
            "series", "serie", "show" -> return M3uContentClass.SERIES
        }
        if (isRadio) return M3uContentClass.LIVE
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').lowercase()
        val segments = path.split('/')
        if ("live" in segments) return M3uContentClass.LIVE
        if ("series" in segments) return M3uContentClass.SERIES
        if ("movie" in segments || "movies" in segments || "vod" in segments) return M3uContentClass.MOVIE
        if (segments.last().substringAfterLast('.', "") in VOD_EXTENSIONS) return M3uContentClass.MOVIE
        val words = groups.flatMap { TextNormalization.matchNormalize(it).split(' ') }.toSet()
        if (words.any { it in SERIES_WORDS }) return M3uContentClass.SERIES
        if (words.any { it in MOVIE_WORDS }) return M3uContentClass.MOVIE
        return M3uContentClass.LIVE
    }

    private val EPISODE_SE = Regex("""(?i)\bS(\d{1,3})\s*[ ._-]?\s*E(\d{1,4})\b""")
    private val EPISODE_X = Regex("""\b(\d{1,2})x(\d{2,3})\b""")

    /** (series title, season, episode) from `Show S01E02`, or also `Show 1x02` when [allowXPattern] is set. */
    fun episode(title: String, allowXPattern: Boolean = true): Triple<String, Int, Int>? {
        val match = EPISODE_SE.find(title) ?: (if (allowXPattern) EPISODE_X.find(title) else null) ?: return null
        val season = match.groupValues[1].toInt()
        val episode = match.groupValues[2].toInt()
        val series = title.substring(0, match.range.first).trim { it.isWhitespace() || it in "-_.:|" }
        return Triple(series.ifEmpty { title.trim() }, season, episode)
    }

    private val YEAR = Regex("""\((\d{4})\)\s*$""")

    fun year(title: String): Int? = YEAR.find(title)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1900..2100 }

    fun protocol(url: String, scheme: String): StreamProtocol = when (scheme) {
        "rtmp", "rtmps" -> StreamProtocol.RTMP
        "rtsp", "rtsps" -> StreamProtocol.RTSP
        "udp", "rtp" -> StreamProtocol.UDP
        else -> when (url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "m3u8" -> StreamProtocol.HLS
            "mpd" -> StreamProtocol.DASH
            "ts" -> StreamProtocol.PROGRESSIVE_TS
            "mp4", "m4v", "mov" -> StreamProtocol.PROGRESSIVE_MP4
            "mkv" -> StreamProtocol.MATROSKA
            else -> StreamProtocol.UNKNOWN
        }
    }
}

/**
 * Turns raw M3U records into domain entities for one playlist import (docs/DOMAIN_MODEL.md §4, docs/IPTV_PROTOCOLS.md
 * §3). Stateful per import: assigns collision ordinals in file order and collapses identical entries.
 */
internal class M3uNormalizer(
    private val playlistId: PlaylistId,
    credentials: SourceCredentials?,
    private val emit: (ContentItem) -> Unit,
    private val diagnostics: DiagnosticSink,
) {
    private val username = credentials?.username?.unsafeValue()
    private val password = credentials?.password?.unsafeValue()

    private class SeenChannel(val channelId: ChannelId, val urlFingerprint: String, val groups: MutableSet<GroupId>)

    private val channelsByKey = HashMap<String, MutableList<SeenChannel>>()
    private val movieOrdinals = HashMap<String, Int>()
    private val episodeOrdinals = HashMap<String, Int>()
    private val groups = HashMap<GroupId, ChannelGroup>()
    private val seriesSeen = HashSet<SeriesId>()
    private val seasonsSeen = HashSet<SeasonId>()
    private val epgHintsSeen = HashSet<String>()

    private var headerCatchUp: Map<String, String> = emptyMap()
    var counts: ImportCounts = ImportCounts()
        private set

    val acceptedItems: Int get() = counts.channels + counts.movies + counts.episodes

    fun onHeader(header: M3uRecord.Header) {
        headerCatchUp = header.attributes.filterKeys { it == "catchup" || it == "catchup-days" || it == "catchup-source" }
        val shift = header.attributes["tvg-shift"]?.trim()?.removePrefix("+")?.toDoubleOrNull()?.let { (it * 60).toInt() }
        val urls = listOfNotNull(header.attributes["url-tvg"], header.attributes["x-tvg-url"])
            .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        for (raw in urls) {
            val template = checkedTemplate(raw, UrlContext.EPG, header.line, M3uDiagnosticCodes.INVALID_EPG_URL) ?: continue
            if (epgHintsSeen.add(template.template)) {
                emit(ContentItem.EpgHint(template, shift))
                counts = counts.copy(epgHints = counts.epgHints + 1)
            }
        }
    }

    fun onEntry(entry: M3uRecord.Entry) {
        counts = counts.copy(entries = counts.entries + 1)
        val attrs = entry.attributes
        val check = UrlPolicy.check(entry.url, UrlContext.STREAM)
        if (check !is UrlCheck.Allowed) {
            val reason = (check as UrlCheck.Rejected).reason
            val code = when (reason) {
                UrlRejection.SCHEME_NOT_ALLOWED -> M3uDiagnosticCodes.UNSUPPORTED_SCHEME
                else -> M3uDiagnosticCodes.INVALID_URL
            }
            reject(entry.line, code, "Stream URL rejected: $reason")
            return
        }
        val template = UrlTemplate.fromUrl(entry.url, username, password) ?: run {
            reject(entry.line, M3uDiagnosticCodes.INVALID_URL, "Stream URL could not be parsed")
            return
        }

        val name = displayName(entry) ?: run {
            reject(entry.line, M3uDiagnosticCodes.NO_USABLE_NAME, "Entry has no usable name")
            return
        }
        val groupTitles = (attrs["group-title"]?.split(';') ?: listOfNotNull(entry.extGroup))
            .map { TextNormalization.collapse(it) }.filter { it.isNotEmpty() }
        val classified = M3uClassifier.classify(attrs["tvg-type"], entry.url, groupTitles, attrs["radio"]?.lowercase() == "true")
        // VOD items with explicit SxxEyy numbering are episodes even when path or group only said "movie".
        val contentClass = if (classified == M3uContentClass.MOVIE && M3uClassifier.episode(name, allowXPattern = false) != null) {
            M3uContentClass.SERIES
        } else {
            classified
        }
        val protocol = M3uClassifier.protocol(entry.url, check.url.scheme)
        val tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null }

        when (contentClass) {
            M3uContentClass.LIVE -> onChannel(entry, name, groupTitles, tvgId, template, protocol)
            M3uContentClass.SERIES -> {
                val episode = M3uClassifier.episode(name)
                if (episode != null) {
                    onEpisode(entry, name, groupTitles, episode, template, protocol)
                } else {
                    info(M3uDiagnosticCodes.SERIES_WITHOUT_EPISODE, entry.line, "Series entry without episode number imported as a movie")
                    onMovie(entry, name, groupTitles, tvgId, template, protocol)
                }
            }
            M3uContentClass.MOVIE -> onMovie(entry, name, groupTitles, tvgId, template, protocol)
        }
    }

    private fun onChannel(
        entry: M3uRecord.Entry,
        name: String,
        groupTitles: List<String>,
        tvgId: String?,
        template: UrlTemplate,
        protocol: StreamProtocol,
    ) {
        val groupIds = groupTitles.map { group(ContentKind.LIVE, it) }
        val fingerprint = StableIds.fingerprint(template.template)
        val key = compositeKey(TextNormalization.normKey(tvgId ?: ""), TextNormalization.normKey(name))
        val seen = channelsByKey.getOrPut(key) { ArrayList() }
        val duplicate = seen.firstOrNull { it.urlFingerprint == fingerprint }
        if (duplicate != null) {
            for (groupId in groupIds) {
                if (duplicate.groups.add(groupId)) emit(ContentItem.GroupMembership(duplicate.channelId, groupId))
            }
            counts = counts.copy(collapsedDuplicates = counts.collapsedDuplicates + 1)
            info(M3uDiagnosticCodes.DUPLICATE_COLLAPSED, entry.line, "Identical entry collapsed into one channel")
            return
        }
        val channelId = ChannelId(
            StableIds.derive(
                DerivedIdKind.CHANNEL,
                playlistId.value,
                listOf("m3u", TextNormalization.normKey(tvgId ?: ""), TextNormalization.normKey(name), seen.size.toString()),
            ),
        )
        seen.add(SeenChannel(channelId, fingerprint, groupIds.toMutableSet()))
        val owner = ContentRef(ContentType.CHANNEL, channelId.value)
        val mediaSource = mediaSource(owner, entry, template, protocol)
        val logo = artwork(entry.attributes["tvg-logo"], ArtworkKind.LOGO, entry.line)
        val attrs = entry.attributes
        val channel = Channel(
            id = channelId,
            playlistId = playlistId,
            groupIds = groupIds,
            name = name,
            number = (attrs["tvg-chno"] ?: attrs["channel-number"])?.trim()?.toIntOrNull(),
            logo = logo?.id,
            tvgId = tvgId,
            providerStreamId = null,
            languages = list(attrs["tvg-language"]),
            countries = list(attrs["tvg-country"]),
            catchUp = catchUp(attrs, entry.line),
            mediaSourceIds = listOf(mediaSource.id),
            isAdult = if (attrs.containsKey("parent-code") || attrs["adult"] == "1") true else null,
            extras = extras(attrs, entry.line),
            identityHints = IdentityHints(fingerprint, tvgId, TextNormalization.matchNormalize(name)),
        )
        emit(ContentItem.ChannelItem(channel, mediaSource, logo))
        counts = counts.copy(channels = counts.channels + 1)
    }

    private fun onMovie(
        entry: M3uRecord.Entry,
        name: String,
        groupTitles: List<String>,
        tvgId: String?,
        template: UrlTemplate,
        protocol: StreamProtocol,
    ) {
        val key = compositeKey(TextNormalization.normKey(name), TextNormalization.normKey(tvgId ?: ""))
        val ordinal = movieOrdinals[key] ?: 0
        movieOrdinals[key] = ordinal + 1
        val movieId =
            MovieId(
                StableIds.derive(
                    DerivedIdKind.MOVIE,
                    playlistId.value,
                    listOf(TextNormalization.normKey(name), TextNormalization.normKey(tvgId ?: ""), ordinal.toString()),
                ),
            )
        val mediaSource = mediaSource(ContentRef(ContentType.MOVIE, movieId.value), entry, template, protocol)
        val poster = artwork(entry.attributes["tvg-logo"], ArtworkKind.POSTER, entry.line)
        // The id above stays derived from the raw name, so favourites and progress survive; only what is shown is cleaned.
        val clean = TitleCleaner.clean(name)
        val movie = Movie(
            id = movieId,
            playlistId = playlistId,
            groupIds = groupTitles.map { group(ContentKind.MOVIE, it) },
            title = clean.title,
            year = clean.year ?: M3uClassifier.year(name),
            duration = null,
            plot = null,
            genres = emptyList(),
            rating = null,
            releaseDate = null,
            poster = poster?.id,
            backdrop = null,
            mediaSourceIds = listOf(mediaSource.id),
            providerStreamId = null,
            externalIds = ExternalIds(),
            addedAt = null,
            quality = clean.quality,
            tags = clean.tags,
            language = clean.language,
        )
        emit(ContentItem.MovieItem(movie, mediaSource, poster))
        counts = counts.copy(movies = counts.movies + 1)
    }

    private fun onEpisode(
        entry: M3uRecord.Entry,
        name: String,
        groupTitles: List<String>,
        parsed: Triple<String, Int, Int>,
        template: UrlTemplate,
        protocol: StreamProtocol,
    ) {
        val (seriesTitle, seasonNumber, episodeNumber) = parsed
        val seriesId =
            SeriesId(StableIds.derive(DerivedIdKind.SERIES, playlistId.value, listOf("m3u", TextNormalization.normKey(seriesTitle))))
        if (seriesSeen.add(seriesId)) {
            val poster = artwork(entry.attributes["tvg-logo"], ArtworkKind.POSTER, entry.line)
            val clean = TitleCleaner.clean(seriesTitle)
            emit(
                ContentItem.SeriesItem(
                    Series(
                        id = seriesId, playlistId = playlistId, groupIds = groupTitles.map { group(ContentKind.SERIES, it) },
                        title = clean.title, year = clean.year, quality = clean.quality, tags = clean.tags,
                        language = clean.language, plot = null, genres = emptyList(), rating = null,
                        poster = poster?.id, backdrop = null,
                        providerSeriesId = null, externalIds = ExternalIds(), lastModifiedAt = null,
                    ),
                    poster,
                ),
            )
            counts = counts.copy(series = counts.series + 1)
        }
        val seasonId = SeasonId(StableIds.derive(DerivedIdKind.SEASON, seriesId.value, listOf(seasonNumber.toString())))
        if (seasonsSeen.add(seasonId)) {
            emit(ContentItem.SeasonItem(Season(seasonId, seriesId, seasonNumber, title = null, poster = null, episodeCount = null)))
            counts = counts.copy(seasons = counts.seasons + 1)
        }
        val key = "${seriesId.value}|$seasonNumber|$episodeNumber"
        val ordinal = episodeOrdinals[key] ?: 0
        episodeOrdinals[key] = ordinal + 1
        val episodeId =
            EpisodeId(
                StableIds.derive(
                    DerivedIdKind.EPISODE,
                    seriesId.value,
                    listOf("m3u", seasonNumber.toString(), episodeNumber.toString(), ordinal.toString()),
                ),
            )
        val mediaSource = mediaSource(ContentRef(ContentType.EPISODE, episodeId.value), entry, template, protocol)
        val still = artwork(entry.attributes["tvg-logo"], ArtworkKind.THUMBNAIL, entry.line)
        emit(
            ContentItem.EpisodeItem(
                Episode(
                    id = episodeId, seasonId = seasonId, seriesId = seriesId, episodeNumber = episodeNumber, title = name,
                    plot = null, duration = null, airDate = null, still = still?.id, mediaSourceIds = listOf(mediaSource.id),
                    providerEpisodeId = null,
                ),
                mediaSource,
                still,
            ),
        )
        counts = counts.copy(episodes = counts.episodes + 1)
    }

    private fun group(kind: ContentKind, title: String): GroupId {
        val id = GroupId(StableIds.derive(DerivedIdKind.GROUP, playlistId.value, listOf(kind.name, TextNormalization.normKey(title))))
        if (id !in groups) {
            val group = ChannelGroup(id, playlistId, kind, title, providerSortOrder = groups.size, providerCategoryId = null)
            groups[id] = group
            emit(ContentItem.Group(group))
            counts = counts.copy(groups = counts.groups + 1)
        }
        return id
    }

    private fun mediaSource(owner: ContentRef, entry: M3uRecord.Entry, template: UrlTemplate, protocol: StreamProtocol): MediaSource {
        val id = MediaSourceId(StableIds.derive(DerivedIdKind.MEDIA_SOURCE, owner.id, listOf("m3u", template.template)))
        return MediaSource(
            id = id,
            owner = owner,
            locator = MediaLocator.DirectUrl(template),
            protocolHint = protocol,
            headers = headers(entry, id),
            drm = drm(entry),
            codecHints = null,
            priority = 0,
        )
    }

    /** Header precedence: EXTVLCOPT, then EXTHTTP, then pipe headers (the most URL-local wins). */
    private fun headers(entry: M3uRecord.Entry, mediaSourceId: MediaSourceId): MediaHeaders {
        val all = LinkedHashMap<String, String>()
        entry.vlcOptions["http-user-agent"]?.let { all["User-Agent"] = it }
        (entry.vlcOptions["http-referrer"] ?: entry.vlcOptions["http-referer"])?.let { all["Referer"] = it }
        entry.httpHeaders.forEach { (k, v) -> all[canonicalHeader(k)] = v }
        entry.pipeHeaders.forEach { (k, v) -> all[canonicalHeader(k)] = v }
        val custom = LinkedHashMap<String, String>()
        val sensitive = LinkedHashMap<String, CredentialRef>()
        for ((name, value) in all) {
            when {
                name.equals("User-Agent", ignoreCase = true) || name.equals("Referer", ignoreCase = true) -> Unit
                name.lowercase() in Redactor.SENSITIVE_HEADERS -> {
                    val ref = CredentialRef(StableIds.fingerprint("header|${mediaSourceId.value}|${name.lowercase()}"))
                    sensitive[name] = ref
                    emit(ContentItem.SensitiveHeader(ref, name, Secret(value)))
                }
                name.lowercase() in ALLOWED_CUSTOM_HEADERS -> custom[name] = value
            }
        }
        return MediaHeaders(userAgent = all["User-Agent"], referrer = all["Referer"], custom = custom, sensitive = sensitive)
    }

    private fun drm(entry: M3uRecord.Entry): DrmDescriptor? {
        val scheme = when (entry.kodiProps["inputstream.adaptive.license_type"]?.lowercase()) {
            "com.widevine.alpha", "widevine" -> DrmScheme.WIDEVINE
            "com.microsoft.playready", "playready" -> DrmScheme.PLAYREADY
            "org.w3.clearkey", "clearkey" -> DrmScheme.CLEARKEY
            "com.apple.fps", "fairplay" -> DrmScheme.FAIRPLAY
            else -> return null
        }
        val licenseUrl = entry.kodiProps["inputstream.adaptive.license_key"]?.substringBefore('|')?.trim()?.takeIf { it.contains("://") }
        val template = licenseUrl?.let { checkedTemplate(it, UrlContext.API, entry.line, M3uDiagnosticCodes.INVALID_URL) }
        return DrmDescriptor(scheme, template, MediaHeaders())
    }

    private fun catchUp(attrs: Map<String, String>, line: Long): CatchUpInfo? {
        val mode = when ((attrs["catchup"] ?: headerCatchUp["catchup"])?.trim()?.lowercase()) {
            null, "" -> return null
            "default" -> CatchUpMode.M3U_DEFAULT
            "append" -> CatchUpMode.M3U_APPEND
            "shift" -> CatchUpMode.M3U_SHIFT
            "flussonic", "fs", "flussonic-hls", "flussonic-ts" -> CatchUpMode.M3U_FLUSSONIC
            "xc" -> CatchUpMode.XTREAM_TIMESHIFT
            else -> CatchUpMode.M3U_TEMPLATE
        }
        val days = (attrs["catchup-days"] ?: headerCatchUp["catchup-days"])?.trim()?.toIntOrNull()
        val source = (attrs["catchup-source"] ?: headerCatchUp["catchup-source"])?.trim()?.ifEmpty { null }
        val template = source?.let {
            if (it.contains("://")) checkedTemplate(it, UrlContext.STREAM, line, M3uDiagnosticCodes.INVALID_URL) else UrlTemplate(it)
        }
        return CatchUpInfo(mode, days, template)
    }

    private fun artwork(raw: String?, kind: ArtworkKind, line: Long): Artwork? {
        val value = raw?.trim()?.ifEmpty { null } ?: return null
        // Support URL-encoded values (§6.1): decode once when the raw value is not itself a URL.
        val decoded = if (!value.contains("://") && PercentEncoding.decode(value).contains("://")) PercentEncoding.decode(value) else value
        val candidate = decoded.replace(" ", "%20")
        val template = checkedTemplate(candidate, UrlContext.ARTWORK, line, M3uDiagnosticCodes.INVALID_ARTWORK_URL) ?: return null
        return Artwork(
            ArtworkId(StableIds.derive(DerivedIdKind.ARTWORK, "", listOf(template.template))),
            template,
            kind,
            ArtworkOrigin.PROVIDER,
            null,
            null,
        )
    }

    private fun checkedTemplate(raw: String, context: UrlContext, line: Long, code: String): UrlTemplate? {
        val check = UrlPolicy.check(raw, context)
        if (check is UrlCheck.Rejected) {
            info(code, line, "URL rejected: ${check.reason}")
            return null
        }
        return UrlTemplate.fromUrl(raw, username, password) ?: run {
            info(code, line, "URL could not be parsed")
            null
        }
    }

    private fun displayName(entry: M3uRecord.Entry): String? {
        val candidates = listOf(entry.title, entry.attributes["tvg-name"], urlStem(entry.url))
        val name = candidates.map { TextNormalization.collapse(it ?: "") }.firstOrNull { it.isNotEmpty() } ?: return null
        if (name.length <= DomainLimits.MAX_NAME_LENGTH) return name
        info(M3uDiagnosticCodes.NAME_TRUNCATED, entry.line, "Name truncated to ${DomainLimits.MAX_NAME_LENGTH} characters")
        return name.take(DomainLimits.MAX_NAME_LENGTH)
    }

    private fun urlStem(url: String): String? {
        val lastSegment = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return PercentEncoding.decode(lastSegment.substringBeforeLast('.')).ifEmpty { null }
    }

    private fun extras(attrs: Map<String, String>, line: Long): Map<String, String> {
        val unknown = attrs.filterKeys { it !in KNOWN_ATTRIBUTES }
        if (unknown.size > DomainLimits.MAX_EXTRAS) {
            info(M3uDiagnosticCodes.EXTRAS_TRUNCATED, line, "Unknown attributes beyond ${DomainLimits.MAX_EXTRAS} dropped")
        }
        return unknown.entries.take(DomainLimits.MAX_EXTRAS).associate { (k, v) -> k to v.take(DomainLimits.MAX_EXTRA_VALUE_LENGTH) }
    }

    private fun list(value: String?): List<String> = value?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun reject(line: Long, code: String, message: String) {
        counts = counts.copy(rejectedEntries = counts.rejectedEntries + 1)
        report(DiagnosticSeverity.WARNING, code, line, message)
    }

    private fun info(code: String, line: Long, message: String) = report(DiagnosticSeverity.INFO, code, line, message)

    private fun report(severity: DiagnosticSeverity, code: String, line: Long, message: String) {
        diagnostics.report(ImportDiagnostic(null, severity, code, DiagnosticLocation(line = line), message, null))
    }

    private companion object {
        val KNOWN_ATTRIBUTES = setOf(
            "tvg-id", "tvg-name", "tvg-logo", "group-title", "tvg-country", "tvg-language", "tvg-chno", "channel-number",
            "tvg-shift", "catchup", "catchup-days", "catchup-source", "timeshift", "radio", "tvg-type", "parent-code", "adult",
        )
        val ALLOWED_CUSTOM_HEADERS = setOf("origin", "accept", "accept-language", "x-forwarded-for")

        /** Unambiguous in-memory key for two strings. */
        fun compositeKey(a: String, b: String): String = "${a.length}:$a$b"

        fun canonicalHeader(name: String): String = when (name.trim().lowercase()) {
            "user-agent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            else -> name.trim()
        }
    }
}
