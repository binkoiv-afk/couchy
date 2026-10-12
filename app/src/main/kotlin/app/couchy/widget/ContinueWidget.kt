package app.couchy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import app.couchy.CouchyApp
import app.couchy.MainActivity
import app.couchy.R
import app.couchy.core.LibrarySort
import app.couchy.core.Progress
import app.couchy.data.MediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Продолжить просмотр" on the phone's home screen: up to three unfinished videos, a tap plays on. */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as CouchyApp
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                draw(context, runCatching { app.library.snapshot() }.getOrDefault(emptyList()))
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private val rows = listOf(
            Row(R.id.row0, R.id.title0, R.id.note0, R.id.progress0),
            Row(R.id.row1, R.id.title1, R.id.note1, R.id.progress1),
            Row(R.id.row2, R.id.title2, R.id.note2, R.id.progress2),
        )

        private data class Row(val row: Int, val title: Int, val note: Int, val progress: Int)

        /** Redraws every placed widget from the library. */
        fun draw(context: Context, all: List<MediaEntity>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isEmpty()) return
            val items = LibrarySort.continueWatching(all).take(rows.size)
            val views = RemoteViews(context.packageName, R.layout.widget_continue)
            views.setViewVisibility(R.id.empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
            rows.forEachIndexed { i, r ->
                val item = items.getOrNull(i)
                views.setViewVisibility(r.row, if (item == null) View.GONE else View.VISIBLE)
                if (item == null) return@forEachIndexed
                views.setTextViewText(r.title, item.title)
                views.setTextViewText(r.note, Progress.remainingLabel(item.positionMs, item.durationMs) ?: "Продолжить")
                views.setProgressBar(r.progress, 1000, (Progress.fraction(item.positionMs, item.durationMs) * 1000).toInt(), false)
                val open = Intent(Intent.ACTION_VIEW, Uri.parse(item.uri), context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(
                    r.row,
                    PendingIntent.getActivity(context, i, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
