package app.iptvplayer.epg

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.model.ChannelEpgLink
import app.iptvplayer.domain.model.EpgChannel
import app.iptvplayer.domain.model.EpgMatchMethod
import app.iptvplayer.domain.text.TextNormalization

/** A playlist channel as seen by the matcher. */
public data class EpgMatchCandidate(
    public val channelId: ChannelId,
    public val name: String,
    /** M3U `tvg-id` or Xtream `epg_channel_id`. */
    public val tvgId: String?,
    public val fromXtream: Boolean,
)

public data class EpgMatchResult(
    public val links: List<ChannelEpgLink>,
    /** No candidate EPG channel at all. */
    public val unmatched: List<ChannelId>,
    /** Several equally good candidates: left unlinked for the user to choose (docs/EPG.md §3). */
    public val ambiguous: List<ChannelId>,
)

/** Channel ↔ EPG channel matching, first match wins (docs/EPG.md §3). Pure and deterministic. */
public object EpgMatcher {
    public const val CONFIDENCE_OVERRIDE: Int = 100
    public const val CONFIDENCE_ID: Int = 95
    public const val CONFIDENCE_NAME_EXACT: Int = 80
    public const val CONFIDENCE_NAME_NORMALIZED: Int = 60

    public fun match(
        channels: List<EpgMatchCandidate>,
        epgChannels: List<EpgChannel>,
        sourcePriority: Int,
        overrides: Map<ChannelId, EpgChannelKey> = emptyMap(),
    ): EpgMatchResult {
        val byId = HashMap<String, EpgChannelKey>()
        val byExactName = HashMap<String, MutableSet<EpgChannelKey>>()
        val byNormalizedName = HashMap<String, MutableSet<EpgChannelKey>>()
        for (epg in epgChannels) {
            byId.putIfAbsent(epg.key.channelId.trim().lowercase(), epg.key)
            for (name in epg.displayNames) {
                byExactName.getOrPut(TextNormalization.normKey(name.text)) { LinkedHashSet() }.add(epg.key)
                val normalized = TextNormalization.matchNormalize(name.text)
                if (normalized.isNotEmpty()) byNormalizedName.getOrPut(normalized) { LinkedHashSet() }.add(epg.key)
            }
        }

        val links = ArrayList<ChannelEpgLink>()
        val unmatched = ArrayList<ChannelId>()
        val ambiguous = ArrayList<ChannelId>()
        for (channel in channels) {
            fun link(key: EpgChannelKey, method: EpgMatchMethod, confidence: Int) =
                links.add(ChannelEpgLink(channel.channelId, key, method, confidence, sourcePriority))

            overrides[channel.channelId]?.let {
                link(it, EpgMatchMethod.USER_OVERRIDE, CONFIDENCE_OVERRIDE)
                continue
            }
            val idKey = channel.tvgId?.trim()?.lowercase()?.ifEmpty { null }?.let { byId[it] }
            if (idKey != null) {
                link(idKey, if (channel.fromXtream) EpgMatchMethod.XTREAM_EPG_ID else EpgMatchMethod.TVG_ID, CONFIDENCE_ID)
                continue
            }
            val exact = byExactName[TextNormalization.normKey(channel.name)].orEmpty()
            if (exact.size == 1) {
                link(exact.first(), EpgMatchMethod.NAME_EXACT, CONFIDENCE_NAME_EXACT)
                continue
            }
            val normalized = TextNormalization.matchNormalize(
                channel.name,
            ).takeIf { it.isNotEmpty() }?.let { byNormalizedName[it] }.orEmpty()
            when {
                exact.size > 1 || normalized.size > 1 -> ambiguous.add(channel.channelId)
                normalized.size == 1 -> link(normalized.first(), EpgMatchMethod.NAME_NORMALIZED, CONFIDENCE_NAME_NORMALIZED)
                else -> unmatched.add(channel.channelId)
            }
        }
        return EpgMatchResult(links, unmatched, ambiguous)
    }

    /**
     * Combines links from several EPG sources: per channel, the source with the best (lowest) priority wins; within the
     * same priority, the higher confidence wins.
     */
    public fun mergeByPriority(links: List<ChannelEpgLink>): List<ChannelEpgLink> =
        links.groupBy { it.channelId }.values.map { candidates ->
            candidates.minWith(compareBy<ChannelEpgLink> { it.epgSourcePriority }.thenByDescending { it.confidence })
        }
}
