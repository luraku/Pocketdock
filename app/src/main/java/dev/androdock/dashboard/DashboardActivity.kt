package dev.androdock.dashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.androdock.AndrodockApp
import dev.androdock.BuildConfig
import dev.androdock.terminal.TerminalActivity

/**
 * Tableau de bord web (assets/web) affiché dans une WebView. Il est servi par l'API de
 * contrôle locale et la pilote ; le même tableau de bord est accessible depuis un
 * navigateur d'ordinateur via `adb forward tcp:7777 tcp:7777`.
 */
class DashboardActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as AndrodockApp
        // Ouvrir le tableau de bord vaut consentement à l'API de contrôle locale.
        if (!app.settings.config.value.controlApi) app.settings.update { it.copy(controlApi = true) }
        app.control.start()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0d1017"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "AndrodockBridge")
            // Erreurs JavaScript du tableau de bord dans logcat (diagnostic des tests e2e)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("AndrodockDashboard", "console ${m.messageLevel()} ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == "127.0.0.1" && url.port == BuildConfig.CONTROL_PORT) return false
                    startActivity(Intent(Intent.ACTION_VIEW, url)); return true
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(web)
        val view = intent.getStringExtra(EXTRA_VIEW) ?: "overview"
        web.loadUrl("http://127.0.0.1:${BuildConfig.CONTROL_PORT}/#token=${app.control.token}&view=$view")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** Fonctions natives exposées au tableau de bord (window.AndrodockBridge). */
    inner class Bridge {
        @JavascriptInterface
        fun openTerminal(kind: String, arg: String) = runOnUiThread {
            val k = if (kind == "machine") TerminalActivity.KIND_MACHINE else TerminalActivity.KIND_EXEC
            startActivity(TerminalActivity.intent(this@DashboardActivity, k, arg, arg))
        }

        /** Appelé par le tableau de bord une fois une vue rendue avec des données (vérifié par l'e2e). */
        @JavascriptInterface
        fun ready(view: String, engine: String, pods: Int, containers: Int) {
            android.util.Log.i("AndrodockDashboard", "ready view=$view engine=$engine pods=$pods containers=$containers")
        }

        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread {
            val uri = Uri.parse(url)
            if (uri.scheme == "http" || uri.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    companion object {
        const val EXTRA_VIEW = "view"
    }
}
