package com.safeview.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

private const val HOME_URL = "https://safeview.local/home"

class MainActivity : AppCompatActivity() {

    /**
     * One WebView (and its own SafeViewBridge instance) per browser tab, so
     * switching tabs preserves each tab's navigation back-stack, scroll
     * position, and in-page JS state instead of reloading a bare URL string.
     * Note: like real browsers, cookies/localStorage are still shared across
     * tabs by origin (that's normal WebView/CookieManager behavior, not a
     * bug) — what per-tab WebViews fix is losing each tab's own session state
     * on switch.
     */
    private inner class BrowserTab(val webView: WebView) {
        val bridge: SafeViewBridge = SafeViewBridge(webView, application as SafeViewApp, prefs)
        var bridgeAttached: Boolean = false
        var url: String = HOME_URL
        var showingBlockPage: Boolean = false
    }

    private lateinit var webViewContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var statusChip: TextView
    private lateinit var tabsOverlayContainer: FrameLayout
    private lateinit var prefs: SettingsPrefs
    private var contentScript: String = ""
    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex: Int = -1
    private val browserPrefs by lazy { getSharedPreferences("safeview_browser", MODE_PRIVATE) }

    private val currentTab: BrowserTab? get() = tabs.getOrNull(currentTabIndex)
    private val currentWebView: WebView? get() = currentTab?.webView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = SettingsPrefs(this)
        contentScript = loadAsset("safeview-content.js")

        webViewContainer = findViewById(R.id.webViewContainer)
        urlBar = findViewById(R.id.urlBar)
        statusChip = findViewById(R.id.statusChip)
        tabsOverlayContainer = findViewById(R.id.tabsOverlayContainer)

        val btnGo = findViewById<ImageButton>(R.id.btnGo)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnHome = findViewById<ImageButton>(R.id.btnHome)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        val btnTabs = findViewById<TextView>(R.id.btnTabs)

        btnGo.setOnClickListener { navigateToBar() }
        btnBack.setOnClickListener {
            currentWebView?.let { if (it.canGoBack()) it.goBack() }
        }
        btnHome.setOnClickListener { showHomePage() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnMenu.setOnClickListener { showBrowserMenu(btnMenu) }
        btnTabs.setOnClickListener { showTabsGrid() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToBar()
                true
            } else false
        }

        openNewTab(btnTabs)
        showHomePage()
    }

    override fun onResume() {
        super.onResume()
        updateStatusChip()
        val tab = currentTab ?: return
        if (tab.webView.url != null && tab.webView.url != "about:blank") {
            injectSafeView(tab)
        }
    }

    override fun onDestroy() {
        tabs.forEach { destroyTab(it) }
        tabs.clear()
        super.onDestroy()
    }

    // ---- Tab management ----------------------------------------------------

    private fun openNewTab(tabsButton: TextView): BrowserTab {
        val webView = createWebView()
        val tab = BrowserTab(webView)
        tabs += tab
        webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        switchToTab(tabs.lastIndex)
        updateTabCount(tabsButton)
        return tab
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index
        tabs.forEachIndexed { i, tab ->
            tab.webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        val tab = tabs[index]
        urlBar.setText(tab.url.takeIf { it != HOME_URL } ?: "")
        updateStatusChip()
        if (tab.webView.url != null && tab.webView.url != "about:blank") {
            injectSafeView(tab)
        }
    }

    private fun closeTab(index: Int, tabsButton: TextView) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            // Always keep at least one tab; reset it to the home page instead.
            showHomePage()
            return
        }
        val tab = tabs.removeAt(index)
        destroyTab(tab)
        val newIndex = (index - 1).coerceAtLeast(0)
        switchToTab(newIndex.coerceAtMost(tabs.lastIndex))
        updateTabCount(tabsButton)
    }

    private fun destroyTab(tab: BrowserTab) {
        try {
            webViewContainer.removeView(tab.webView)
            tab.webView.stopLoading()
            tab.webView.webViewClient = WebViewClient()
            tab.webView.webChromeClient = null
            if (tab.bridgeAttached) {
                try { tab.webView.removeJavascriptInterface("SafeViewAndroid") } catch (_: Exception) {}
            }
            tab.webView.destroy()
        } catch (_: Exception) {
        }
    }

    private fun updateTabCount(view: TextView) { view.text = tabs.size.toString() }

    // ---- WebView setup -------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val webView = WebView(this)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = settings.userAgentString.replace("; wv", "") + " SafeView/1.5.1"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val tab = tabForWebView(view) ?: return
                if (!tab.showingBlockPage && url != null && shouldBlockUrl(url)) {
                    showBlockedPage(tab)
                    return
                }
                tab.bridge.onPageStarted(url)
                // Detach bridge until we know the finished origin is allowed
                detachBridge(tab)
                if (tab === currentTab && url != null && !url.startsWith("about:")) {
                    urlBar.setText(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = tabForWebView(view) ?: return
                syncBridgeForUrl(tab, url)
                if (!tab.showingBlockPage && url != null && url.startsWith("https://")) {
                    recordHistory(url)
                    tab.url = url
                }
                injectSafeView(tab)
                if (tab === currentTab) updateStatusChip()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val tab = tabForWebView(view) ?: return true
                val uri = request?.url ?: return true
                if (handleHomeAction(uri.toString())) return true
                if (shouldBlockUrl(uri.toString())) {
                    showBlockedPage(tab)
                    return true
                }
                return when (uri.scheme?.lowercase()) {
                    "https" -> false
                    else -> true
                }
            }
        }
        return webView
    }

    private fun tabForWebView(view: WebView?): BrowserTab? = tabs.firstOrNull { it.webView === view }

    /** Only expose SafeViewAndroid on allowlisted origins. */
    private fun syncBridgeForUrl(tab: BrowserTab, url: String?) {
        if (tab.bridge.isOriginAllowed(url)) {
            attachBridge(tab)
        } else {
            detachBridge(tab)
        }
    }

    private fun attachBridge(tab: BrowserTab) {
        if (tab.bridgeAttached) return
        tab.webView.addJavascriptInterface(tab.bridge, "SafeViewAndroid")
        tab.bridgeAttached = true
    }

    private fun detachBridge(tab: BrowserTab) {
        if (!tab.bridgeAttached) return
        try {
            tab.webView.removeJavascriptInterface("SafeViewAndroid")
        } catch (_: Exception) {
        }
        tab.bridgeAttached = false
    }

    // ---- Navigation ----------------------------------------------------------

    private fun navigateToBar() {
        val tab = currentTab ?: return
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
            showBlockedPage(tab)
        } else {
            tab.webView.loadUrl(url)
        }
    }

    /**
     * Chrome-style New Tab page: wordmark, pill search box, quick-action
     * chips, a row of safe shortcut sites, and a protection-status footer
     * (in place of Chrome's Discover feed toggle).
     */
    private fun showHomePage() {
        val tab = currentTab ?: return
        tab.showingBlockPage = true
        val protectionOn = prefs.enabled
        val strictOn = prefs.strict
        val statusLine = if (protectionOn) {
            "Protection on \u00b7 ${if (strictOn) "Strict" else "Standard"} filtering"
        } else {
            "Protection off"
        }
        val shortcuts = listOf(
            Triple("Google", "G", "https://www.google.com/"),
            Triple("Bing", "B", "https://www.bing.com/"),
            Triple("DuckDuckGo", "D", "https://duckduckgo.com/"),
            Triple("Wikipedia", "W", "https://www.wikipedia.org/")
        )
        val shortcutsHtml = shortcuts.joinToString("") { (name, letter, url) ->
            "<a class='shortcut' href='$url'><span class='sicon'>$letter</span><span class='slabel'>$name</span></a>"
        }
        val html = """
            <html><meta name='viewport' content='width=device-width, initial-scale=1'>
            <style>
              body{margin:0;background:#0B1220;color:#F3F7FF;font-family:sans-serif}
              .wrap{max-width:640px;margin:auto;padding:52px 22px 24px}
              .logo{font-size:40px;font-weight:700;letter-spacing:-1px;text-align:center;margin-bottom:22px;color:#F3F7FF}
              .logo span{color:#6EA8FF}
              .searchbar{display:flex;align-items:center;background:#1B2740;border-radius:28px;padding:14px 20px;margin-bottom:16px}
              .searchbar input{flex:1;background:none;border:none;outline:none;color:#F3F7FF;font-size:16px}
              .pills{display:flex;gap:10px;justify-content:center;margin-bottom:28px}
              .pill{background:#1B2740;color:#DCE4F5;border-radius:20px;padding:9px 16px;font-size:13px;text-decoration:none}
              .shortcuts{display:flex;gap:14px;justify-content:center;flex-wrap:wrap;margin-bottom:34px}
              .shortcut{display:flex;flex-direction:column;align-items:center;width:64px;text-decoration:none;color:#DCE4F5}
              .sicon{width:44px;height:44px;border-radius:22px;background:#182338;border:1px solid #2c3b55;color:#6EA8FF;font-size:18px;font-weight:700;display:flex;align-items:center;justify-content:center;margin-bottom:6px}
              .slabel{font-size:11px;text-align:center;max-width:64px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
              .footer{text-align:center;color:#9EACC2;font-size:12px;padding-top:8px;border-top:1px solid #182338}
            </style>
            <body>
              <main class='wrap'>
                <div class='logo'>Safe<span>View</span></div>
                <form class='searchbar' action='https://www.google.com/search' method='get'>
                  <input name='q' placeholder='Search safely or type a URL' />
                </form>
                <div class='pills'>
                  <a class='pill' href='https://safeview.local/action/settings'>&#128737; Protection center</a>
                  <a class='pill' href='https://safeview.local/action/newtab'>&#10011; New tab</a>
                </div>
                <div class='shortcuts'>$shortcutsHtml</div>
                <div class='footer'>$statusLine</div>
              </main>
            </body></html>
        """.trimIndent()
        tab.webView.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null)
        tab.url = HOME_URL
        urlBar.setText("")
        tab.showingBlockPage = false
    }

    /** Handles the pseudo-links used by the New Tab page's action pills. */
    private fun handleHomeAction(url: String): Boolean {
        return when (url) {
            "https://safeview.local/action/settings" -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            "https://safeview.local/action/newtab" -> {
                openNewTab(findViewById(R.id.btnTabs))
                showHomePage()
                true
            }
            else -> false
        }
    }

    private fun showBrowserMenu(anchor: ImageButton) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("Reload")
        menu.menu.add("New tab")
        menu.menu.add("Close tab")
        menu.menu.add("Browsing history")
        menu.menu.add("Downloads")
        menu.menu.add("Add bookmark")
        menu.menu.add("Bookmarks")
        menu.menu.add("Protection center")
        menu.menu.add("Blocked domains")
        menu.menu.add("Blocked search terms")
        menu.setOnMenuItemClickListener { item ->
            val tabsButton = findViewById<TextView>(R.id.btnTabs)
            when (item.title.toString()) {
                "Reload" -> currentWebView?.reload()
                "New tab" -> { openNewTab(tabsButton); showHomePage() }
                "Close tab" -> closeTab(currentTabIndex, tabsButton)
                "Browsing history" -> showHistoryDialog()
                "Downloads" -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                "Add bookmark" -> addBookmark()
                "Bookmarks" -> showBookmarksDialog()
                "Protection center" -> startActivity(Intent(this, SettingsActivity::class.java))
                "Blocked domains" -> startActivity(Intent(this, BlockedDomainsActivity::class.java))
                "Blocked search terms" -> startActivity(Intent(this, BlockedTermsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    // ---- Chrome-style tab grid overlay ---------------------------------------

    private fun showTabsGrid(filter: String = "") {
        tabsOverlayContainer.removeAllViews()
        val overlay = LayoutInflater.from(this).inflate(R.layout.view_tabs_overlay, tabsOverlayContainer, false)
        tabsOverlayContainer.addView(overlay)
        tabsOverlayContainer.visibility = View.VISIBLE

        val grid = overlay.findViewById<GridLayout>(R.id.tabsGrid)
        val countLabel = overlay.findViewById<TextView>(R.id.tabsCountLabel)
        val searchBox = overlay.findViewById<EditText>(R.id.tabsSearchBox)
        val addBtn = overlay.findViewById<ImageButton>(R.id.tabsAddBtn)
        val doneBtn = overlay.findViewById<TextView>(R.id.tabsDoneBtn)

        countLabel.text = if (tabs.size == 1) "1 tab" else "${tabs.size} tabs"
        searchBox.setText(filter)

        fun populate(query: String) {
            grid.removeAllViews()
            val q = query.trim().lowercase()
            tabs.forEachIndexed { index, tab ->
                val label = if (tab.url == HOME_URL) "New tab" else tab.url
                if (q.isNotEmpty() && !label.lowercase().contains(q)) return@forEachIndexed
                val card = LayoutInflater.from(this).inflate(R.layout.item_tab_card, grid, false)
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                )
                params.width = 0
                params.setMargins(6, 6, 6, 6)
                card.layoutParams = params
                if (index == currentTabIndex) {
                    card.setBackgroundResource(R.drawable.tab_card_new_bg)
                }
                card.findViewById<TextView>(R.id.tabCardTitle).text = label
                card.findViewById<ImageView>(R.id.tabCardThumb).setImageBitmap(captureThumbnail(tab))
                card.findViewById<ImageButton>(R.id.tabCardClose).setOnClickListener {
                    closeTab(index, findViewById(R.id.btnTabs))
                    showTabsGrid(searchBox.text.toString())
                }
                card.setOnClickListener {
                    switchToTab(index)
                    hideTabsGrid()
                }
                grid.addView(card)
            }
        }
        populate(filter)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { populate(s?.toString().orEmpty()) }
        })
        addBtn.setOnClickListener {
            openNewTab(findViewById(R.id.btnTabs))
            showHomePage()
            hideTabsGrid()
        }
        doneBtn.setOnClickListener { hideTabsGrid() }
    }

    private fun hideTabsGrid() {
        tabsOverlayContainer.visibility = View.GONE
        tabsOverlayContainer.removeAllViews()
    }

    /** Cheap live snapshot of a tab's current WebView content for the grid card. */
    private fun captureThumbnail(tab: BrowserTab): Bitmap? {
        val webView = tab.webView
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun recordHistory(url: String) {
        val history = browserPrefs.getStringSet("history", emptySet()).orEmpty().toMutableList()
        history.remove(url)
        history.add(0, url)
        browserPrefs.edit().putStringSet("history", history.take(50).toSet()).apply()
    }

    private fun showHistoryDialog() {
        val history = browserPrefs.getStringSet("history", emptySet()).orEmpty().toList()
        AlertDialog.Builder(this).setTitle("Browsing history")
            .setItems(if (history.isEmpty()) arrayOf("No history yet") else history.toTypedArray()) { _, which -> if (which < history.size) currentWebView?.loadUrl(history[which]) }
            .setNegativeButton("Close", null).show()
    }

    private fun addBookmark() {
        currentWebView?.url?.takeIf { it.startsWith("https://") }?.let { url ->
            val bookmarks = browserPrefs.getStringSet("bookmarks", emptySet()).orEmpty().toMutableSet()
            bookmarks.add(url)
            browserPrefs.edit().putStringSet("bookmarks", bookmarks).apply()
        }
    }

    private fun showBookmarksDialog() {
        val bookmarks = browserPrefs.getStringSet("bookmarks", emptySet()).orEmpty().toList()
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(if (bookmarks.isEmpty()) arrayOf("No bookmarks yet") else bookmarks.toTypedArray()) { _, which -> if (which < bookmarks.size) currentWebView?.loadUrl(bookmarks[which]) }
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
            if (prefs.blockedSearchTerms.any { text.contains(it) }) return true
        }
        return false
    }

    private fun showBlockedPage(tab: BrowserTab) {
        tab.showingBlockPage = true
        tab.webView.stopLoading()
        tab.webView.loadDataWithBaseURL(
            "https://safeview.local/",
            "<html><meta name='viewport' content='width=device-width'><body style='background:#101827;color:#fff;font-family:sans-serif;padding:32px'><h1>Content blocked by SafeView</h1><p>This request was blocked by SafeView protection rules.</p><p>No page media was loaded.</p></body></html>",
            "text/html",
            "UTF-8",
            null
        )
        if (tab === currentTab) {
            urlBar.setText("")
            updateStatusChip()
        }
        tab.showingBlockPage = false
    }

    private fun injectSafeView(tab: BrowserTab) {
        if (contentScript.isEmpty()) return
        val originOk = tab.bridge.isOriginAllowed(tab.webView.url)
        val settingsJson = org.json.JSONObject(prefs.toJsObject())
            .put("nonce", tab.bridge.currentNonce())
            .put("generation", tab.bridge.currentGeneration())
            .put(
                "aiEnabled",
                prefs.aiEnabled && originOk && tab.bridgeAttached &&
                    (application as SafeViewApp).classifier.isReady
            )
            .put("originAllowed", originOk && tab.bridgeAttached)
            .toString()
        val settingsJs = "window.SafeViewNativeSettings = $settingsJson;"
        val updateJs = "if (window.__safeviewInjected && window.SafeViewUpdateSettings) " +
            "window.SafeViewUpdateSettings($settingsJson);"
        tab.webView.evaluateJavascript(settingsJs + "\n" + updateJs + "\n" + contentScript, null)
    }

    private fun updateStatusChip() {
        val app = application as? SafeViewApp
        val tab = currentTab
        val originOk = tab?.bridge?.isOriginAllowed(tab.webView.url) == true
        val aiReady = app?.let {
            it.aiPipelineAvailable && it.classifier.isReady
        } == true && prefs.aiEnabled && originOk && (tab?.bridgeAttached == true)
        val protection = if (prefs.enabled) "Protection on" else "Protection off"
        val mode = when {
            aiReady -> "AI + Heuristics"
            prefs.aiEnabled && app?.classifier?.isReady != true -> "Heuristics (AI model missing)"
            prefs.aiEnabled && !originOk -> "Heuristics (origin not on AI allowlist)"
            else -> "Heuristics"
        }
        statusChip.text = "$protection \u00b7 $mode"
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
        if (tabsOverlayContainer.visibility == View.VISIBLE) {
            hideTabsGrid()
            return
        }
        val webView = currentWebView
        if (webView != null && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
