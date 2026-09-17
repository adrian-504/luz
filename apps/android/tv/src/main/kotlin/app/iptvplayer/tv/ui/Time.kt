package app.iptvplayer.tv.ui

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/** A time of day the way the television's own settings write it: "21:30" or "9:30 PM". */
fun shortTime(instant: Instant): String = TIME.format(java.time.Instant.ofEpochSecond(instant.epochSeconds).atZone(ZoneId.systemDefault()))

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
