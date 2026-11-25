package systems.hiraeth.ensoradio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** What native code tells the interface. Changed on the main thread only. */
object UiState {
    var ready by mutableStateOf(false)
    var stationCount by mutableIntStateOf(0)

    /** The place under the crosshair (or playing), and its label. */
    var labelPlace by mutableIntStateOf(-1)
    var labelName by mutableStateOf("")
    var labelDetail by mutableStateOf("")

    var playState by mutableIntStateOf(Playback.STOPPED)
    var playStation by mutableIntStateOf(-1)
    var playName by mutableStateOf("")
    var playDetail by mutableStateOf("")
    var playPlace by mutableStateOf("")

    /** Goes up when favourites or recent may have changed. */
    var listsVersion by mutableIntStateOf(0)

    val active: Boolean get() = playState != Playback.STOPPED

    fun playback(state: Int, station: Int, name: String, detail: String, place: String) {
        if (station != playStation) listsVersion++
        playState = state
        playStation = station
        playName = name
        playDetail = detail
        playPlace = place
    }
}

/** The interface's own settings (the player's live in config.ini). */
object Prefs {
    private lateinit var sp: SharedPreferences

    const val COLOURS_DXG = "dxg"
    const val COLOURS_PHONE = "phone"
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    var colours by mutableStateOf(COLOURS_DXG)
        private set
    var theme by mutableStateOf(THEME_SYSTEM)
        private set
    var haptics by mutableStateOf(true)
        private set
    /** KB buffered before a station starts playing. */
    var bufferKb by mutableIntStateOf(64)
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        colours = sp.getString("colours", COLOURS_DXG) ?: COLOURS_DXG
        theme = sp.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        haptics = sp.getBoolean("haptics", true)
        bufferKb = sp.getInt("buffer_kb", 64)
    }

    fun changeColours(v: String) { colours = v; sp.edit().putString("colours", v).apply() }
    fun changeTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun changeHaptics(v: Boolean) { haptics = v; sp.edit().putBoolean("haptics", v).apply() }
    fun changeBufferKb(v: Int) {
        bufferKb = v
        sp.edit().putInt("buffer_kb", v).apply()
        Native.command(Native.CMD_PREBUFFER, v)
    }
}

/** Stops playing after a while. Main thread. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    /** SystemClock.elapsedRealtime() when it stops, or 0 when off. */
    var endsAt by mutableLongStateOf(0L)
        private set
    var minutes by mutableIntStateOf(0)
        private set

    private val fire = Runnable {
        endsAt = 0
        minutes = 0
        Native.command(Playback.CMD_STOP, 0)
    }

    fun set(min: Int) {
        handler.removeCallbacks(fire)
        minutes = min
        if (min <= 0) {
            endsAt = 0
            return
        }
        endsAt = SystemClock.elapsedRealtime() + min * 60_000L
        handler.postAtTime(fire, SystemClock.uptimeMillis() + min * 60_000L)
    }
}
