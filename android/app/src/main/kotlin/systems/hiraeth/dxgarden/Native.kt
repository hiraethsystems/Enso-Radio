package systems.hiraeth.dxgarden

import android.util.LruCache

/**
 * The library and player as the interface sees them: JNI functions in
 * libmain (ui_jni.c). The library is read-only once loaded, so these may be
 * called from any thread; before it has loaded they answer empty.
 */
object Native {
    // Commands (app.h). 1-5 are Playback's.
    const val CMD_PLAY_STATION = 10
    const val CMD_SHOW_PLACE = 11
    const val CMD_PREBUFFER = 12

    const val COUNTRIES = 0
    const val GENRES = 1
    const val FAVOURITES = 0
    const val RECENT = 1

    @JvmStatic external fun stationCount(): Int
    /** name, country, station count, "1" if Country-wide */
    @JvmStatic external fun place(place: Int): Array<String>
    @JvmStatic external fun placeStations(place: Int): IntArray
    /** name, genre, city, country, url, place index, notes */
    @JvmStatic external fun station(station: Int): Array<String>
    /** [number of places, places..., stations...] */
    @JvmStatic external fun search(query: String, max: Int): IntArray
    @JvmStatic external fun groupNames(which: Int): Array<String>
    @JvmStatic external fun groupCounts(which: Int): IntArray
    @JvmStatic external fun groupItems(which: Int, group: Int): IntArray
    @JvmStatic external fun list(which: Int): IntArray
    @JvmStatic external fun isFavourite(station: Int): Boolean
    @JvmStatic external fun toggleFavourite(station: Int): Boolean
    @JvmStatic external fun command(cmd: Int, arg: Int)
    @JvmStatic external fun setGlobeLeft(px: Int)
    /** codec, rate, channels, kbps, stream's name, website, title, buffer %, error */
    @JvmStatic external fun status(): Array<String>
}

class PlaceInfo(val index: Int, val name: String, val country: String, val stations: Int, val countryWide: Boolean) {
    /** "Swansea, Wales", or just the country for a Country-wide place. */
    val label: String
        get() = when {
            countryWide && country.isNotEmpty() -> country
            country.isEmpty() -> name
            else -> "$name, $country"
        }
}

class StationInfo(
    val index: Int, val name: String, val genre: String, val city: String,
    val country: String, val url: String, val place: Int, val notes: String,
)

/** Cached rows, so long lists don't cross JNI for every frame. */
object Lib {
    private val stations = LruCache<Int, StationInfo>(2000)
    private val places = LruCache<Int, PlaceInfo>(1000)

    fun station(i: Int): StationInfo = stations.get(i) ?: Native.station(i).let {
        StationInfo(i, it[0], it[1], it[2], it[3], it[4], it[5].toIntOrNull() ?: -1, it[6])
    }.also { stations.put(i, it) }

    fun place(i: Int): PlaceInfo = places.get(i) ?: Native.place(i).let {
        PlaceInfo(i, it[0], it[1], it[2].toIntOrNull() ?: 0, it[3] == "1")
    }.also { places.put(i, it) }

    fun placeLabel(i: Int): String = if (i >= 0) place(i).label else ""
}
