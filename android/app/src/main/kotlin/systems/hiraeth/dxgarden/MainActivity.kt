package systems.hiraeth.dxgarden

import org.libsdl.app.SDLActivity

/**
 * DX Garden's activity: SDL's activity, with SDL linked statically into
 * libmain.so, so there is only one native library to load.
 */
class MainActivity : SDLActivity() {
    override fun getLibraries(): Array<String> = arrayOf("main")
}
