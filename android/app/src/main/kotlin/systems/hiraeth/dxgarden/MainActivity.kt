package systems.hiraeth.dxgarden

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.libsdl.app.SDLActivity

/**
 * DX Garden's activity: SDL's activity, with SDL linked statically into
 * libmain.so, so there is only one native library to load.
 *
 * Until the Compose interface (A4), the only Android view over the globe is
 * the place label under the crosshair, set from native code.
 */
class MainActivity : SDLActivity() {
    private var label: TextView? = null

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(4 * dp, 0f, 1 * dp, Color.argb(0xE0, 0, 0, 0))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        // Centred, just below the crosshair (which is in the middle of the
        // screen). The overlay passes touches through to the globe.
        val overlay = FrameLayout(this).apply { isClickable = false; isFocusable = false }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        overlay.addView(tv, lp)
        tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.translationY = v.height / 2f + 26 * dp
        }
        mLayout?.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        label = tv
    }

    /** From native code (any thread): the place under the crosshair. */
    @Suppress("unused")
    fun setGlobeLabel(place: String, detail: String) {
        runOnUiThread {
            val tv = label ?: return@runOnUiThread
            if (place.isEmpty()) {
                tv.visibility = View.GONE
            } else {
                tv.text = if (detail.isEmpty()) place else "$place\n$detail"
                tv.visibility = View.VISIBLE
            }
        }
    }

    /** From native code: a light tick when the crosshair tunes to a place. */
    @Suppress("unused")
    fun hapticTick() {
        runOnUiThread { mSurface?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

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
        for (key in listOf("play", "seconds", "script", "screenshot", "frames", "goto", "time")) {
            extras.getString(key)?.let { args += listOf("--$key", it) }
        }
        if (extras.getBoolean("verbose", false)) args += "--verbose"
        return args.toTypedArray()
    }
}
