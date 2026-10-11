package app.couchy.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where the shared sync file lives: a WebDAV folder, Google Диск or Яндекс Диск. */
interface SyncDisk {
    /** The file's text, or null when there is none yet. */
    suspend fun read(): String?
    suspend fun write(text: String)
}

private fun connection(url: String, method: String, auth: String?): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        auth?.let { setRequestProperty("Authorization", it) }
        setRequestProperty("User-Agent", "Couchy/1.0 (Android)")
    }

private fun HttpURLConnection.text(): String = inputStream.bufferedReader().use { it.readText() }

private fun HttpURLConnection.send(body: ByteArray, type: String) {
    doOutput = true
    setRequestProperty("Content-Type", type)
    outputStream.use { it.write(body) }
}

private fun cloudError(code: Int) = IOException(
    when (code) {
        401, 403 -> "Нет доступа к диску: войдите ещё раз."
        507 -> "На диске закончилось место."
        else -> "Диск ответил $code."
    },
)

/**
 * Google Диск: the file sits in the app's hidden folder, which only Couchy sees; the rest of the
 * person's Drive stays closed to it.
 */
class GoogleDrive(private val token: suspend () -> String) : SyncDisk {
    private suspend fun fileId(auth: String): String? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("name = '${WebDav.FILE}'", "UTF-8")
        val c = connection("$API/files?spaces=appDataFolder&fields=files(id)&q=$q", "GET", auth)
        try {
            if (c.responseCode != 200) throw cloudError(c.responseCode)
            JSONObject(c.text()).getJSONArray("files").optJSONObject(0)?.getString("id")
        } finally {
            c.disconnect()
        }
    }

    override suspend fun read(): String? {
        val auth = "Bearer " + token()
        val id = fileId(auth) ?: return null
        return withContext(Dispatchers.IO) {
            val c = connection("$API/files/$id?alt=media", "GET", auth)
            try {
                if (c.responseCode != 200) throw cloudError(c.responseCode)
                c.text()
            } finally {
                c.disconnect()
            }
        }
    }

    override suspend fun write(text: String) {
        val auth = "Bearer " + token()
        val id = fileId(auth)
        withContext(Dispatchers.IO) {
            val c = if (id != null) {
                // HttpURLConnection has no PATCH; Google takes the method from this header.
                connection("$UPLOAD/files/$id?uploadType=media", "POST", auth).apply {
                    setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    send(text.toByteArray(), "application/json; charset=utf-8")
                }
            } else {
                val boundary = "couchy" + System.nanoTime()
                val meta = JSONObject().put("name", WebDav.FILE).put("parents", org.json.JSONArray().put("appDataFolder"))
                val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                    "--$boundary\r\nContent-Type: application/json\r\n\r\n$text\r\n--$boundary--"
                connection("$UPLOAD/files?uploadType=multipart", "POST", auth).apply {
                    send(body.toByteArray(), "multipart/related; boundary=$boundary")
                }
            }
            try {
                if (c.responseCode !in 200..299) throw cloudError(c.responseCode)
            } finally {
                c.disconnect()
            }
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }
}

/** Signing in to Google with the account already on the device; access only to Couchy's own hidden folder. */
object GoogleAuth {
    private const val APPDATA = "https://www.googleapis.com/auth/drive.appdata"

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(APPDATA))).build()

    private suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request()).await()

    /** A fresh token without any screen; works once the person has agreed. */
    suspend fun token(context: Context): String =
        authorize(context).accessToken ?: throw IOException("Войдите в Google ещё раз: Настройки → Синхронизация.")

    /** The whole sign-in: Google's screen when it is needed, then an error message, or null when access is granted. */
    suspend fun signIn(activity: Activity, launch: suspend (IntentSenderRequest) -> ActivityResult): String? = try {
        var result = authorize(activity)
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) {
            val answer = launch(IntentSenderRequest.Builder(pending.intentSender).build())
            if (answer.resultCode != Activity.RESULT_OK) throw IOException("Вход отменён.")
            result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(answer.data ?: Intent())
        }
        if (result.accessToken == null) "Google не выдал доступ к диску." else null
    } catch (e: Exception) {
        e.message ?: "Google не ответил."
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(IOException(message(it), it)) }
    }

    private fun message(e: Exception): String {
        val text = e.message.orEmpty()
        // 10 = DEVELOPER_ERROR: the app is not registered in Google Cloud yet.
        return if (text.startsWith("10:") || "DEVELOPER_ERROR" in text) "Google ещё не знает Couchy: нужна настройка в Google Cloud."
        else "Google не пустил: ${text.ifEmpty { "неизвестная ошибка" }}"
    }
}

/**
 * Яндекс Диск through its REST API with a token from the Yandex account on the device. The file
 * sits in "Приложения/Couchy", the only folder the token opens.
 */
class YandexDisk(private val token: String) : SyncDisk {
    private val auth get() = "OAuth $token"
    private val path = URLEncoder.encode("app:/${WebDav.FILE}", "UTF-8")

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val c = connection("$API/resources/download?path=$path", "GET", auth)
        val href = try {
            when (c.responseCode) {
                200 -> JSONObject(c.text()).getString("href")
                404 -> return@withContext null
                else -> throw cloudError(c.responseCode)
            }
        } finally {
            c.disconnect()
        }
        val d = connection(href, "GET", null)
        try {
            if (d.responseCode != 200) throw cloudError(d.responseCode)
            d.text()
        } finally {
            d.disconnect()
        }
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val c = connection("$API/resources/upload?path=$path&overwrite=true", "GET", auth)
        val href = try {
            if (c.responseCode != 200) throw cloudError(c.responseCode)
            JSONObject(c.text()).getString("href")
        } finally {
            c.disconnect()
        }
        val u = connection(href, "PUT", null)
        try {
            u.send(text.toByteArray(), "application/json; charset=utf-8")
            if (u.responseCode !in 200..299) throw cloudError(u.responseCode)
        } finally {
            u.disconnect()
        }
    }

    /** The account's login, to show which one is connected. */
    suspend fun login(): String = withContext(Dispatchers.IO) {
        runCatching {
            val c = connection("https://login.yandex.ru/info?format=json", "GET", auth)
            try {
                JSONObject(c.text()).let { it.optString("default_email").ifBlank { it.optString("login") } }
            } finally {
                c.disconnect()
            }
        }.getOrDefault("").ifBlank { "Яндекс" }
    }

    private companion object {
        const val API = "https://cloud-api.yandex.net/v1/disk"
    }
}

/** Yandex sign-in works once the app is registered on oauth.yandex.ru and its id is built in. */
object YandexAuth {
    val configured: Boolean get() = app.couchy.BuildConfig.YANDEX_CLIENT_ID.isNotBlank()
}
