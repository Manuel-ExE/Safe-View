package com.safeview.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge from injected content script to native TFLite (v1.2.2).
 *
 * Authorization model:
 * - [pageGeneration] increments on every navigation; in-flight jobs capture it and
 *   callbacks are dropped if the generation no longer matches (no cross-document apply).
 * - [sessionNonce] is required on classify and echoed in callbacks; the page must
 *   match it before applying a result (defense in depth vs ID collision).
 * - [originAllowlist]: if non-empty, classify is accepted only when the current page
 *   origin host is on the list. Default is a conservative set of image-host sites.
 * - Fetch host allowlist ([fetchHostAllowlist]) defaults to known CDNs for allowed origins.
 */
class SafeViewBridge(
    webView: WebView,
    private val app: SafeViewApp,
    private val prefs: SettingsPrefs
) {
    private val webViewRef = WeakReference(webView)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val inFlight = AtomicInteger(0)
    private val sessionNonce = AtomicReference(UUID.randomUUID().toString())
    private val pageOrigin = AtomicReference<String?>(null)
    private val pageGeneration = AtomicLong(0)

    /**
     * Page origins (hostnames) allowed to use the classify bridge.
     * Empty set = deny all AI classify (heuristics-only). Production default is non-empty.
     */
    @Volatile
    var originAllowlist: Set<String> = DEFAULT_ORIGIN_ALLOWLIST

    /**
     * Hosts allowed for native image fetch.
     * Default is CDN hosts for the default origin allowlist.
     * Empty set would mean any public HTTPS host (private ranges still blocked).
     */
    @Volatile
    var fetchHostAllowlist: Set<String> = DEFAULT_FETCH_HOST_ALLOWLIST

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun onPageStarted(url: String?) {
        // Invalidate all in-flight work for the previous document
        pageGeneration.incrementAndGet()
        sessionNonce.set(UUID.randomUUID().toString())
        pageOrigin.set(originOf(url))
    }

    fun currentNonce(): String = sessionNonce.get()

    fun currentGeneration(): Long = pageGeneration.get()

    fun isOriginAllowed(url: String? = null): Boolean {
        val host = (url?.let { originOf(it) } ?: pageOrigin.get())
            ?.removePrefix("https://")
            ?.lowercase()
            ?: return false
        val allow = originAllowlist
        if (allow.isEmpty()) return false
        return allow.any { it.equals(host, ignoreCase = true) }
    }

    @JavascriptInterface
    fun classify(requestJson: String) {
        val req = try {
            JSONObject(requestJson)
        } catch (_: Exception) {
            return
        }

        val requestId = req.optString("id", "")
        if (requestId.isEmpty() || requestId.length > 80) return

        // Capture generation for this job immediately
        val jobGeneration = pageGeneration.get()
        val jobNonce = sessionNonce.get()

        fun fail(error: String) {
            postResult(requestId, blocked = false, error = error, nonce = jobNonce, generation = jobGeneration)
        }

        if (!prefs.enabled || !prefs.aiEnabled) {
            fail("disabled")
            return
        }
        if (!app.aiPipelineAvailable || !app.classifier.isReady) {
            fail("ai-unavailable")
            return
        }

        val nonce = req.optString("nonce", "")
        if (nonce.isEmpty() || nonce != jobNonce) {
            fail("unauthorized")
            return
        }

        // Origin gate: page must be on allowlist
        if (!isOriginAllowed()) {
            fail("origin-denied")
            return
        }

        val dataUrl = req.optString("dataUrl", "").takeIf { it.isNotEmpty() }
        val src = req.optString("src", "").takeIf { it.isNotEmpty() }

        if (dataUrl != null && !isSafeDataUrl(dataUrl)) {
            fail("bad-data-url")
            return
        }
        if (dataUrl == null) {
            if (src == null || !isAllowedFetchUrl(src)) {
                fail("bad-src")
                return
            }
        }

        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            fail("busy")
            return
        }

        executor.execute {
            var bitmap: Bitmap? = null
            try {
                // Drop if user navigated away while queued
                if (pageGeneration.get() != jobGeneration) {
                    postResult(requestId, blocked = false, error = "stale", nonce = jobNonce, generation = jobGeneration)
                    return@execute
                }

                bitmap = when {
                    dataUrl != null -> decodeDataUrl(dataUrl)
                    src != null -> fetchHttpsImageManualRedirects(src)
                    else -> null
                }
                if (bitmap == null) {
                    postResult(requestId, blocked = false, error = "decode-or-fetch-failed", nonce = jobNonce, generation = jobGeneration)
                    return@execute
                }
                if (bitmap.width * bitmap.height > MAX_DECODED_PIXELS) {
                    postResult(requestId, blocked = false, error = "bitmap-too-large", nonce = jobNonce, generation = jobGeneration)
                    return@execute
                }

                if (pageGeneration.get() != jobGeneration) {
                    postResult(requestId, blocked = false, error = "stale", nonce = jobNonce, generation = jobGeneration)
                    return@execute
                }

                val result = app.classifier.classify(
                    bitmap,
                    prefs.explicitThreshold,
                    prefs.revealingThreshold
                )
                if (result == null) {
                    postResult(requestId, blocked = false, error = "classify-failed", nonce = jobNonce, generation = jobGeneration)
                } else {
                    postResult(
                        requestId,
                        blocked = result.blocked,
                        error = null,
                        nonce = jobNonce,
                        generation = jobGeneration
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "classify failed: ${e.message}")
                postResult(requestId, blocked = false, error = "exception", nonce = jobNonce, generation = jobGeneration)
            } finally {
                try {
                    bitmap?.recycle()
                } catch (_: Exception) {
                }
                inFlight.decrementAndGet()
            }
        }
    }

    private fun postResult(
        requestId: String,
        blocked: Boolean,
        error: String?,
        nonce: String,
        generation: Long
    ) {
        // Only deliver into the document that started this job
        if (pageGeneration.get() != generation) return

        val payload = JSONObject()
            .put("id", requestId)
            .put("blocked", blocked)
            .put("nonce", nonce)
            .put("generation", generation)
        if (error != null) payload.put("error", error)
        val js = "window.SafeViewOnClassifyResult && window.SafeViewOnClassifyResult($payload);"
        mainHandler.post {
            // Re-check generation on main thread before touching the WebView
            if (pageGeneration.get() != generation) return@post
            webViewRef.get()?.evaluateJavascript(js, null)
        }
    }

    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("about:")) return null
        return try {
            val u = Uri.parse(url)
            if (u.scheme.equals("https", true) && !u.host.isNullOrBlank()) {
                "https://${u.host!!.lowercase()}"
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isHttpsUrl(url: String): Boolean {
        return url.startsWith("https://", ignoreCase = true) && url.length < 2048
    }

    private fun isSafeDataUrl(dataUrl: String): Boolean {
        if (dataUrl.length > MAX_DATA_URL_CHARS) return false
        val lower = dataUrl.take(40).lowercase()
        return lower.startsWith("data:image/jpeg;base64,") ||
            lower.startsWith("data:image/jpg;base64,") ||
            lower.startsWith("data:image/png;base64,")
    }

    private fun isPublicHttpsHost(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) {
            return false
        }
        if (h == "metadata.google.internal" || h == "metadata") return false
        // Reject obvious private IP literals without DNS
        if (h.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
            val parts = h.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                val a = parts[0]
                val b = parts[1]
                if (a == 10 || a == 127 || a == 0 || a == 169 && b == 254 ||
                    a == 192 && b == 168 || a == 172 && b in 16..31
                ) {
                    return false
                }
            }
        }
        return try {
            val addr = InetAddress.getByName(h)
            !(addr.isAnyLocalAddress || addr.isLoopbackAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress)
        } catch (_: Exception) {
            false
        }
    }

    private fun isAllowedFetchUrl(url: String): Boolean {
        if (!isHttpsUrl(url)) return false
        val host = try {
            Uri.parse(url).host ?: return false
        } catch (_: Exception) {
            return false
        }
        if (!isPublicHttpsHost(host)) return false
        val allow = fetchHostAllowlist
        if (allow.isNotEmpty()) {
            if (allow.none { it.equals(host, ignoreCase = true) }) return false
        }
        return true
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        return try {
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) return null
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            if (bytes.size > MAX_DECODE_BYTES) return null
            decodeBoundedBitmap(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBoundedBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_DECODED_PIXELS) return null

        var sample = 1
        while ((bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_DECODED_PIXELS / 2 &&
            sample < 32
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width.toLong() * bmp.height > MAX_DECODED_PIXELS) {
            bmp.recycle()
            return null
        }
        return bmp
    }

    private fun fetchHttpsImageManualRedirects(startUrl: String): Bitmap? {
        var url = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (!isAllowedFetchUrl(url)) return null
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SafeView/1.5.1")
                .get()
                .build()
            val response = http.newCall(request).execute()
            response.use { res ->
                val code = res.code
                if (code in 300..399) {
                    if (hop >= MAX_REDIRECTS) return null
                    val location = res.header("Location") ?: return null
                    val next = try {
                        java.net.URL(java.net.URL(url), location).toString()
                    } catch (_: Exception) {
                        return null
                    }
                    if (!isHttpsUrl(next) || !isAllowedFetchUrl(next)) return null
                    url = next
                    return@use
                }
                if (!res.isSuccessful) return null
                val body = res.body ?: return null
                // Stream with hard byte cap (avoid buffering unlimited body.bytes())
                val bytes = readBodyCapped(body.byteStream(), MAX_DECODE_BYTES) ?: return null
                val contentType = body.contentType()?.toString()?.lowercase() ?: ""
                if (contentType.isNotEmpty() &&
                    !contentType.startsWith("image/") &&
                    !contentType.contains("octet-stream")
                ) {
                    return null
                }
                return decodeBoundedBitmap(bytes)
            }
        }
        return null
    }

    private fun readBodyCapped(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        return try {
            stream.use { input ->
                val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SafeView.Bridge"
        private const val MAX_IN_FLIGHT = 4
        private const val MAX_DATA_URL_CHARS = 600_000
        private const val MAX_DECODE_BYTES = 512 * 1024
        private const val MAX_DECODED_PIXELS = 2048 * 2048
        private const val MAX_REDIRECTS = 3

        /** Default pages allowed to invoke the AI bridge. */
        val DEFAULT_ORIGIN_ALLOWLIST: Set<String> = setOf(
            "www.pinterest.com",
            "pinterest.com",
            "www.google.com",
            "google.com",
            "www.bing.com",
            "bing.com"
        )

        /** Default CDN / image hosts for native fetch (production default). */
        val DEFAULT_FETCH_HOST_ALLOWLIST: Set<String> = setOf(
            // Pinterest
            "i.pinimg.com",
            "s.pinimg.com",
            "pinimg.com",
            // Google Images / gstatic
            "encrypted-tbn0.gstatic.com",
            "encrypted-tbn1.gstatic.com",
            "encrypted-tbn2.gstatic.com",
            "encrypted-tbn3.gstatic.com",
            "lh3.googleusercontent.com",
            "gstatic.com",
            // Bing
            "tse1.mm.bing.net",
            "tse2.mm.bing.net",
            "tse3.mm.bing.net",
            "tse4.mm.bing.net",
            "th.bing.com"
        )
    }
}
