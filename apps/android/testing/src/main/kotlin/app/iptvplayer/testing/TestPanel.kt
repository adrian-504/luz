package app.iptvplayer.testing

import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * A synthetic Xtream Codes panel for device tests and debug builds: six test channels in three categories, a guide
 * generated around the current time, and an M3U export. Only [USERNAME] / [PASSWORD] (canary values) are accepted.
 * Channel 999 is listed but its stream does not exist, for error handling.
 */
object TestPanel {
    const val USERNAME = "canary-user"
    const val PASSWORD = "CANARY-PW-7f3a9c-DO-NOT-LOG"

    enum class Target { TS, HLS }

    private data class TestChannel(val id: Int, val name: String, val category: String, val epgId: String?)

    private val categories = listOf("1" to "News", "2" to "Sports", "3" to "Documentaries & Kids")

    // Library: synthetic titles only. Movies and episodes play the 30 s HLS test video.
    private val vodCategories = listOf("11" to "Test Films", "12" to "Test Shorts")
    private val movies = listOf(
        Triple(5001, "Test Movie One", "11"),
        Triple(5002, "Test Movie Two", "11"),
        Triple(5003, "Test Short", "12"),
    )
    private val seriesCategories = listOf("21" to "Test Shows")
    private const val SERIES_ID = 7001
    private val channels = listOf(
        TestChannel(101, "Test News HD", "1", "news.test"),
        TestChannel(102, "Test News 2", "1", null),
        TestChannel(201, "Test Sports", "2", "sports.test"),
        TestChannel(202, "Test Sports Extra", "2", null),
        TestChannel(301, "Test Documentaries", "3", "docs.test"),
        TestChannel(302, "Test Kids", "3", null),
        TestChannel(999, "Test Offline Channel", "3", null),
    )

    private fun params(query: String): Map<String, String> = query.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun authorized(query: String): Boolean = params(query).let { it["username"] == USERNAME && it["password"] == PASSWORD }

    fun api(query: String): String {
        if (!authorized(query)) return """{"user_info":{"auth":0}}"""
        return when (params(query)["action"]) {
            null -> """{"user_info":{"username":"$USERNAME","auth":1,"status":"Active","exp_date":"${Instant.now().plus(
                365,
                ChronoUnit.DAYS,
            ).epochSecond}","is_trial":"0","active_cons":"0","max_connections":"1","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"127.0.0.1","port":"80","server_protocol":"http","timezone":"UTC"}}"""
            "get_live_categories" -> categories.joinToString(
                ",",
                "[",
                "]",
            ) { (id, name) -> """{"category_id":"$id","category_name":"$name","parent_id":0}""" }
            "get_live_streams" -> channels.withIndex().joinToString(",", "[", "]") { (index, c) ->
                """{"num":${index + 1},"name":"${c.name}","stream_type":"live","stream_id":${c.id},"stream_icon":"","epg_channel_id":${c.epgId?.let { "\"$it\"" } ?: "null"},"category_id":"${c.category}","tv_archive":0}"""
            }
            "get_short_epg" -> shortEpg(params(query)["stream_id"])
            "get_vod_categories" -> categoryJson(vodCategories)
            "get_vod_streams" -> movies.withIndex().joinToString(",", "[", "]") { (index, m) ->
                """{"num":${index + 1},"name":"${m.second}","stream_type":"movie","stream_id":${m.first},"stream_icon":"",""" +
                    """"rating":"7","added":"${Instant.now().epochSecond - index * 3600}","category_id":"${m.third}","container_extension":"m3u8"}"""
            }
            "get_series_categories" -> categoryJson(seriesCategories)
            "get_series" -> seriesListJson()
            "get_series_info" -> if (params(query)["series_id"] == SERIES_ID.toString()) seriesInfo() else EMPTY_SERIES_INFO
            else -> "[]"
        }
    }

    private fun categoryJson(list: List<Pair<String, String>>) =
        list.joinToString(",", "[", "]") { (id, name) -> """{"category_id":"$id","category_name":"$name","parent_id":0}""" }

    private fun seriesListJson() =
        """[{"num":1,"name":"Test Series","series_id":$SERIES_ID,"cover":"","plot":"A synthetic test series.",""" +
            """"genre":"Test","releaseDate":"2026-01-01","category_id":"21"}]"""

    private const val EMPTY_SERIES_INFO = """{"seasons":[],"info":{},"episodes":{}}"""

    /** `get_series_info` for the test series: season 1 with three episodes, season 2 with one. */
    private fun seriesInfo(): String {
        fun episode(id: Int, season: Int, number: Int) =
            """{"id":"$id","episode_num":$number,"title":"Test Episode $number","container_extension":"m3u8",""" +
                """"season":$season,"info":{"duration_secs":30}}"""
        return """{"seasons":[{"season_number":1,"name":"Season 1"},{"season_number":2,"name":"Season 2"}],""" +
            """"info":{"name":"Test Series"},""" +
            """"episodes":{"1":[${episode(8101, 1, 1)},${episode(8102, 1, 2)},${episode(8103, 1, 3)}],"2":[${episode(8201, 2, 1)}]}}"""
    }

    /** `/movie/{user}/{password}/{id}.m3u8` and `/series/...`: true when the login and title exist. */
    fun libraryTarget(route: String): Boolean {
        val kind = route.removePrefix("/").substringBefore('/')
        val parts = route.removePrefix("/$kind/").split('/')
        if (parts.size != 3 || parts[0] != USERNAME || URLDecoder.decode(parts[1], "UTF-8") != PASSWORD) return false
        val id = parts[2].substringBefore('.').toIntOrNull() ?: return false
        return when (kind) {
            "movie" -> movies.any { it.first == id }
            "series" -> id in setOf(8101, 8102, 8103, 8201)
            else -> false
        }
    }

    /** Per-channel guide (`get_short_epg`): 30-minute programmes from the current half hour, base64 titles as panels send them. */
    private fun shortEpg(streamId: String?): String {
        val channel = channels.firstOrNull { it.id.toString() == streamId } ?: return """{"epg_listings":[]}"""
        val start = Instant.now().epochSecond.let { it - it % 1800 }
        return (0 until 4).joinToString(",", """{"epg_listings":[""", "]}") { slot ->
            val from = start + slot * 1800
            val title = Base64.getEncoder().encodeToString("${channel.name} short guide ${slot + 1}".toByteArray())
            """{"id":"${channel.id}$slot","title":"$title","description":"","start_timestamp":"$from","stop_timestamp":"${from + 1800}"}"""
        }
    }

    fun m3u(query: String, base: String): String? {
        if (!authorized(query)) return null
        return buildString {
            append("#EXTM3U url-tvg=\"$base/xmltv.php?username=$USERNAME&password=$PASSWORD\"\n")
            for (c in channels) {
                val group = categories.first { it.first == c.category }.second
                append("#EXTINF:-1 tvg-id=\"${c.epgId.orEmpty()}\" group-title=\"$group\",${c.name}\n")
                append("$base/live/$USERNAME/$PASSWORD/${c.id}.ts\n")
            }
        }
    }

    /** Guide around now: 30-minute programmes from 2 h before to 6 h after; "Test Kids" is matched by display name. */
    fun xmltv(query: String): String? {
        if (!authorized(query)) return null
        val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS)
        val guideChannels = listOf(
            "news.test" to "Test News HD",
            "sports.test" to "Test Sports",
            "docs.test" to "Test Documentaries",
            "kids.test" to "Test Kids",
        )
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for ((id, name) in guideChannels) append("  <channel id=\"$id\"><display-name>$name</display-name></channel>\n")
            for ((id, name) in guideChannels) {
                for (slot in 0 until 16) {
                    val from = start.plus(slot * 30L, ChronoUnit.MINUTES)
                    append(
                        "  <programme start=\"${format.format(
                            from,
                        )}\" stop=\"${format.format(from.plus(30, ChronoUnit.MINUTES))}\" channel=\"$id\">",
                    )
                    append("<title>$name programme ${slot + 1}</title></programme>\n")
                }
            }
            append("</tv>\n")
        }
    }

    /** `/live/{user}/{password}/{id}.{ext}`: the stream to serve, or null for bad credentials or unknown channels. */
    fun liveTarget(route: String): Target? {
        val parts = route.removePrefix("/live/").split('/')
        if (parts.size != 3 || parts[0] != USERNAME || URLDecoder.decode(parts[1], "UTF-8") != PASSWORD) return null
        val id = parts[2].substringBefore('.').toIntOrNull() ?: return null
        if (id == 999 || channels.none { it.id == id }) return null
        return when (parts[2].substringAfter('.')) {
            "ts" -> Target.TS
            "m3u8" -> Target.HLS
            else -> null
        }
    }
}
