package com.safeview.app

import android.annotation.SuppressLint
import android.app.DownloadManager
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

private const val HOME_URL = "https://safeview.local/home"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusChip: TextView
    private lateinit var prefs: SettingsPrefs
    private lateinit var bridge: SafeViewBridge
    private var contentScript: String = ""
    private var bridgeAttached: Boolean = false
    private var showingBlockPage: Boolean = false
    private val tabUrls = mutableListOf<String>()
    private val browserPrefs by lazy { getSharedPreferences("safeview_browser", MODE_PRIVATE) }

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
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        val btnTabs = findViewById<TextView>(R.id.btnTabs)

        setupWebView()
        updateStatusChip()

        btnGo.setOnClickListener { navigateToBar() }
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnHome.setOnClickListener { showHomePage() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnMenu.setOnClickListener { showBrowserMenu(btnMenu) }
        btnTabs.setOnClickListener { showTabsDialog(btnTabs) }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToBar()
                true
            } else false
        }

        tabUrls += HOME_URL
        updateTabCount(btnTabs)
        showHomePage()
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
                if (!showingBlockPage && url != null && url.startsWith("https://")) {
                    recordHistory(url)
                    if (tabUrls.isNotEmpty()) tabUrls[tabUrls.lastIndex] = url
                }
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

    private fun showHomePage() {
        showingBlockPage = true
        webView.loadDataWithBaseURL(HOME_URL, homePageHtml(), "text/html", "UTF-8", null)
        urlBar.setText("")
        showingBlockPage = false
    }

    private fun homePageHtml(): String = """
        <html>
        <meta name='viewport' content='width=device-width, initial-scale=1'>
        <style>$SHARED_PAGE_STYLE
            .shield { margin: 0 auto 18px; }
            .actions { margin-top: 22px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
            .pill { display: inline-block; padding: 6px 14px; border-radius: 999px;
                background: #0F1A2B; border: 1px solid #24334A; color: #93A2BA; font-size: 13px; }
        </style>
        <body>
            <div class="card">
                $SHIELD_SVG
                <h1>SafeView</h1>
                <p class="lead">Protected browsing with local, on-device filtering.</p>
                <p class="hint">Use the address bar above to search or enter a website.</p>
                <div class="actions"><span class="pill">Protection is active</span></div>
            </div>
        </body>
        </html>
    """.trimIndent()

    private fun showBrowserMenu(anchor: ImageButton) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("Reload")
        menu.menu.add("Browsing history")
        menu.menu.add("Downloads")
        menu.menu.add("Add bookmark")
        menu.menu.add("Bookmarks")
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Reload" -> webView.reload()
                "Browsing history" -> showHistoryDialog()
                "Downloads" -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                "Add bookmark" -> addBookmark()
                "Bookmarks" -> showBookmarksDialog()
            }
            true
        }
        menu.show()
    }

    private fun showTabsDialog(tabButton: TextView) {
        val labels = tabUrls.mapIndexed { index, url -> "Tab ${index + 1}: ${url.ifBlank { "SafeView home" }}" }
        AlertDialog.Builder(this).setTitle("Tabs (${tabUrls.size})")
            .setItems(labels.toTypedArray()) { _, which -> if (which in tabUrls.indices) webView.loadUrl(tabUrls[which]) }
            .setPositiveButton("New tab") { _, _ -> tabUrls += HOME_URL; updateTabCount(tabButton); showHomePage() }
            .setNegativeButton("Close", null).show()
    }

    private fun updateTabCount(view: TextView) { view.text = tabUrls.size.toString() }

    private fun recordHistory(url: String) {
        val history = browserPrefs.getStringSet("history", emptySet()).orEmpty().toMutableList()
        history.remove(url)
        history.add(0, url)
        browserPrefs.edit().putStringSet("history", history.take(50).toSet()).apply()
    }

    private fun showHistoryDialog() {
        val history = browserPrefs.getStringSet("history", emptySet()).orEmpty().toList()
        AlertDialog.Builder(this).setTitle("Browsing history")
            .setItems(if (history.isEmpty()) arrayOf("No history yet") else history.toTypedArray()) { _, which -> if (which < history.size) webView.loadUrl(history[which]) }
            .setNegativeButton("Close", null).show()
    }

    private fun addBookmark() {
        webView.url?.takeIf { it.startsWith("https://") }?.let { url ->
            val bookmarks = browserPrefs.getStringSet("bookmarks", emptySet()).orEmpty().toMutableSet()
            bookmarks.add(url)
            browserPrefs.edit().putStringSet("bookmarks", bookmarks).apply()
        }
    }

    private fun showBookmarksDialog() {
        val bookmarks = browserPrefs.getStringSet("bookmarks", emptySet()).orEmpty().toList()
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(if (bookmarks.isEmpty()) arrayOf("No bookmarks yet") else bookmarks.toTypedArray()) { _, which -> if (which < bookmarks.size) webView.loadUrl(bookmarks[which]) }
            .setNegativeButton("Close", null).show()
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
        webView.loadDataWithBaseURL("https://safeview.local/", blockedPageHtml(), "text/html", "UTF-8", null)
        urlBar.setText("")
        showingBlockPage = false
        updateStatusChip()
    }

    private fun blockedPageHtml(): String = """
        <html>
        <meta name='viewport' content='width=device-width, initial-scale=1'>
        <style>$SHARED_PAGE_STYLE
            .shield circle, .shield path { fill: #F5C168; }
        </style>
        <body>
            <div class="card">
                $BLOCK_SVG
                <h1>Content blocked</h1>
                <p class="lead">This page was blocked by SafeView's protection settings.</p>
                <p class="hint">No page media was loaded.</p>
            </div>
        </body>
        </html>
    """.trimIndent()

    companion object {
        /** Shared inline CSS for SafeView's own in-app pages (home, blocked). */
        private const val SHARED_PAGE_STYLE = """
            :root { color-scheme: dark; }
            * { box-sizing: border-box; }
            body {
                margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
                background: #0B1220; color: #F3F7FF;
                font-family: -apple-system, Roboto, 'Segoe UI', sans-serif;
                padding: 28px;
            }
            .card {
                max-width: 360px; width: 100%; text-align: center;
                background: #111B2C; border: 1px solid #24334A; border-radius: 20px;
                padding: 32px 24px;
            }
            .shield { width: 56px; height: 56px; }
            h1 { font-size: 20px; margin: 4px 0 10px; }
            p { margin: 0 0 6px; line-height: 1.4; }
            .lead { font-size: 14px; color: #F3F7FF; }
            .hint { font-size: 13px; color: #93A2BA; }
        """

        private const val SHIELD_SVG = """
            <svg class="shield" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path fill="#6EA8FF" d="M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z"/>
                <path fill="#F3F7FF" d="M10.3,14.9l-2.8,-2.8 1.05,-1.05 1.75,1.75 4.05,-4.05 1.05,1.05z"/>
            </svg>
        """

        private const val BLOCK_SVG = """
            <svg class="shield" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path fill="#F5C168" d="M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z"/>
                <rect x="11" y="7" width="2" height="7" rx="1" fill="#111B2C"/>
                <rect x="11" y="15.5" width="2" height="2" rx="1" fill="#111B2C"/>
            </svg>
        """
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
