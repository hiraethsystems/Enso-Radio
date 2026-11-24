package systems.hiraeth.dxgarden

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A page's top row: back and a title. */
@Composable
private fun PageTop(title: String) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Nav.page = Page.None }, Modifier.padding(start = 4.dp)) { Icon(Icons.Back, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

// ---- Search ----

@Composable
fun SearchPage(sheet: Sheet) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(IntArray(0)) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            result = IntArray(0)
            return@LaunchedEffect
        }
        delay(120)
        result = withContext(Dispatchers.Default) { Native.search(query, 200) }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.page = Page.None }) { Icon(Icons.Back, "Back") }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Stations and places") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Close, "Clear") }
        }
        HorizontalDivider()
        val nPlaces = result.firstOrNull() ?: 0
        val places = if (result.isEmpty()) IntArray(0) else result.copyOfRange(1, 1 + nPlaces)
        val stations = if (result.isEmpty()) IntArray(0) else result.copyOfRange(1 + nPlaces, result.size)
        if (query.isNotBlank() && result.isNotEmpty() && places.isEmpty() && stations.isEmpty()) {
            Text("Nothing found", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            if (places.isNotEmpty()) item { SectionTitle("Places") }
            items(places.size, key = { "p" + places[it] }) { i ->
                PlaceRow(places[i], showCountry = true, onClick = {
                    showPlace(places[i])
                    Nav.page = Page.None
                })
            }
            if (stations.isNotEmpty()) item { SectionTitle("Stations") }
            items(stations.size, key = { "s" + stations[it] }) { i -> StationRow(stations[i], showPlace = true) }
        }
    }
}

// ---- Now Playing ----

@Composable
fun NowPlayingPage() {
    val si = UiState.playStation
    if (si < 0 || !UiState.active) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            PageTop("Now playing")
            Text("Nothing is playing.", Modifier.padding(24.dp))
        }
        return
    }
    val s = remember(si) { Lib.station(si) }
    var status by remember { mutableStateOf(Native.status()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(si) {
        while (true) {
            status = Native.status()
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val context = LocalContext.current
    val playing = UiState.playState == Playback.PLAYING || UiState.playState == Playback.CONNECTING
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageTop("Now playing")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(UiState.playName, style = MaterialTheme.typography.headlineMedium)
            Text(UiState.playPlace, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(playDetailText(), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Native.command(Playback.CMD_PREVIOUS, 0) }, Modifier.size(56.dp)) {
                    Icon(Icons.Previous, "Previous station here")
                }
                FilledIconButton(
                    onClick = { Native.command(if (playing) Playback.CMD_PAUSE else Playback.CMD_PLAY, 0) },
                    Modifier.size(72.dp),
                ) { Icon(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", Modifier.size(36.dp)) }
                IconButton(onClick = { Native.command(Playback.CMD_NEXT, 0) }, Modifier.size(56.dp)) {
                    Icon(Icons.Next, "Next station here")
                }
                IconButton(onClick = { Native.command(Playback.CMD_STOP, 0); Nav.page = Page.None }, Modifier.size(56.dp)) {
                    Icon(Icons.Stop, "Stop")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FavouriteButton(si)
                Text("Favourite", style = MaterialTheme.typography.bodyLarge)
            }

            SectionTitleFlat("Sleep timer")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60, 90).forEach { m ->
                    FilterChip(
                        selected = SleepTimer.minutes == m,
                        onClick = { SleepTimer.set(m) },
                        label = { Text(if (m == 0) "Off" else "$m min") },
                    )
                }
            }
            if (SleepTimer.endsAt > 0) {
                val left = ((SleepTimer.endsAt - now) / 1000).coerceAtLeast(0)
                Text("Stops in ${left / 60}:${"%02d".format(left % 60)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitleFlat("Station")
            if (s.genre.isNotEmpty()) Detail("Genre", s.genre)
            val codec = status[0]
            if (codec.isNotEmpty()) {
                val kbps = status[3].toIntOrNull() ?: 0
                val rate = (status[1].toLongOrNull() ?: 0) / 1000.0
                val ch = status[2].toIntOrNull() ?: 0
                Detail(
                    "Stream",
                    listOfNotNull(
                        codec, if (kbps > 0) "$kbps kbps" else null,
                        if (rate > 0) "%.1f kHz".format(rate).replace(".0 ", " ") else null,
                        when (ch) { 1 -> "mono"; 2 -> "stereo"; 0 -> null; else -> "$ch channels" },
                    ).joinToString(" · "),
                )
                Detail("Buffer", "${status[7]}%")
            }
            if (status[4].isNotEmpty() && status[4] != s.name) Detail("Stream's name", status[4])
            // The stream's own website, or the one in the station list
            // ("web:..." in its notes).
            val listed = Regex("""(?:^|\s)web:(\S+)""").find(s.notes)?.groupValues?.get(1) ?: ""
            val site = status[5].takeIf { it.startsWith("http") } ?: listed
            if (site.startsWith("http://") || site.startsWith("https://")) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open the station's website") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Website", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(site, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.OpenInNew, null)
                }
            }
            Text("Stream address", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(s.url, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitleFlat(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- Settings ----

@Composable
private fun Choice(label: String, detail: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsPage() {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageTop("Settings")
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionTitle("Colours")
            Column(Modifier.selectableGroup()) {
                Choice("DX Garden", "Night blue and amber", Prefs.colours == Prefs.COLOURS_DXG) { Prefs.changeColours(Prefs.COLOURS_DXG) }
                Choice("Your phone's colours", "From your wallpaper (Material You)", Prefs.colours == Prefs.COLOURS_PHONE) {
                    Prefs.changeColours(Prefs.COLOURS_PHONE)
                }
            }
            SectionTitle("Light or dark")
            Column(Modifier.selectableGroup()) {
                Choice("As the phone", null, Prefs.theme == Prefs.THEME_SYSTEM) { Prefs.changeTheme(Prefs.THEME_SYSTEM) }
                Choice("Light", null, Prefs.theme == Prefs.THEME_LIGHT) { Prefs.changeTheme(Prefs.THEME_LIGHT) }
                Choice("Dark", null, Prefs.theme == Prefs.THEME_DARK) { Prefs.changeTheme(Prefs.THEME_DARK) }
            }
            SectionTitle("Buffer before playing")
            Column(Modifier.selectableGroup()) {
                Choice("Short", "Starts quickest; more likely to break up on a weak signal", Prefs.bufferKb == 32) { Prefs.changeBufferKb(32) }
                Choice("Normal", null, Prefs.bufferKb == 64) { Prefs.changeBufferKb(64) }
                Choice("Long", "Slower to start; rides out a poor connection", Prefs.bufferKb == 160) { Prefs.changeBufferKb(160) }
            }
            SectionTitle("Globe")
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = Prefs.haptics, role = Role.Switch, onValueChange = { Prefs.changeHaptics(it) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tick when tuned", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A light vibration when the ring settles on a place",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = Prefs.haptics, onCheckedChange = null)
            }
            SectionTitle("Your files")
            Text(
                "Your station list (stations.txt), favourites and recent stations are in " +
                    "Android/data/systems.hiraeth.dxgarden/files on the phone's storage, where a computer " +
                    "can reach them over USB.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- About ----

/** The author's name and email, as Aisling wants them shown. */
private const val AUTHOR = "Aisling"
private const val AUTHOR_EMAIL = "aisling@hiraeth.club"

@Composable
fun AboutPage() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        PageTop("About")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text("DX Garden", style = MaterialTheme.typography.displaySmall)
            Text(
                "Version ${AppInfo.version} · build ${AppInfo.build}",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "A radio explorer: turn the globe, hold the ring over a place and hear live radio from there. " +
                    "It comes with a dated snapshot of the Radio Browser directory as its station list, which is " +
                    "yours to keep and edit.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text("Made by $AUTHOR", style = MaterialTheme.typography.bodyLarge)
            Text(
                AUTHOR_EMAIL,
                Modifier
                    .clickable(role = Role.Button, onClickLabel = "Send an email") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$AUTHOR_EMAIL"))) }
                    }
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                    Text(
                        "This small garden of voices is dedicated to Emma, Max and Phoebe, my family, and to " +
                            "Imi and John, my dearest friends.",
                        style = style,
                    )
                    Text(
                        "Like stations drifting across the dial at night, so much in this world arrives and " +
                            "fades away. You are the ones that stay.",
                        style = style,
                    )
                    Text(
                        "Wherever I wander on this turning earth, through every distant city and quiet sea of " +
                            "static, my heart returns to you, the way a river returns to the ocean, gently and always.",
                        style = style,
                    )
                }
            }

            Text(
                "Credits", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 28.dp, bottom = 6.dp).semantics { heading() },
            )
            val credits = listOf(
                "DX Garden is free software under the GNU GPL v3. Source: github.com/hiraethsystems/DX-Garden-Android",
                "Stations from Radio Browser (radio-browser.info). Place names from GeoNames (CC BY 4.0).",
                "Globe imagery from NASA: Blue Marble Next Generation (Reto Stöckli, NASA Earth Observatory) and " +
                    "Black Marble 2016 (Joshua Stevens, NASA Earth Observatory; Suomi NPP VIIRS data, Miguel Román, " +
                    "NASA GSFC). Use here implies no endorsement by NASA.",
                "Built with SDL3, libcurl, Mbed TLS, mpg123, faad2, libogg, libvorbis, Opus, libFLAC, " +
                    "stb_image, Jetpack Compose and Material Icons.",
            )
            credits.forEach { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
