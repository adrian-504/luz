package app.iptvplayer.tv.companion

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/** Whether the phone companion is on, and where a phone finds it. */
data class CompanionStatus(val on: Boolean, val address: String?, val failed: Boolean = false)

/**
 * The phone companion as the rest of the app sees it (ADR-0050): switched on and off in Settings, remembered across
 * restarts, and serving while Luz runs. Off until the viewer switches it on.
 */
class PhoneCompanion(private val context: Context, private val preferences: SharedPreferences, private val library: CompanionLibrary) {
    val pairing = CompanionPairing(preferences, System::currentTimeMillis)
    private val main = Handler(Looper.getMainLooper())
    private var server: CompanionServer? = null
    private val mutableStatus = MutableStateFlow(CompanionStatus(on = false, address = null))

    val status: StateFlow<CompanionStatus> = mutableStatus

    /** Starts serving if the viewer left the companion on. */
    fun resume() {
        if (preferences.getBoolean(KEY_ON, false)) start()
    }

    fun setOn(on: Boolean) {
        preferences.edit { putBoolean(KEY_ON, on) }
        if (on) start() else stop()
    }

    /** The address to show, read again each time: the router can hand the TV a new one. */
    fun refresh() {
        if (server != null) mutableStatus.value = mutableStatus.value.copy(address = address())
    }

    @Synchronized
    private fun start() {
        if (server == null) {
            server = try {
                CompanionServer(PORT, ::hosts, ::page, pairing, library, ::tvName) { change -> main.post(change) }
            } catch (_: IOException) {
                null
            }
        }
        mutableStatus.value = CompanionStatus(on = true, address = address(), failed = server == null)
    }

    @Synchronized
    private fun stop() {
        server?.close()
        server = null
        mutableStatus.value = CompanionStatus(on = false, address = null)
    }

    /**
     * The TV's own name from its system settings, as phones show it ("Luz · Living room"); null when the TV has none or
     * it is a model code, which would mean nothing to the viewer.
     */
    fun tvName(): String? {
        val name = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.trim()?.take(MAX_NAME)
        return name?.takeUnless { it.isEmpty() || MODEL_CODE.matches(it) }
    }

    private fun page(): ByteArray = context.assets.open(PAGE).use { it.readBytes() }

    private fun hosts(): Set<String> = setOfNotNull(address(), "127.0.0.1:$PORT", "localhost:$PORT")

    /** The TV's address on the home network (the first private IPv4 address of a running interface), with the port. */
    private fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
            ?.let { "$it:$PORT" }
    }.getOrNull()

    companion object {
        /** Fixed, so a phone that paired once finds Luz again. */
        const val PORT = 8765
        private const val KEY_ON = "companion_on"
        private const val PAGE = "companion/index.html"
        private const val MAX_NAME = 40
        private val MODEL_CODE = Regex("^[A-Z0-9_-]{6,}$")
    }
}
