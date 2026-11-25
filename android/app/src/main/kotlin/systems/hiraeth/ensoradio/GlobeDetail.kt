package systems.hiraeth.ensoradio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Full detail for the globe: the closest zoom level of the day and night
 * imagery (tools/make_earth.py --detail-from 6), left out of the APK to keep
 * it small. Downloaded only when the user asks, from the GitHub release that
 * holds these exact files, into globe/ in the files folder. Each file is
 * fetched into a .part file (so a stopped download carries on where it
 * left off), checked against its size and SHA-256, and only then renamed;
 * the native globe (CMD_GLOBE_DETAIL) reads a pack only under its final
 * name, so it never sees a partial or damaged one.
 */
object GlobeDetail {
    private const val TAG = "EnsoRadio"

    /** Where the files are published. A new set of files gets a new tag. */
    private const val BASE_URL = "https://github.com/hiraethsystems/Enso-Radio/releases/download/globe-1/"

    class Pack(val name: String, val size: Long, val sha256: String)

    /** Must match build-earth-detail/ (Gradle checks when it's there). */
    val PACKS = listOf(
        Pack("earth-day-detail.pack", 168_943_291L, "ba1f3318ad7d04b1bb0c70afb321fde5d0a936a14533b0f6140ca4d2810e3cb3"),
        Pack("earth-night-detail.pack", 85_416_727L, "97af29bda3612f8d0b6dc7995f00f616d1a1c03d1f821fbf6c5078c7e5355744"),
    )
    val TOTAL: Long = PACKS.sumOf { it.size }

    enum class State { ABSENT, PARTIAL, DOWNLOADING, CHECKING, INSTALLED, FAILED }

    var state by mutableStateOf(State.ABSENT)
        private set
    /** Bytes on the phone so far (finished files and .part files). */
    var have by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private lateinit var dir: File
    private lateinit var app: Context
    private var baseUrl = BASE_URL
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stop = false
    private var thread: Thread? = null

    fun init(context: Context) {
        if (::dir.isInitialized) return
        app = context.applicationContext
        dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "globe")
        refresh()
    }

    /** Debug builds only (MainActivity): fetch from a test server instead. */
    fun overrideBaseUrl(url: String) {
        baseUrl = if (url.endsWith("/")) url else "$url/"
        Log.i(TAG, "globe detail: test server $baseUrl")
    }

    private fun final(p: Pack) = File(dir, p.name)
    private fun part(p: Pack) = File(dir, p.name + ".part")
    private fun installed(p: Pack) = final(p).length() == p.size

    private fun onDisk(): Long = PACKS.sumOf { if (installed(it)) it.size else part(it).length() }

    private fun refresh() {
        have = onDisk()
        state = when {
            PACKS.all { installed(it) } -> State.INSTALLED
            have > 0 -> State.PARTIAL
            else -> State.ABSENT
        }
    }

    fun start() {
        if (thread != null) return
        stop = false
        error = null
        state = State.DOWNLOADING
        thread = Thread({ run() }, "enso_globe_detail").also { it.start() }
    }

    /** Stops after the current block; what's downloaded stays for next time. */
    fun pause() {
        stop = true
    }

    /** Deletes the detail files (and any partial download). */
    fun remove() {
        pause()
        thread?.join(5000)
        for (p in PACKS) {
            final(p).delete()
            part(p).delete()
        }
        Native.command(Native.CMD_GLOBE_DETAIL, 0)
        error = null
        refresh()
        Log.i(TAG, "globe detail: removed")
    }

    private fun run() {
        val power = app.getSystemService(PowerManager::class.java)
        val wake = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnsoRadio:globe")
        wake?.acquire(60 * 60 * 1000L)
        var failure: String? = null
        try {
            dir.mkdirs()
            val need = TOTAL - onDisk()
            val free = StatFs(dir.path).availableBytes
            if (need + 50_000_000L > free) {
                failure = "Not enough free space: it needs ${mb(need)} and the phone has ${mb(free)} free."
            } else {
                for (p in PACKS) {
                    if (stop) break
                    if (!installed(p)) fetch(p)
                }
            }
        } catch (e: IOException) {
            failure = "The download stopped (${e.message ?: e.javaClass.simpleName}). " +
                "Try again to carry on where it left off."
            Log.w(TAG, "globe detail: $e")
        } finally {
            if (wake?.isHeld == true) wake.release()
        }
        val done = PACKS.all { installed(it) }
        if (done) Native.command(Native.CMD_GLOBE_DETAIL, 0)
        main.post {
            thread = null
            refresh()
            if (failure != null && !done) {
                error = failure
                state = State.FAILED
            }
            Log.i(TAG, "globe detail: ${state.name.lowercase()}, ${have / 1_000_000} MB on the phone")
        }
    }

    private fun fetch(p: Pack) {
        val part = part(p)
        if (part.length() > p.size) part.delete()
        if (part.length() < p.size) download(p, part)
        if (stop || part.length() != p.size) return
        main.post { state = State.CHECKING }
        if (sha256(part) != p.sha256) {
            part.delete()
            throw IOException("${p.name} arrived damaged and was deleted")
        }
        if (!part.renameTo(final(p))) throw IOException("could not save ${p.name}")
        Log.i(TAG, "globe detail: ${p.name} checked and saved")
        main.post { state = State.DOWNLOADING }
    }

    private fun download(p: Pack, part: File) {
        var from = part.length()
        val conn = URL(baseUrl + p.name).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "EnsoRadio/${AppInfo.version} (Android)")
        if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
        Log.i(TAG, "globe detail: GET ${p.name} from $from")
        try {
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {}
                HttpURLConnection.HTTP_OK -> from = 0 // the server sent it all
                else -> throw IOException("the server said $code")
            }
            conn.inputStream.use { input ->
                FileOutputStream(part, from > 0).use { out ->
                    if (from == 0L) out.channel.truncate(0)
                    val buf = ByteArray(256 * 1024)
                    var got = from
                    var shown = 0L
                    while (!stop) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - shown >= 1_000_000L) {
                            shown = got
                            val total = onDisk()
                            main.post { have = total }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val total = onDisk()
        main.post { have = total }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun mb(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
}
