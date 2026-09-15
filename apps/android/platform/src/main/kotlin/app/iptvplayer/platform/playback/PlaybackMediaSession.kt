package app.iptvplayer.platform.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Publishes the player to the system as a Media3 [MediaSession] (ARCHITECTURE.md §9, PLATFORM_STRATEGY.md): hardware and
 * Bluetooth media keys, the assistant ("pause") and system media controls reach the app while the player is open.
 *
 * Commands from the system go through [Media3PlaybackController] so the shared state machine stays in charge (a system
 * "play" after an error is ignored like a remote key would be). Seeking is offered for movies only; "next"/"previous"
 * switch channels when [onSkip] is given. The session carries the title and subtitle of the [PlaybackRequest] only:
 * Media3 does not send a media item's URI to other processes, and a device test checks that no stream address or
 * credential appears in the system's session dump (SECURITY.md §4).
 */
@OptIn(UnstableApi::class)
class PlaybackMediaSession(
    context: Context,
    private val controller: Media3PlaybackController,
    private val onSkip: ((Int) -> Unit)? = null,
) : AutoCloseable {
    private val session: MediaSession = MediaSession.Builder(context, SessionPlayer())
        // Each player screen owns a session; ids must be unique while an old screen is still being disposed.
        .setId("player-${nextId.incrementAndGet()}")
        .build()

    /** Token for tests and system integrations. */
    val token get() = session.token

    override fun close() {
        session.release()
    }

    private inner class SessionPlayer : ForwardingSimpleBasePlayer(controller.player) {
        override fun getState(): State {
            val state = super.getState()
            val commands = state.availableCommands.buildUpon()
                .removeAll(Player.COMMAND_STOP, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .apply {
                    if (controller.isLive) {
                        removeAll(
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_SEEK_BACK,
                            Player.COMMAND_SEEK_FORWARD,
                            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        )
                    }
                    if (onSkip != null) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
                }
                .build()
            return state.buildUpon().setAvailableCommands(commands).build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) controller.play() else controller.pause()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onSkip?.invoke(+1)
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onSkip?.invoke(-1)
                else -> if (!controller.isLive && positionMs >= 0) controller.seekTo(positionMs.milliseconds)
            }
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        val nextId = AtomicInteger()
    }
}
