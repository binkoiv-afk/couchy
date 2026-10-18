package app.couchy.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couchy.core.radio.RadioBrowser
import app.couchy.core.radio.RadioGenre
import app.couchy.core.radio.Station
import app.couchy.radio.RadioPlayer
import app.couchy.ui.components.Chip
import app.couchy.ui.components.couchyClickable

/** Which stations are listed. */
private sealed class RadioList(val label: String) {
    data object Favourites : RadioList("Избранное")
    data object Russia : RadioList("Россия")
    data object World : RadioList("Популярное в мире")
    data class Genre(val genre: RadioGenre) : RadioList(genre.label)
    data class Search(val query: String) : RadioList("«$query»")
}

/**
 * Internet radio: thousands of stations from the open Radio Browser directory, by country,
 * genre or name, with favourites on top. A station plays in the music player; the arrows there
 * go to the next and previous station of the same list.
 */
@Composable
fun RadioDialog(onPlay: (List<Station>, Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val context = LocalContext.current
        var favourites by remember { mutableStateOf(RadioPlayer.favourites(context)) }
        var list by remember { mutableStateOf<RadioList>(if (favourites.isNotEmpty()) RadioList.Favourites else RadioList.Russia) }
        var query by remember { mutableStateOf("") }
        var stations by remember { mutableStateOf<List<Station>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var attempt by remember { mutableStateOf(0) }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(list, attempt) {
            error = null
            if (list == RadioList.Favourites) { stations = favourites; return@LaunchedEffect }
            stations = null
            stations = runCatching {
                when (val l = list) {
                    RadioList.Russia -> RadioPlayer.load { RadioBrowser.countryUrl(it) }
                    RadioList.World -> RadioPlayer.load { RadioBrowser.topUrl(it) }
                    is RadioList.Genre -> RadioPlayer.load { RadioBrowser.tagUrl(it, l.genre.tag) }
                    is RadioList.Search -> RadioPlayer.load { RadioBrowser.searchUrl(it, l.query) }
                    RadioList.Favourites -> favourites
                }
            }.getOrElse { error = "Каталог радио не отвечает. Проверьте интернет и попробуйте ещё раз."; emptyList() }
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Радио", style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
            }
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Название станции") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    if (query.isNotBlank()) list = RadioList.Search(query.trim())
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
            val chips = listOf(RadioList.Favourites, RadioList.Russia, RadioList.World) + RadioGenre.entries.map { RadioList.Genre(it) }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { c -> Chip(c.label, c == list, onClick = { list = c }) }
                (list as? RadioList.Search)?.let { s -> item { Chip(s.label, true, onClick = {}, icon = Icons.Rounded.Search) } }
            }
            val shown = stations
            when {
                shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                error != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(error!!, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chip("Ещё раз", false, onClick = { attempt++ }, modifier = Modifier.padding(top = 12.dp))
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (list == RadioList.Favourites) "Здесь будут станции, отмеченные звёздочкой." else "Ничего не нашлось.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                    itemsIndexed(shown, key = { _, s -> s.uuid + s.url }) { i, s ->
                        StationRow(
                            s,
                            favourite = favourites.any { it.uuid == s.uuid },
                            onPlay = { onPlay(shown, i) },
                            onStar = {
                                RadioPlayer.toggleFavourite(context, s)
                                favourites = RadioPlayer.favourites(context)
                                if (list == RadioList.Favourites) stations = favourites
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(s: Station, favourite: Boolean, onPlay: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).couchyClickable(RoundedCornerShape(14.dp), onLongClick = onStar, onClick = onPlay).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            s.favicon?.let { coil.compose.AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.about.isNotEmpty()) Text(s.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.widthIn(min = 44.dp).clip(CircleShape).couchyClickable(CircleShape, onClick = onStar).padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (favourite) "Убрать из избранного" else "В избранное",
                tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
