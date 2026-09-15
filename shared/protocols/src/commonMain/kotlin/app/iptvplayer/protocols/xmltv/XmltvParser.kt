package app.iptvplayer.protocols.xmltv

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.protocols.xml.XmlTokenizer
import app.iptvplayer.protocols.xml.XmlTokenizer.Token

internal data class LangText(val text: String, val lang: String?)

/** Raw XMLTV records. Internal: they never leave shared:protocols. */
internal sealed interface XmltvRecord {
    class Channel(val id: String?, val displayNames: List<LangText>, val iconSrc: String?) : XmltvRecord

    class Programme(
        val channel: String?,
        val start: String?,
        val stop: String?,
        val titles: List<LangText>,
        val subTitles: List<LangText>,
        val descriptions: List<LangText>,
        val categories: List<LangText>,
        val iconSources: List<String>,
        val episodeNumbers: List<Pair<String?, String>>,
        val rating: Pair<String?, String>?,
        val isNew: Boolean,
        val isLive: Boolean,
        val isPremiere: Boolean,
        val previouslyShown: Boolean,
        val language: String?,
    ) : XmltvRecord
}

internal class XmltvParseResult(val truncated: Boolean, val stopped: LimitKind?, val records: Long)

/** Pulls XMLTV `<channel>` and `<programme>` records from the tokenizer, ignoring unknown elements. */
internal class XmltvParser(private val tokenizer: XmlTokenizer, private val maxRecords: Long) {
    private class ProgrammeBuilder(val attributes: Map<String, String>) {
        val titles = ArrayList<LangText>()
        val subTitles = ArrayList<LangText>()
        val descriptions = ArrayList<LangText>()
        val categories = ArrayList<LangText>()
        val icons = ArrayList<String>()
        val episodeNumbers = ArrayList<Pair<String?, String>>()
        var ratingSystem: String? = null
        var rating: Pair<String?, String>? = null
        var isNew = false
        var isLive = false
        var isPremiere = false
        var previouslyShown = false
        var language: String? = null
    }

    fun parse(onRecord: (XmltvRecord) -> Unit): XmltvParseResult {
        val path = ArrayList<String>()
        var programme: ProgrammeBuilder? = null
        var channelId: String? = null
        var channelNames: ArrayList<LangText>? = null
        var channelIcon: String? = null
        var text: StringBuilder? = null
        var leafAttributes: Map<String, String> = emptyMap()
        var records = 0L

        while (true) {
            when (val token = tokenizer.next()) {
                is Token.Stopped -> return XmltvParseResult(truncated = false, stopped = token.limit, records = records)
                is Token.EndOfDocument -> return XmltvParseResult(token.truncated, null, records)
                is Token.Text -> text?.append(token.text)
                is Token.Start -> {
                    path.add(token.name)
                    val depth = path.size
                    when {
                        depth == 2 && token.name == "programme" -> programme = ProgrammeBuilder(token.attributes)
                        depth == 2 && token.name == "channel" -> {
                            channelId = token.attributes["id"]
                            channelNames = ArrayList()
                            channelIcon = null
                        }
                        depth == 3 && programme != null -> when (token.name) {
                            "title", "sub-title", "desc", "category", "episode-num", "language" -> {
                                text = StringBuilder()
                                leafAttributes = token.attributes
                            }
                            "icon" -> token.attributes["src"]?.let { programme.icons.add(it) }
                            "rating" -> programme.ratingSystem = token.attributes["system"]
                            "new" -> programme.isNew = true
                            "live" -> programme.isLive = true
                            "premiere" -> programme.isPremiere = true
                            "previously-shown" -> programme.previouslyShown = true
                        }
                        depth == 4 && programme != null && path[2] == "rating" && token.name == "value" -> {
                            text = StringBuilder()
                            leafAttributes = token.attributes
                        }
                        depth == 3 && channelNames != null -> when (token.name) {
                            "display-name" -> {
                                text = StringBuilder()
                                leafAttributes = token.attributes
                            }
                            "icon" -> channelIcon = token.attributes["src"]
                        }
                    }
                }
                is Token.End -> {
                    val depth = path.size
                    val captured = text?.toString()?.trim()
                    when {
                        depth == 2 && token.name == "programme" && programme != null -> {
                            val p = programme
                            programme = null
                            records++
                            if (records > maxRecords) return XmltvParseResult(false, LimitKind.RECORD_COUNT, records - 1)
                            onRecord(
                                XmltvRecord.Programme(
                                    channel = p.attributes["channel"]?.trim(),
                                    start = p.attributes["start"],
                                    stop = p.attributes["stop"],
                                    titles = p.titles,
                                    subTitles = p.subTitles,
                                    descriptions = p.descriptions,
                                    categories = p.categories,
                                    iconSources = p.icons,
                                    episodeNumbers = p.episodeNumbers,
                                    rating = p.rating,
                                    isNew = p.isNew,
                                    isLive = p.isLive,
                                    isPremiere = p.isPremiere,
                                    previouslyShown = p.previouslyShown,
                                    language = p.language,
                                ),
                            )
                        }
                        depth == 2 && token.name == "channel" && channelNames != null -> {
                            records++
                            if (records > maxRecords) return XmltvParseResult(false, LimitKind.RECORD_COUNT, records - 1)
                            onRecord(XmltvRecord.Channel(channelId?.trim(), channelNames, channelIcon))
                            channelNames = null
                        }
                        captured != null && programme != null && depth == 3 -> {
                            val lang = leafAttributes["lang"]
                            if (captured.isNotEmpty()) {
                                when (token.name) {
                                    "title" -> programme.titles.add(LangText(captured, lang))
                                    "sub-title" -> programme.subTitles.add(LangText(captured, lang))
                                    "desc" -> programme.descriptions.add(LangText(captured, lang))
                                    "category" -> programme.categories.add(LangText(captured, lang))
                                    "episode-num" -> programme.episodeNumbers.add(leafAttributes["system"] to captured)
                                    "language" -> programme.language = captured
                                }
                            }
                            text = null
                        }
                        captured != null && programme != null && depth == 4 && token.name == "value" -> {
                            if (captured.isNotEmpty()) programme.rating = programme.ratingSystem to captured
                            text = null
                        }
                        captured != null && channelNames != null && depth == 3 && token.name == "display-name" -> {
                            if (captured.isNotEmpty()) channelNames.add(LangText(captured, leafAttributes["lang"]))
                            text = null
                        }
                    }
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                }
            }
        }
    }
}
