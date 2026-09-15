package app.iptvplayer.tv.developer

import app.iptvplayer.platform.playback.PlaybackRequest

/** A test stream offered in debug builds (Settings → Developer). Release builds provide none. */
class DeveloperStream(val id: String, val label: String, val request: () -> PlaybackRequest)
