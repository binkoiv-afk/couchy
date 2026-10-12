package app.couchy.ui.player

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build

/**
 * "Ночной звук": explosions quieter, voices louder. A compressor squeezes the loud parts, the
 * speech range is lifted a little and a limiter keeps peaks from waking anyone. Android 9 and up.
 */
object NightSound {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("night", false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("night", on).apply()
    }

    /** Starts processing the player's sound; close the result to stop. Null where it cannot. */
    fun attach(audioSession: Int): AutoCloseable? {
        if (!supported || audioSession == 0) return null
        return runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, 3,
                true, 1,
                false, 0,
                true,
            ).build()
            // Low rumble down a little, the voice range up, the rest as it is.
            val eq = DynamicsProcessing.Eq(true, true, 3).apply {
                setBand(0, DynamicsProcessing.EqBand(true, 200f, -3f))
                setBand(1, DynamicsProcessing.EqBand(true, 4_000f, 4f))
                setBand(2, DynamicsProcessing.EqBand(true, 20_000f, 0f))
            }
            config.setPreEqAllChannelsTo(eq)
            // Everything above -32 dB is squeezed 4:1, then the whole is brought back up.
            val mbc = DynamicsProcessing.Mbc(true, true, 1).apply {
                setBand(0, DynamicsProcessing.MbcBand(true, 20_000f, 5f, 120f, 4f, -32f, 8f, -90f, 1f, 0f, 9f))
            }
            config.setMbcAllChannelsTo(mbc)
            config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -3f, 0f))
            val effect = DynamicsProcessing(0, audioSession, config)
            effect.enabled = true
            AutoCloseable { runCatching { effect.enabled = false; effect.release() } }
        }.getOrNull()
    }
}
