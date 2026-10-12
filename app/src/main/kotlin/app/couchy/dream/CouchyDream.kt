package app.couchy.dream

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.couchy.CouchyApp
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleJson
import app.couchy.data.Marks
import coil.imageLoader
import coil.request.ImageRequest

/**
 * The TV screensaver: wide stills of what is watched now and of the person's "Хочу посмотреть",
 * slowly drifting, with the name and the clock. Picked in the TV's settings under screensaver.
 */
class CouchyDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var images: List<ImageView>
    private lateinit var name: TextView
    private lateinit var note: TextView
    private var shown = 0
    private var slides: List<Pair<Title, String>> = emptyList()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        images = List(2) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
                root.addView(this, FrameLayout.LayoutParams(-1, -1))
            }
        }
        // Dark at the bottom so the name reads over any picture.
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x00000000, 0xCC000000.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, dp(56), dp(48))
        }
        note = text(16f, 0xB3FFFFFF.toInt(), Typeface.NORMAL)
        name = text(40f, Color.WHITE, Typeface.BOLD)
        caption.addView(note)
        caption.addView(name)
        root.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        root.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setPadding(0, dp(36), dp(56), 0)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val later = runCatching { (application as CouchyApp).marks.titles(Marks.LATER) }.getOrDefault(emptyList())
        val keep = (later.map { it to "Хочу посмотреть" } + saved(this).map { it to "Сейчас смотрят" })
            .distinctBy { it.first.key }
            .mapNotNull { (t, why) -> (t.backdrop ?: t.poster)?.let { t to why } }
        slides = keep.shuffled()
        if (slides.isEmpty()) {
            name.text = "Couchy"
            note.text = "Откройте главную, и здесь появятся кадры из новинок"
            return
        }
        next()
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    private fun next() {
        val (title, why) = slides[shown % slides.size]
        val image = images[shown % 2]
        val other = images[(shown + 1) % 2]
        shown++
        val request = ImageRequest.Builder(this)
            .data(title.backdrop ?: title.poster)
            .target(
                onSuccess = { drawable ->
                    image.setImageDrawable(drawable)
                    image.scaleX = 1f; image.scaleY = 1f; image.translationX = 0f
                    image.animate().alpha(1f).setDuration(1_500).start()
                    other.animate().alpha(0f).setDuration(1_500).start()
                    // A slow drift, as on Apple TV.
                    image.animate().scaleX(1.12f).scaleY(1.12f).translationX(-dp(40).toFloat()).setDuration(SLIDE_MS + 2_000).start()
                    name.text = title.name
                    note.text = listOfNotNull(why, title.year?.toString()).joinToString(" · ")
                },
            )
            .build()
        imageLoader.enqueue(request)
        handler.postDelayed({ next() }, SLIDE_MS)
    }

    private fun text(size: Float, color: Int, style: Int) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        maxLines = 2
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val SLIDE_MS = 12_000L
        private const val FILE = "dream.json"

        /** Remembers the stills from Home for the next time the box rests. */
        fun keep(context: Context, titles: List<Title>) {
            val list = titles.filter { it.backdrop != null }.distinctBy { it.key }.take(40)
            if (list.isEmpty()) return
            runCatching { context.filesDir.resolve(FILE).writeText(TitleJson.encode(list).toString()) }
        }

        private fun saved(context: Context): List<Title> = runCatching {
            TitleJson.decode(org.json.JSONArray(context.filesDir.resolve(FILE).readText()))
        }.getOrDefault(emptyList())
    }
}
