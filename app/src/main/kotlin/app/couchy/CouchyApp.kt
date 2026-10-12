package app.couchy

import android.app.Application
import app.couchy.bypass.Bypass
import app.couchy.data.CouchyDatabase
import app.couchy.data.LibraryRepository
import app.couchy.data.Settings
import app.couchy.torrent.TorrEngine
import app.couchy.tv.TvDatabase
import app.couchy.tv.TvRepository
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class CouchyApp : Application() {
    /** Work that lives as long as the app does. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val database by lazy { CouchyDatabase.create(this) }
    val library by lazy { LibraryRepository(this, database.media()) }
    val settings by lazy { Settings(this) }
    val tvDatabase by lazy { TvDatabase.create(this) }
    val tv by lazy { TvRepository(tvDatabase.tv()) }
    val torrEngine by lazy { TorrEngine(this) }
    val bypass by lazy { Bypass(this, settings) }
    val cast by lazy { app.couchy.cast.Cast(this) }
    val marks by lazy { app.couchy.data.Marks(this) }
    val downloads by lazy { app.couchy.download.Downloads(this) }
    val kinopub by lazy { app.couchy.sources.KinoPubAccount(this) }
    val stats by lazy { app.couchy.data.WatchStats(this) }
    val sync by lazy { app.couchy.sync.Sync(this, settings, library, tv, marks) }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        QualityChoice.init(this)
        ExternalPlayer.init(this)
        AutoDownload.init(this)
        getSharedPreferences("sources", MODE_PRIVATE).let { p ->
            app.couchy.core.sources.Sites.hosts = app.couchy.core.sources.Sites.mirrors.keys.associateWith { p.getString(it, "").orEmpty() }
        }
        app.couchy.sync.WebDav.FILE = "couchy-sync${app.couchy.data.Profiles.suffix(this)}.json"
        // Every connection asks the bypass which way to go, so it is in place before anything loads.
        bypass.install()
        app.couchy.episodes.NewEpisodes.schedule(this)
        // On a TV, what is half-watched goes to the home screen's "Продолжить просмотр" row.
        // The home-screen widget follows the library.
        scope.launch {
            library.all.debounce(2_000).collect { runCatching { app.couchy.widget.ContinueWidget.draw(this@CouchyApp, it) } }
        }
        if (app.couchy.tvhome.TvHome.isTv(this)) {
            scope.launch {
                library.all.debounce(3_000).collect { runCatching { app.couchy.tvhome.TvHome.publish(this@CouchyApp, it) } }
            }
        }
    }
}
