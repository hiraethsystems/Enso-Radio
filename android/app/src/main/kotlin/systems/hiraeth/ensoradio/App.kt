package systems.hiraeth.ensoradio

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Version and build, for About and the build tag (set by MainActivity). */
object AppInfo {
    var version = ""
    var build = 0
}

enum class Page { None, Search, NowPlaying, Settings, About }
enum class Anchor { Full, Half, Peek }

/** Where the interface is: the page over the globe, the sheet's tab, Browse. */
object Nav {
    var page by mutableStateOf(Page.None)
    var tab by mutableIntStateOf(TAB_HERE)
    var browseMode by mutableIntStateOf(Native.COUNTRIES)
    /** The country (index) or genre (index) open in Browse, or -1. */
    var browseOpen by mutableIntStateOf(-1)

    const val TAB_HERE = 0
    const val TAB_BROWSE = 1
    const val TAB_FAVOURITES = 2
    const val TAB_RECENT = 3
}

/** The bottom sheet: its offset from the top of its full height, in px. */
@Stable
class Sheet {
    var offset by mutableFloatStateOf(0f)
    var halfY by mutableFloatStateOf(0f)
    var peekY by mutableFloatStateOf(0f)
    var anchor by mutableStateOf(Anchor.Peek)
    var job: Job? = null
    var placed = false
    /** A drag or scroll moved the sheet in this gesture. */
    var moved = false

    fun y(a: Anchor) = when (a) {
        Anchor.Full -> 0f
        Anchor.Half -> halfY
        Anchor.Peek -> peekY
    }

    fun drag(d: Float): Float {
        val n = (offset + d).coerceIn(0f, peekY)
        val used = n - offset
        offset = n
        return used
    }

    fun target(velocity: Float): Anchor {
        val points = listOf(Anchor.Full to 0f, Anchor.Half to halfY, Anchor.Peek to peekY)
        if (abs(velocity) > 1200f) {
            return if (velocity < 0) points.filter { it.second < offset - 1 }.maxByOrNull { it.second }?.first ?: Anchor.Full
            else points.filter { it.second > offset + 1 }.minByOrNull { it.second }?.first ?: Anchor.Peek
        }
        return points.minBy { abs(it.second - offset) }.first
    }

    suspend fun animateTo(a: Anchor, velocity: Float = 0f) {
        anchor = a
        animate(offset, y(a), velocity, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f)) { v, _ ->
            offset = v
        }
    }
}

private val labelShadow = Shadow(Color(0xE0000000), Offset(0f, 2f), 8f)

@Composable
fun DxgApp() {
    val scope = rememberCoroutineScope()
    val sheet = remember { Sheet() }
    var backProgress by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
        if (landscape) LandscapeLayout(sheet) else PortraitLayout(sheet)

        AnimatedVisibility(
            Nav.page != Page.None,
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
        ) {
            // Shrinks a little with the back gesture, as Android's own pages do.
            val p = backProgress
            Surface(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 1f - 0.08f * p
                        scaleX = s
                        scaleY = s
                        clip = p > 0f
                        shape = RoundedCornerShape((28 * p).dp)
                    },
                color = MaterialTheme.colorScheme.surface,
            ) {
                when (Nav.page) {
                    Page.Search -> SearchPage(sheet)
                    Page.NowPlaying -> NowPlayingPage()
                    Page.Settings -> SettingsPage()
                    Page.About -> AboutPage()
                    Page.None -> {}
                }
            }
        }
    }

    // One back handler for everything, in order: the open page, an open
    // country or genre, the sheet. With nothing to go back from, Android's
    // own back (to the home screen, with its animation) takes over.
    val canGoBack = Nav.page != Page.None ||
        (Nav.tab == Nav.TAB_BROWSE && Nav.browseOpen >= 0 && sheet.anchor != Anchor.Peek) ||
        sheet.anchor != Anchor.Peek
    PredictiveBackHandler(enabled = canGoBack) { events ->
        val pageOpen = Nav.page != Page.None
        try {
            events.collect { if (pageOpen) backProgress = it.progress }
            backProgress = 0f
            when {
                Nav.page != Page.None -> Nav.page = Page.None
                Nav.tab == Nav.TAB_BROWSE && Nav.browseOpen >= 0 && sheet.anchor != Anchor.Peek -> Nav.browseOpen = -1
                else -> scope.launch { sheet.animateTo(Anchor.Peek) }
            }
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
}

/** Phones upright: the globe, a search bar on top, the sheet below. */
@Composable
private fun PortraitLayout(sheet: Sheet) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Native.setGlobeLeft(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density) + with(density) { 76.dp.toPx() }
        val sheetH = fullH - top
        val navBottom = WindowInsets.navigationBars.getBottom(density)
        var headerH by remember { mutableFloatStateOf(with(density) { 120.dp.toPx() }) }
        sheet.peekY = (sheetH - headerH - navBottom).coerceAtLeast(0f)
        // Half way: the crosshair (mid-screen) and its label stay in view.
        sheet.halfY = (sheetH - fullH * 0.42f).coerceIn(0f, sheet.peekY)
        LaunchedEffect(sheet.peekY, sheet.halfY) {
            if (sheet.job?.isActive != true) sheet.offset = sheet.y(sheet.anchor)
            sheet.placed = true
        }

        if (sheet.anchor != Anchor.Full) GlobeLabel(Modifier.fillMaxSize())

        SearchBar(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        val connection = remember(sheet) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && sheet.offset > 0f && source == NestedScrollSource.UserInput) {
                        sheet.job?.cancel()
                        sheet.moved = true
                        return Offset(0f, sheet.drag(available.y))
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        sheet.job?.cancel()
                        sheet.moved = true
                        return Offset(0f, sheet.drag(available.y))
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (sheet.moved) {
                        sheet.moved = false
                        sheet.animateTo(sheet.target(available.y), available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { sheetH.toDp() })
                .offset { IntOffset(0, sheet.offset.roundToInt()) }
                .nestedScroll(connection)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d -> sheet.drag(d) },
                    onDragStarted = { sheet.job?.cancel() },
                    onDragStopped = { v ->
                        sheet.job = scope.launch { sheet.animateTo(sheet.target(v), v) }
                    },
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
        ) {
            Column {
                Column(Modifier.onSizeChanged { headerH = it.height.toFloat() }) {
                    Handle(sheet)
                    MiniPlayer()
                    PeekRow(onClick = {
                        Nav.tab = Nav.TAB_HERE
                        sheet.job = scope.launch { sheet.animateTo(if (sheet.anchor == Anchor.Peek) Anchor.Half else Anchor.Peek) }
                    })
                }
                // The tabs fade in as the sheet rises, so at its lowest only
                // the place row shows above the navigation bar.
                val fadeOver = with(density) { 56.dp.toPx() } + navBottom
                SheetBody(
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = ((sheet.peekY - sheet.offset) / fadeOver).coerceIn(0f, 1f) },
                    onPlace = { scope.launch { sheet.animateTo(Anchor.Half) } },
                )
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** Landscape and large screens: a panel on the left, the globe beside it. */
@Composable
private fun LandscapeLayout(sheet: Sheet) {
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    // In landscape there's no sheet to go back from.
    LaunchedEffect(Unit) { sheet.anchor = Anchor.Peek }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelW = minOf(420.dp, maxWidth * 0.45f)
        Row(Modifier.fillMaxSize()) {
            Surface(
                Modifier
                    .width(panelW)
                    .fillMaxHeight()
                    .onGloballyPositioned { c ->
                        // The globe is drawn beside the panel (not under it),
                        // so its crosshair is in the middle of what's seen.
                        Native.setGlobeLeft(if (ltr) c.size.width else 0)
                    },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier.windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                    )
                ) {
                    SearchBar(Modifier.padding(12.dp))
                    MiniPlayer()
                    PeekRow(onClick = { Nav.tab = Nav.TAB_HERE })
                    SheetBody(Modifier.weight(1f), onPlace = {})
                    BuildTag(Modifier.padding(start = 16.dp, bottom = 6.dp))
                }
            }
            GlobeLabel(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** The place under the crosshair, just below it. */
@Composable
private fun GlobeLabel(modifier: Modifier) {
    Box(modifier) {
        if (UiState.labelName.isNotEmpty()) {
            val style = TextStyle(color = Color.White, shadow = labelShadow, textAlign = TextAlign.Center)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .layout { m, c ->
                        val p = m.measure(c)
                        layout(p.width, p.height) { p.place(0, p.height / 2 + 26.dp.roundToPx()) }
                    }
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(UiState.labelName, style = style.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                if (UiState.labelDetail.isNotEmpty()) Text(UiState.labelDetail, style = style.copy(fontSize = 14.sp))
            }
        }
    }
}

@Composable
fun BuildTag(modifier: Modifier = Modifier) {
    Text(
        "${AppInfo.version} · build ${AppInfo.build}",
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

/** The sheet's drag handle, with the build number on the right. */
@Composable
private fun Handle(sheet: Sheet) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = if (sheet.anchor == Anchor.Peek) "Show stations" else "Hide stations"
                onClick {
                    scope.launch { sheet.animateTo(if (sheet.anchor == Anchor.Peek) Anchor.Half else Anchor.Peek) }
                    true
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(36.dp, 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
        BuildTag(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
    }
}

/** The floating search bar, with the menu. Opens the search page. */
@Composable
private fun SearchBar(modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClickLabel = "Search") { Nav.page = Page.Search },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, Modifier.padding(start = 16.dp, end = 12.dp))
                Text(
                    "Search stations and places",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.More, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; Nav.page = Page.Settings })
                    DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; Nav.page = Page.About })
                }
            }
        }
    }
}

/** What's playing, above the sheet's place row. Opens Now Playing. */
@Composable
private fun MiniPlayer() {
    if (!UiState.active || UiState.playStation < 0) return
    val playing = UiState.playState == Playback.PLAYING || UiState.playState == Playback.CONNECTING
    Surface(
        onClick = { Nav.page = Page.NowPlaying },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    UiState.playName, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playDetailText(), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FavouriteButton(UiState.playStation)
            IconButton(onClick = { Native.command(if (playing) Playback.CMD_PAUSE else Playback.CMD_PLAY, 0) }) {
                Icon(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play")
            }
        }
    }
}

fun playDetailText(): String = when (UiState.playState) {
    Playback.CONNECTING -> "Connecting…"
    Playback.PAUSED -> "Paused"
    Playback.ERROR -> UiState.playDetail.ifEmpty { "Couldn't play this station" }
    else -> UiState.playDetail.ifEmpty { UiState.playPlace }
}

/** The tuned place: name, country, how many stations. */
@Composable
private fun PeekRow(onClick: () -> Unit) {
    val pl = UiState.labelPlace
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = pl >= 0, onClickLabel = "Show its stations", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!UiState.ready) {
            Text("Loading stations…", style = MaterialTheme.typography.titleMedium)
        } else if (pl < 0) {
            Text(
                "Turn the globe and hold a place under the ring",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val p = Lib.place(pl)
            Column(Modifier.weight(1f)) {
                Text(
                    if (p.countryWide) "${p.country}, country-wide" else p.name,
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!p.countryWide && p.country.isNotEmpty()) {
                    Text(p.country, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${p.stations} station${if (p.stations == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
