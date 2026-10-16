package app.couchy.ui.player

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.couchy.core.player.FrameRates
import app.couchy.core.player.ScreenMode
import app.couchy.ui.components.Chip
import app.couchy.ui.components.CouchySheet
import app.couchy.ui.components.SheetHeader
import app.couchy.ui.components.couchyClickable
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** How subtitles look: size, what is behind the letters, and how high they sit. */
enum class SubSize(val label: String, val scale: Float) { S("Мелкие", 0.8f), M("Обычные", 1f), L("Крупные", 1.3f), XL("Огромные", 1.65f) }
enum class SubBack(val label: String) { SHADOW("Тень"), BOX("Подложка"), OUTLINE("Обводка") }
enum class SubPlace(val label: String, val bottom: Float) { LOW("Внизу", 0.04f), MID("Чуть выше", 0.1f), HIGH("Высоко", 0.2f) }

/** The player's own settings kept on the device: subtitle look, sound shift, frame rate on TV. */
object PlayerTuning {
    private fun prefs(context: Context) = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> read(context: Context, key: String, default: E): E =
        prefs(context).getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    fun subSize(c: Context) = read(c, "sub_size", SubSize.M)
    fun subBack(c: Context) = read(c, "sub_back", SubBack.SHADOW)
    fun subPlace(c: Context) = read(c, "sub_place", SubPlace.LOW)
    fun setSub(c: Context, size: SubSize, back: SubBack, place: SubPlace) =
        prefs(c).edit().putString("sub_size", size.name).putString("sub_back", back.name).putString("sub_place", place.name).apply()

    /** Sound moved against the picture, ms: below zero it comes earlier. Kept for the device (headphones lag). */
    fun audioShift(c: Context) = prefs(c).getLong("audio_shift", 0)
    fun setAudioShift(c: Context, ms: Long) = prefs(c).edit().putLong("audio_shift", ms).apply()

    fun frameRate(c: Context) = prefs(c).getBoolean("frame_rate", true)
    fun setFrameRate(c: Context, on: Boolean) = prefs(c).edit().putBoolean("frame_rate", on).apply()

    /** Draws subtitles the way the person set them, over whatever the file asks for. */
    @OptIn(UnstableApi::class)
    fun style(view: SubtitleView, size: SubSize, back: SubBack, place: SubPlace) {
        val black = android.graphics.Color.BLACK
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size.scale)
        view.setBottomPaddingFraction(place.bottom)
        view.setStyle(
            when (back) {
                SubBack.SHADOW -> CaptionStyleCompat(android.graphics.Color.WHITE, 0, 0, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, black, null)
                SubBack.BOX -> CaptionStyleCompat(android.graphics.Color.WHITE, 0xB3000000.toInt(), 0, CaptionStyleCompat.EDGE_TYPE_NONE, black, null)
                SubBack.OUTLINE -> CaptionStyleCompat(android.graphics.Color.WHITE, 0, 0, CaptionStyleCompat.EDGE_TYPE_OUTLINE, black, null)
            },
        )
    }

    fun shiftLabel(ms: Long): String = when {
        ms == 0L -> "как в файле"
        else -> (if (ms > 0) "позже на " else "раньше на ") + String.format(Locale("ru"), "%.1f с", abs(ms) / 1000f)
    }
}

/**
 * Moves the sound against the picture. Later: silence goes first. Earlier: the first moment of
 * sound is dropped, so the rest plays ahead. Applied from every seek, as the player starts over then.
 */
@OptIn(UnstableApi::class)
class AudioShift : BaseAudioProcessor() {
    @Volatile var shiftMs: Long = 0
    private var silence = 0
    private var drop = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (drop > 0) {
            val skip = min(drop, inputBuffer.remaining())
            inputBuffer.position(inputBuffer.position() + skip)
            drop -= skip
        }
        val size = inputBuffer.remaining()
        if (size == 0 && silence == 0) return
        val out = replaceOutputBuffer(size + silence)
        if (silence > 0) {
            out.put(ByteArray(silence))
            silence = 0
        }
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        val format = inputAudioFormat
        silence = 0
        drop = 0
        if (format.sampleRate <= 0 || format.bytesPerFrame <= 0) return
        val bytes = (abs(shiftMs) * format.sampleRate / 1000).toInt() * format.bytesPerFrame
        if (shiftMs > 0) silence = bytes else drop = bytes
    }

    override fun onReset() {
        silence = 0
        drop = 0
    }
}

/**
 * On a TV, switches the screen to the film's own rate (24, 25, 23.976 frames) while it plays,
 * so the picture does not judder; the screen goes back when the player closes.
 */
@OptIn(UnstableApi::class)
@Composable
fun MatchFrameRate(player: ExoPlayer, enabled: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(player, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        fun apply() {
            val fps = player.videoFormat?.frameRate ?: return
            val display = (if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay) ?: return
            val current = display.mode
            fun of(m: android.view.Display.Mode) = ScreenMode(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)
            val target = FrameRates.pick(fps, display.supportedModes.map(::of), of(current)) ?: return
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = target.id }
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = apply()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = apply()
        }
        player.addListener(listener)
        apply()
        onDispose {
            player.removeListener(listener)
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }
}

/** «Синхронизация»: sound and subtitles a tenth of a second at a time, earlier or later. */
@Composable
fun SyncSheet(audioMs: Long, subMs: Long, subNote: String?, onAudio: (Long) -> Unit, onSub: (Long) -> Unit, onDismiss: () -> Unit) {
    CouchySheet("Синхронизация", onDismiss) {
        SheetHeader("Звук")
        Stepper(PlayerTuning.shiftLabel(audioMs), onLess = { onAudio(audioMs - 100) }, onMore = { onAudio(audioMs + 100) }, onReset = { onAudio(0) }.takeIf { audioMs != 0L })
        Text(
            "Если голос отстаёт от губ, жмите «−». Сдвиг запоминается для этого устройства: с наушниками он обычно один и тот же.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SheetHeader("Субтитры")
        Stepper(PlayerTuning.shiftLabel(subMs), onLess = { onSub(subMs - 100) }, onMore = { onSub(subMs + 100) }, onReset = { onSub(0) }.takeIf { subMs != 0L })
        Text(
            subNote ?: "Если субтитры появляются раньше слов, жмите «+». Сдвиг действует для этого видео.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Stepper(value: String, onLess: () -> Unit, onMore: () -> Unit, onReset: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton(Icons.Rounded.Remove, "Раньше", onLess)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        RoundButton(Icons.Rounded.Add, "Позже", onMore)
        onReset?.let { Chip("Сброс", false, onClick = it) }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape).couchyClickable(CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface) }
}

/** «Вид субтитров»: size, background and height, seen at once on the film behind the sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyleSheet(size: SubSize, back: SubBack, place: SubPlace, onChange: (SubSize, SubBack, SubPlace) -> Unit, onDismiss: () -> Unit) {
    CouchySheet("Вид субтитров", onDismiss) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeader("Размер")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubSize.entries.forEach { s -> Chip(s.label, s == size, onClick = { onChange(s, back, place) }) }
            }
            SheetHeader("Фон")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubBack.entries.forEach { b -> Chip(b.label, b == back, onClick = { onChange(size, b, place) }) }
            }
            SheetHeader("Положение")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubPlace.entries.forEach { p -> Chip(p.label, p == place, onClick = { onChange(size, back, p) }) }
            }
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(12.dp)).padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Так будут выглядеть субтитры",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * size.scale),
                    modifier = if (back == SubBack.BOX) Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp) else Modifier,
                )
            }
        }
    }
}
