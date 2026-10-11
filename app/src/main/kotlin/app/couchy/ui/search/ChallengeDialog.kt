package app.couchy.ui.search

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

/**
 * The site's own visitor check, shown in a browser window so the person passes it
 * themselves. When the page behind it opens, the window closes and Couchy carries on
 * with the same cookies.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChallengeDialog(url: String, userAgent: String, onPassed: () -> Unit, onDismiss: () -> Unit) {
    val bypass = (LocalContext.current.applicationContext as CouchyApp).bypass
    val ready by produceState(false, url) {
        bypass.prepare(url)
        value = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Сайт проверяет, что вы не бот. Обычно это проходит само за пару секунд; если появится кнопка, нажмите её.",
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
                        settings.userAgentString = userAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            private var done = false
                            override fun onPageFinished(view: WebView, loaded: String?) {
                                if (done) return
                                // Still on the check page while its markers are there.
                                view.evaluateJavascript(
                                    "(function(){var h=document.documentElement.innerHTML;" +
                                        "return (h.indexOf('anubis_challenge')>=0||h.indexOf('challenge-platform')>=0||document.title.indexOf('Just a moment')>=0)?'wait':'ok';})()",
                                ) { answer ->
                                    if (!done && answer?.contains("ok") == true) {
                                        done = true
                                        CookieManager.getInstance().flush()
                                        onPassed()
                                    }
                                }
                            }
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
