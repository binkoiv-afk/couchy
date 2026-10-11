package app.couchy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.couchy.ui.components.CouchMark
import app.couchy.ui.components.CouchyLogo
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.components.couchyClickable

enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    SEARCH("Поиск", Icons.Rounded.Search),
    TV("ТВ", Icons.Rounded.LiveTv),
    YOUTUBE("YouTube", Icons.Rounded.SmartDisplay),
    LIBRARY("Моё", Icons.Rounded.VideoLibrary),
    SETTINGS("Настройки", Icons.Rounded.Settings),
}

/** Kids mode: no search, no links, and the settings place becomes "Выйти" behind the PIN. */
val LocalKidsMode = androidx.compose.runtime.staticCompositionLocalOf { false }

private val kidsSections = listOf(Destination.HOME, Destination.TV, Destination.LIBRARY)

/**
 * The frame around every screen: a side rail on TV and tablets, a bottom bar on phones.
 * "Открыть" sits in the same place as the sections so it is one press away everywhere.
 */
@Composable
fun Shell(current: Destination, onNavigate: (Destination) -> Unit, onOpen: () -> Unit, content: @Composable () -> Unit) {
    val form = LocalFormFactor.current
    if (form.wide) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Labels stay on TV too: icons alone left people guessing where search is.
            Rail(current, onNavigate, onOpen, compact = false)
            Box(Modifier.fillMaxSize().then(if (current == Destination.HOME) Modifier else Modifier.statusBarsPadding())) { Themed(content) }
        }
    } else {
        // Phones: the screen runs edge to edge and the menu floats over it.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val inset = if (current == Destination.HOME) Modifier else Modifier.statusBarsPadding().padding(bottom = 84.dp).navigationBarsPadding()
            Box(Modifier.fillMaxSize().then(inset)) { Themed(content) }
            BottomBar(current, onNavigate, onOpen, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Text without an explicit color follows the theme instead of falling back to black. */
@Composable
private fun Themed(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)

@Composable
private fun Rail(current: Destination, onNavigate: (Destination) -> Unit, onOpen: () -> Unit, compact: Boolean) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (compact) 88.dp else 220.dp)
            .background(if (compact) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer)
            .statusBarsPadding()
            .padding(vertical = 24.dp, horizontal = if (compact) 12.dp else 14.dp),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (compact) CouchMark(36.dp) else CouchyLogo(30.dp, Modifier.padding(start = 10.dp))
        Spacer(Modifier.height(22.dp))
        val kids = LocalKidsMode.current
        val sections = if (kids) kidsSections else Destination.entries.filter { it != Destination.SETTINGS }
        sections.forEach { d ->
            RailItem(d.icon, d.label, d == current, compact) { onNavigate(d) }
        }
        if (!kids) RailItem(Icons.Rounded.AddCircle, "Открыть", false, compact, onClick = onOpen)
        Spacer(Modifier.weight(1f))
        if (kids) {
            RailItem(Icons.Rounded.Lock, "Выйти", false, compact) { onNavigate(Destination.SETTINGS) }
        } else {
            RailItem(Destination.SETTINGS.icon, Destination.SETTINGS.label, current == Destination.SETTINGS, compact) {
                onNavigate(Destination.SETTINGS)
            }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val fg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .couchyClickable(shape, focusScale = 1.08f, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else fg, modifier = Modifier.size(24.dp))
        if (!compact) Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

@Composable
private fun BottomBar(current: Destination, onNavigate: (Destination) -> Unit, onOpen: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f), shape)
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val item = Modifier.weight(1f)
        if (LocalKidsMode.current) {
            kidsSections.forEach { d -> BarItem(d.icon, d.label, current == d, item) { onNavigate(d) } }
            BarItem(Icons.Rounded.Lock, "Выйти", false, item) { onNavigate(Destination.SETTINGS) }
            return@Row
        }
        listOf(Destination.HOME, Destination.SEARCH, Destination.TV, Destination.YOUTUBE, Destination.LIBRARY).forEach { d ->
            BarItem(d.icon, d.label, current == d || (d == Destination.LIBRARY && current == Destination.SETTINGS), item) { onNavigate(d) }
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.padding(horizontal = 2.dp).couchyClickable(RoundedCornerShape(16.dp), focusScale = 1f, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else fg, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}
