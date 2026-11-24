package systems.hiraeth.dxgarden

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import org.libsdl.app.SDLActivity

/**
 * DX Garden's activity: SDL's activity, with SDL linked statically into
 * libmain.so, so there is only one native library to load.
 *
 * Until the Compose interface (A4), the only Android views over the globe
 * are the place label under the crosshair, set from native code, and the
 * build number in the bottom-left corner.
 */
class MainActivity : SDLActivity() {
    private var label: TextView? = null

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Playback.init(this)
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

        // The build number, small, bottom left, clear of the navigation bar
        // and any display cutout, so builds can be told apart on the phone.
        val info = packageManager.getPackageInfo(packageName, 0)
        val build = TextView(this).apply {
            text = getString(R.string.build_label, info.versionName, info.longVersionCode.toInt())
            setTextColor(Color.argb(0xB0, 0xFF, 0xFF, 0xFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setShadowLayer(3 * dp, 0f, 1 * dp, Color.argb(0xC0, 0, 0, 0))
            isClickable = false
            isFocusable = false
        }
        overlay.addView(
            build,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )
        val margin = (8 * dp).toInt()
        overlay.setOnApplyWindowInsetsListener { _, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (build.layoutParams as FrameLayout.LayoutParams).setMargins(
                i.left + margin, 0, 0, i.bottom + margin / 2
            )
            build.requestLayout()
            insets
        }
        tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.translationY = v.height / 2f + 26 * dp
        }
        mLayout?.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        label = tv
    }

    override fun onDestroy() {
        // The player lives in this activity's native thread, so when the
        // activity goes for good (swiped away from Recents) playback stops
        // with it. Not on a configuration change, which we handle ourselves.
        if (!isChangingConfigurations) Playback.release()
        super.onDestroy()
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

    /** From native code (any thread): what's playing, for the media session. */
    @Suppress("unused")
    fun playbackUpdate(state: Int, station: String, detail: String, place: String) {
        Playback.update(state, station, detail, place)
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
