package app.couchy.ui.youtube

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couchy.CouchyApp
import app.couchy.ui.components.SecondaryButton
import app.couchy.youtube.MusicApi

private const val SIGN_IN = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/**
 * Google's own sign-in page in a window. The person types their login there; when YouTube Music
 * opens behind it, Couchy keeps the cookies Google gave and the window closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MusicLoginDialog(onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bypass = (context.applicationContext as CouchyApp).bypass
    val ready by produceState(false) {
        bypass.prepare("https://music.youtube.com")
        value = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Войдите в Google на его странице. Пароль видит только Google, Couchy хранит лишь вход на этом устройстве.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton("Закрыть", null, onClick = onDismiss)
            }
            if (!ready) return@Column
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google turns away sign-in from pages that call themselves an app's window.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex(" Version/[0-9.]+"), "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            private var done = false
                            override fun onPageFinished(view: WebView, loaded: String?) {
                                if (done || loaded?.startsWith("https://music.youtube.com") != true) return
                                CookieManager.getInstance().flush()
                                if (MusicApi.takeFromWebView(view.context)) {
                                    done = true
                                    onSignedIn()
                                }
                            }
                        }
                        loadUrl(SIGN_IN)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
