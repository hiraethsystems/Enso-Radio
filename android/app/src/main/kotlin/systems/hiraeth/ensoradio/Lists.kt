package systems.hiraeth.ensoradio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The sheet below the place row: tabs and their lists. */
@Composable
fun SheetBody(modifier: Modifier, onPlace: () -> Unit) {
    Column(modifier) {
        // Scrollable, so "Favourites" fits in a narrow panel or at large
        // font sizes.
        PrimaryScrollableTabRow(selectedTabIndex = Nav.tab, containerColor = Color.Transparent, edgePadding = 8.dp) {
            listOf("Here", "Browse", "Favourites", "Recent").forEachIndexed { i, t ->
                Tab(selected = Nav.tab == i, onClick = { Nav.tab = i }, text = { Text(t, maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f)) {
            if (!UiState.ready) return@Box
            when (Nav.tab) {
                Nav.TAB_HERE -> HereList()
                Nav.TAB_BROWSE -> BrowseList(onPlace)
                Nav.TAB_FAVOURITES -> UserList(Native.FAVOURITES, "Star a station to keep it here.")
                Nav.TAB_RECENT -> UserList(Native.RECENT, "Stations you play appear here.")
            }
        }
    }
}

fun playStation(si: Int) = Native.command(Native.CMD_PLAY_STATION, si)

fun showPlace(pl: Int) {
    Native.command(Native.CMD_SHOW_PLACE, pl)
    Nav.tab = Nav.TAB_HERE
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The tuned place's stations. */
@Composable
private fun HereList() {
    val pl = UiState.labelPlace
    if (pl < 0) {
        Empty("Turn the globe: the place under the ring plays, and its stations are listed here.")
        return
    }
    val stations = remember(pl) { Native.placeStations(pl) }
    val state = remember(pl) { androidx.compose.foundation.lazy.LazyListState() }
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        items(stations.size, key = { stations[it] }) { i -> StationRow(stations[i], showPlace = false) }
    }
}

/** Countries then their places, or genres then their stations. */
@Composable
private fun BrowseList(onPlace: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (Nav.browseOpen < 0) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = Nav.browseMode == Native.COUNTRIES,
                    onClick = { Nav.browseMode = Native.COUNTRIES },
                    label = { Text("Countries") },
                )
                FilterChip(
                    selected = Nav.browseMode == Native.GENRES,
                    onClick = { Nav.browseMode = Native.GENRES },
                    label = { Text("Genres") },
                )
            }
            val mode = Nav.browseMode
            val names = remember(mode) { Native.groupNames(mode) }
            val counts = remember(mode) { Native.groupCounts(mode) }
            val state = remember(mode) { androidx.compose.foundation.lazy.LazyListState() }
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(names.size) { i ->
                    ListItem(
                        headlineContent = { Text(names[i].ifEmpty { "(none)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stationsText(counts[i])) },
                        trailingContent = { Icon(Icons.Chevron, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { Nav.browseOpen = i },
                    )
                }
            }
        } else {
            val mode = Nav.browseMode
            val gi = Nav.browseOpen
            val name = remember(mode, gi) { Native.groupNames(mode).getOrElse(gi) { "" } }
            val items = remember(mode, gi) { Native.groupItems(mode, gi) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Nav.browseOpen = -1 }) {
                    Icon(Icons.Back, if (mode == Native.COUNTRIES) "All countries" else "All genres")
                }
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
                if (mode == Native.COUNTRIES) {
                    items(items.size, key = { items[it] }) { i ->
                        PlaceRow(items[i], showCountry = false, onClick = { showPlace(items[i]); onPlace() })
                    }
                } else {
                    items(items.size, key = { items[it] }) { i -> StationRow(items[i], showPlace = true) }
                }
            }
        }
    }
}

@Composable
private fun UserList(which: Int, empty: String) {
    val stations = remember(UiState.listsVersion, which) { Native.list(which) }
    if (stations.isEmpty()) {
        Empty(empty)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(stations.size, key = { stations[it] }) { i -> StationRow(stations[i], showPlace = true) }
    }
}

fun stationsText(n: Int) = "$n station${if (n == 1) "" else "s"}"

/** A station: tap to play it. Shows the genre, and the place if asked. */
@Composable
fun StationRow(si: Int, showPlace: Boolean) {
    val s = remember(si) { Lib.station(si) }
    val playing = UiState.playStation == si && UiState.active
    val sub = listOfNotNull(
        if (showPlace) Lib.placeLabel(s.place).ifEmpty { null } else null,
        s.genre.ifEmpty { null },
    ).joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(
                s.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else null,
                color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = if (sub.isNotEmpty()) {
            { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = {
            Box(Modifier.size(10.dp).background(if (playing) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
        },
        trailingContent = { FavouriteButton(si) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClickLabel = "Play", role = Role.Button) { playStation(si) }
            .semantics { if (playing) stateDescription = "Playing" },
    )
}

@Composable
fun PlaceRow(pl: Int, showCountry: Boolean, onClick: () -> Unit) {
    val p = remember(pl) { Lib.place(pl) }
    ListItem(
        headlineContent = {
            Text(
                if (p.countryWide) "Country-wide" else if (showCountry) p.label else p.name,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(stationsText(p.stations)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClickLabel = "Go there and play", onClick = onClick),
    )
}

/** A star that adds or removes a favourite. */
@Composable
fun FavouriteButton(si: Int) {
    var fav by remember(si, UiState.listsVersion) { mutableStateOf(Native.isFavourite(si)) }
    IconButton(
        onClick = {
            fav = Native.toggleFavourite(si)
            UiState.listsVersion++
        },
        modifier = Modifier.semantics {
            contentDescription = if (fav) "Remove from favourites" else "Add to favourites"
        },
    ) {
        Icon(
            if (fav) Icons.Star else Icons.StarOutline, null,
            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
