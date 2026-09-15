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
            "get_vod_categories", "get_series_categories", "get_vod_streams", "get_series" -> "[]"
            else -> "[]"
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
