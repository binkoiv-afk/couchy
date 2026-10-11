package app.couchy.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
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
    TV("ТВ", Icons.Rounded.LiveTv),
    LIBRARY("Медиатека", Icons.Rounded.GridView),
    SETTINGS("Настройки", Icons.Rounded.Settings),
}

/**
 * The frame around every screen: a side rail on TV and tablets, a bottom bar on phones.
 * "Открыть" sits in the same place as the sections so it is one press away everywhere.
 */
@Composable
fun Shell(current: Destination, onNavigate: (Destination) -> Unit, onOpen: () -> Unit, content: @Composable () -> Unit) {
    val form = LocalFormFactor.current
    if (form.wide) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Rail(current, onNavigate, onOpen, compact = form == FormFactor.TV)
            Box(Modifier.fillMaxSize().statusBarsPadding()) { Themed(content) }
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CouchyLogo(30.dp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { Themed(content) }
            BottomBar(current, onNavigate, onOpen)
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
        Destination.entries.filter { it != Destination.SETTINGS }.forEach { d ->
            RailItem(d.icon, d.label, d == current, compact) { onNavigate(d) }
        }
        RailItem(Icons.Rounded.AddCircle, "Открыть", false, compact, onClick = onOpen)
        Spacer(Modifier.weight(1f))
        RailItem(Destination.SETTINGS.icon, Destination.SETTINGS.label, current == Destination.SETTINGS, compact) {
            onNavigate(Destination.SETTINGS)
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
private fun BottomBar(current: Destination, onNavigate: (Destination) -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        val item = Modifier.weight(1f)
        BarItem(Destination.HOME.icon, Destination.HOME.label, current == Destination.HOME, item) { onNavigate(Destination.HOME) }
        BarItem(Destination.TV.icon, Destination.TV.label, current == Destination.TV, item) { onNavigate(Destination.TV) }
        BarItem(Icons.Rounded.AddCircle, "Открыть", false, item, onClick = onOpen)
        BarItem(Destination.LIBRARY.icon, Destination.LIBRARY.label, current == Destination.LIBRARY, item) { onNavigate(Destination.LIBRARY) }
        BarItem(Destination.SETTINGS.icon, Destination.SETTINGS.label, current == Destination.SETTINGS, item) { onNavigate(Destination.SETTINGS) }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.padding(horizontal = 2.dp).couchyClickable(RoundedCornerShape(14.dp), focusScale = 1f, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else fg, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}
