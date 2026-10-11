package app.couchy

import android.app.Application
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
}
