package app.couchy

import android.app.Application
import app.couchy.bypass.Bypass
import app.couchy.data.CouchyDatabase
import app.couchy.data.LibraryRepository
import app.couchy.data.Settings
import app.couchy.torrent.TorrEngine
import app.couchy.tv.TvDatabase
import app.couchy.tv.TvRepository

class CouchyApp : Application() {
    val database by lazy { CouchyDatabase.create(this) }
    val library by lazy { LibraryRepository(this, database.media()) }
    val settings by lazy { Settings(this) }
    val tvDatabase by lazy { TvDatabase.create(this) }
    val tv by lazy { TvRepository(tvDatabase.tv()) }
    val torrEngine by lazy { TorrEngine(this) }
    val bypass by lazy { Bypass(this, settings) }
    val cast by lazy { app.couchy.cast.Cast(this) }
    val marks by lazy { app.couchy.data.Marks(this) }
    val sync by lazy { app.couchy.sync.Sync(this, settings, library, tv, marks) }

    override fun onCreate() {
        super.onCreate()
        QualityChoice.init(this)
        // Every connection asks the bypass which way to go, so it is in place before anything loads.
        bypass.install()
    }
}
