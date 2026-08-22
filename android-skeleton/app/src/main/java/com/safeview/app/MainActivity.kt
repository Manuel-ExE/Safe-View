package com.safeview.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusChip: TextView
    private lateinit var prefs: SettingsPrefs
    private lateinit var bridge: SafeViewBridge
    private var contentScript: String = ""
    private var bridgeAttached: Boolean = false
    private var showingBlockPage: Boolean = false

    private val strictSearchTerms = setOf(
        "porn", "porno", "pornhub", "xvideos", "xnxx", "sex video", "sexual video",
        "nude sex", "naked sex", "hentai", "xxx", "explicit sex"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = SettingsPrefs(this)
        contentScript = loadAsset("safeview-content.js")

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        statusChip = findViewById(R.id.statusChip)

        val btnGo = findViewById<ImageButton>(R.id.btnGo)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnHome = findViewById<ImageButton>(R.id.btnHome)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        setupWebView()
        updateStatusChip()

        btnGo.setOnClickListener { navigateToBar() }
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnHome.setOnClickListener {
            webView.loadUrl("https://www.pinterest.com/")
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToBar()
                true
            } else false
        }

        webView.loadUrl("https://www.pinterest.com/")
    }

    override fun onResume() {
        super.onResume()
        updateStatusChip()
        if (webView.url != null && webView.url != "about:blank") {
            injectSafeView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = settings.userAgentString.replace("; wv", "") + " SafeView/1.2.3"

        val app = application as SafeViewApp
        bridge = SafeViewBridge(webView, app, prefs)
        // Defaults: origin allowlist + fetch CDN allowlist (see SafeViewBridge companion)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!showingBlockPage && url != null && shouldBlockUrl(url)) {
                    showBlockedPage()
                    return
                }
                bridge.onPageStarted(url)
                // Detach bridge until we know the finished origin is allowed
                detachBridge()
                url?.let { if (!it.startsWith("about:")) urlBar.setText(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                syncBridgeForUrl(url)
                injectSafeView()
                updateStatusChip()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return true
                if (shouldBlockUrl(uri.toString())) {
                    showBlockedPage()
                    return true
                }
                return when (uri.scheme?.lowercase()) {
                    "https" -> false
                    else -> true
                }
            }
        }
    }

    /** Only expose SafeViewAndroid on allowlisted origins. */
    private fun syncBridgeForUrl(url: String?) {
        if (bridge.isOriginAllowed(url)) {
            attachBridge()
        } else {
            detachBridge()
        }
    }

    private fun attachBridge() {
        if (bridgeAttached) return
        webView.addJavascriptInterface(bridge, "SafeViewAndroid")
        bridgeAttached = true
    }

    private fun detachBridge() {
        if (!bridgeAttached) return
        try {
            webView.removeJavascriptInterface("SafeViewAndroid")
        } catch (_: Exception) {
        }
        bridgeAttached = false
    }

    private fun navigateToBar() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("https://", ignoreCase = true)) {
            url = if (url.contains(".") && !url.contains(" ")) {
                "https://$url"
            } else {
                "https://www.google.com/search?tbm=isch&q=" +
                    java.net.URLEncoder.encode(url, "UTF-8")
            }
        }
        if (shouldBlockUrl(url)) {
            showBlockedPage()
        } else {
            webView.loadUrl(url)
        }
    }

    private fun shouldBlockUrl(rawUrl: String): Boolean {
        if (!prefs.enabled) return false
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return true }
        if (uri.scheme?.lowercase() != "https") return true
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return true
        val blockedDomain = prefs.blockedDomains.any { domain ->
            val normalized = domain.lowercase().removePrefix("www.")
            host == normalized || host.endsWith(".$normalized")
        }
        if (blockedDomain) return true
        if (prefs.strict) {
            val text = listOfNotNull(uri.path, uri.query, uri.fragment).joinToString(" ").lowercase()
            if (strictSearchTerms.any { text.contains(it) }) return true
        }
        return false
    }

    private fun showBlockedPage() {
        showingBlockPage = true
        webView.stopLoading()
        webView.loadDataWithBaseURL(
            "https://safeview.local/",
            "<html><meta name='viewport' content='width=device-width'><body style='background:#101827;color:#fff;font-family:sans-serif;padding:32px'><h1>Content blocked by SafeView</h1><p>This page was blocked because Strict protection is enabled.</p><p>No page media was loaded.</p></body></html>",
            "text/html",
            "UTF-8",
            null
        )
        urlBar.setText("")
        showingBlockPage = false
        updateStatusChip()
    }

    private fun injectSafeView() {
        if (contentScript.isEmpty()) return
        val originOk = bridge.isOriginAllowed(webView.url)
        val settingsJson = org.json.JSONObject(prefs.toJsObject())
            .put("nonce", bridge.currentNonce())
            .put("generation", bridge.currentGeneration())
            .put(
                "aiEnabled",
                prefs.aiEnabled && originOk && bridgeAttached &&
                    (application as SafeViewApp).classifier.isReady
            )
            .put("originAllowed", originOk && bridgeAttached)
            .toString()
        val settingsJs = "window.SafeViewNativeSettings = $settingsJson;"
        val updateJs = "if (window.__safeviewInjected && window.SafeViewUpdateSettings) " +
            "window.SafeViewUpdateSettings($settingsJson);"
        webView.evaluateJavascript(settingsJs + "\n" + updateJs + "\n" + contentScript, null)
    }

    private fun updateStatusChip() {
        val app = application as? SafeViewApp
        val originOk = if (::bridge.isInitialized) bridge.isOriginAllowed(webView.url) else false
        val aiReady = app?.let {
            it.aiPipelineAvailable && it.classifier.isReady
        } == true && prefs.aiEnabled && originOk && bridgeAttached
        val protection = if (prefs.enabled) "Protection on" else "Protection off"
        val mode = when {
            aiReady -> "AI + Heuristics"
            prefs.aiEnabled && app?.classifier?.isReady != true -> "Heuristics (AI model missing)"
            prefs.aiEnabled && !originOk -> "Heuristics (origin not on AI allowlist)"
            else -> "Heuristics"
        }
        statusChip.text = "$protection · $mode"
        statusChip.setTextColor(
            getColor(if (prefs.enabled) R.color.sv_ok else R.color.sv_muted)
        )
    }

    private fun loadAsset(name: String): String {
        return try {
            assets.open(name).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
