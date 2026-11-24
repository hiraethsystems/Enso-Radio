package systems.hiraeth.dxgarden

import android.content.pm.ApplicationInfo
import org.libsdl.app.SDLActivity

/**
 * DX Garden's activity: SDL's activity, with SDL linked statically into
 * libmain.so, so there is only one native library to load.
 */
class MainActivity : SDLActivity() {
    override fun getLibraries(): Array<String> = arrayOf("main")

    /**
     * The Linux app's test options, passed as intent extras for headless
     * checks over adb, e.g.
     *   adb shell am start -n systems.hiraeth.dxgarden/.MainActivity \
     *       -e play https://example/stream -e seconds 20
     * Only in debuggable builds, so other apps can't drive a release build.
     */
    override fun getArguments(): Array<String> {
        val extras = intent?.extras ?: return emptyArray()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return emptyArray()
        val args = mutableListOf<String>()
        for (key in listOf("play", "seconds", "script", "screenshot", "frames")) {
            extras.getString(key)?.let { args += listOf("--$key", it) }
        }
        if (extras.getBoolean("verbose", false)) args += "--verbose"
        return args.toTypedArray()
    }
}
