package app.couchy

import android.app.Application
import app.couchy.data.CouchyDatabase
import app.couchy.data.LibraryRepository
import app.couchy.data.Settings

class CouchyApp : Application() {
    val database by lazy { CouchyDatabase.create(this) }
    val library by lazy { LibraryRepository(this, database.media()) }
    val settings by lazy { Settings(this) }
}
