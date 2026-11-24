package systems.hiraeth.dxgarden

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.libsdl.app.SDLActivity

/**
 * DX Garden's activity: SDL's activity (the globe, drawn in native code, with
 * SDL linked statically into libmain.so), with the Compose interface over it.
 *
 * SDL's activity is a plain Activity, so this class provides what Compose
 * needs from an AndroidX activity itself: a lifecycle, saved state, a view
 * model store and the back dispatcher, which is tied to Android's predictive
 * back (OnBackInvokedDispatcher).
 */
class MainActivity : SDLActivity(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val backDispatcher = OnBackPressedDispatcher(null)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun onCreate(savedInstanceState: Bundle?) {
        savedState.performAttach()
        savedState.performRestore(savedInstanceState)
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        backDispatcher.setOnBackInvokedDispatcher(onBackInvokedDispatcher)
        Prefs.init(this)
        Playback.init(this)
        val info = packageManager.getPackageInfo(packageName, 0)
        AppInfo.version = info.versionName ?: ""
        AppInfo.build = info.longVersionCode.toInt()

        val compose = ComposeView(this).apply {
            setContent { DxgTheme { DxgApp() } }
        }
        for (v in listOf(window.decorView, compose)) {
            v.setViewTreeLifecycleOwner(this)
            v.setViewTreeSavedStateRegistryOwner(this)
            v.setViewTreeViewModelStoreOwner(this)
            v.setViewTreeOnBackPressedDispatcherOwner(this)
        }
        // Over SDL's surface. Touches the interface doesn't use (anywhere on
        // the globe) fall through to the surface below.
        mLayout?.addView(
            compose,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedState.performSave(outState)
    }

    override fun onDestroy() {
        // The player lives in this activity's native thread, so when the
        // activity goes for good (swiped away from Recents) playback stops
        // with it. Not on a configuration change, which we handle ourselves.
        if (!isChangingConfigurations) Playback.release()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (!isChangingConfigurations) store.clear()
        super.onDestroy()
    }

    /** From native code (any thread): the place under the crosshair. */
    @Suppress("unused")
    fun setGlobeLabel(place: Int, name: String, detail: String) {
        runOnUiThread {
            UiState.labelPlace = place
            UiState.labelName = name
            UiState.labelDetail = detail
        }
    }

    /** From native code (SDL's thread): the library has loaded. */
    @Suppress("unused")
    fun libraryReady(stations: Int) {
        Native.command(Native.CMD_PREBUFFER, Prefs.bufferKb)
        runOnUiThread {
            UiState.stationCount = stations
            UiState.ready = true
        }
    }

    /** From native code (any thread): what's playing. */
    @Suppress("unused")
    fun playbackUpdate(state: Int, station: Int, name: String, detail: String, place: String) {
        Playback.update(state, name, detail, place)
        runOnUiThread { UiState.playback(state, station, name, detail, place) }
    }

    /** From native code: a light tick when the crosshair tunes to a place. */
    @Suppress("unused")
    fun hapticTick() {
        runOnUiThread {
            if (Prefs.haptics) mSurface?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
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
