package app.stereoplay

import android.content.Context
import android.net.Uri

/** How the two eye pictures are laid out on the screen. */
enum class Layout(val label: String) {
    VR("VR-очки"),
    SBS_HALF("SBS половинный (3D-ТВ)"),
    TAB("Верх-низ (3D-ТВ)"),
    ANAGLYPH("Анаглиф красный/голубой"),
    FLAT("2D, без объёма"),
    DEPTH("Карта глубины"),
}

/** Depth map resolution, by the short side of the picture the network sees. */
enum class DepthDetail(val label: String, val shortSide: Int) {
    FAST("Быстро", 182),
    BALANCED("Средне", 252),
    HIGH("Чётко", 364),
    MAX("Максимум", 518),
}

/** The two bundled variants of Depth Anything V2 Small. */
enum class DepthModel(val label: String, val file: String) {
    FAST("Быстрая", "model_quantized.onnx"),
    PRECISE("Точная", "model.onnx"),
}

data class StereoParams(
    val layout: Layout = Layout.VR,
    /** Largest gap between the eyes, as a share of one eye's width. */
    val strength: Float = 0.03f,
    /** Share of the depth range that comes out in front of the screen. */
    val popout: Float = 0.15f,
    val detail: DepthDetail = DepthDetail.FAST,
    val model: DepthModel = DepthModel.FAST,
    val swapEyes: Boolean = false,
) {
    val needsDepth: Boolean get() = layout != Layout.FLAT
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("stereo", Context.MODE_PRIVATE)

    fun params(): StereoParams {
        val d = StereoParams()
        return StereoParams(
            layout = enumOr(sp.getString("layout", null), d.layout),
            strength = sp.getFloat("strength", d.strength),
            popout = sp.getFloat("popout", d.popout),
            detail = enumOr(sp.getString("detail", null), d.detail),
            model = enumOr(sp.getString("model", null), d.model),
            swapEyes = sp.getBoolean("swap", d.swapEyes),
        )
    }

    fun save(p: StereoParams) {
        sp.edit()
            .putString("layout", p.layout.name)
            .putFloat("strength", p.strength)
            .putFloat("popout", p.popout)
            .putString("detail", p.detail.name)
            .putString("model", p.model.name)
            .putBoolean("swap", p.swapEyes)
            .apply()
    }

    fun position(uri: Uri): Long = sp.getLong("pos:$uri", 0L)

    fun savePosition(uri: Uri, ms: Long) {
        sp.edit().putLong("pos:$uri", ms).apply()
    }

    /** Recently opened videos, newest first. */
    fun recent(): List<Recent> =
        sp.getString("recent", "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) null else Recent(Uri.parse(line.substring(0, tab)), line.substring(tab + 1))
        }

    fun addRecent(uri: Uri, title: String) {
        val list = listOf(Recent(uri, title)) + recent().filter { it.uri != uri }
        sp.edit().putString("recent", list.take(10).joinToString("\n") { "${it.uri}\t${it.title.replace('\n', ' ')}" }).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

data class Recent(val uri: Uri, val title: String)
