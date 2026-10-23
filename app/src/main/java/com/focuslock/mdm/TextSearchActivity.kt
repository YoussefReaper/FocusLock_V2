package com.focuslock.mdm

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/**
 * Search without the scroll.
 *
 * Google with SafeSearch forced on and every image, video and thumbnail
 * stripped at the network layer. It answers a question and then stops, which is
 * the difference between looking something up and losing an hour.
 */
class TextSearchActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: TextView

    private val imageExtensions = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg", ".ico", ".avif"
    )
    private val videoExtensions = setOf(
        ".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".m3u8", ".ts", ".avi"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_search)

        root = findViewById(R.id.textSearchRoot)
        webView = findViewById(R.id.textSearchWebView)
        etSearch = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnSearch)

        setupWebView()
        applyTheme()

        btnSearch.setOnClickListener { runSearch() }
        etSearch.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                runSearch()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy.syncLockTaskState(this)
        applyTheme()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ── Theming ───────────────────────────────────────────────────

    private fun applyTheme() {
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)

        if (tokens.wallpaperRes != 0) {
            root.setBackgroundResource(tokens.wallpaperRes)
        } else {
            root.setBackgroundColor(tokens.background)
        }

        val title = findViewById<TextView>(R.id.tvTextSearchTitle)
        title.setTextColor(tokens.textPrimary)
        title.typeface = Typeface.create(tokens.typeface, Typeface.BOLD)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(22f))

        val subtitle = findViewById<TextView>(R.id.tvTextSearchSubtitle)
        subtitle.setTextColor(tokens.textSecondary)
        subtitle.typeface = tokens.typeface
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(13f))

        findViewById<View>(R.id.textSearchDivider).setBackgroundColor(tokens.divider)

        etSearch.setTextColor(tokens.textPrimary)
        etSearch.setHintTextColor(tokens.textMuted)
        etSearch.typeface = tokens.typeface
        etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))
        etSearch.background = FocusUi.roundedShape(
            this,
            tokens.input,
            minOf(tokens.radiusDp, 14),
            UiPrefs.blend(tokens.divider, tokens.input, 0.2f)
        )
        val inputPadding = FocusUi.dp(this, 14)
        etSearch.setPadding(inputPadding, inputPadding, inputPadding, inputPadding)

        btnSearch.setTextColor(tokens.onAccent)
        btnSearch.typeface = Typeface.create(tokens.typeface, Typeface.BOLD)
        btnSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))
        btnSearch.background = FocusUi.withRipple(
            this,
            FocusUi.roundedShape(this, tokens.accent, minOf(tokens.radiusDp, 14)),
            tokens
        )
        val buttonPaddingH = FocusUi.dp(this, 20)
        btnSearch.setPadding(buttonPaddingH, inputPadding, buttonPaddingH, inputPadding)

        webView.setBackgroundColor(tokens.background)
    }

    // ── Search ────────────────────────────────────────────────────

    private fun runSearch() {
        val query = etSearch.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            FocusDialog.toast(this, getString(R.string.text_search_empty))
            return
        }
        if (isAdultQuery(query)) {
            FocusDialog.toast(this, getString(R.string.text_search_adult))
            return
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        webView.loadUrl("https://www.google.com/search?q=" + encoded + "&safe=active&num=20")
    }

    private fun isAdultQuery(query: String): Boolean {
        if (!CapabilityRegistry.isEnabled(this, Capabilities.ADULT_BLOCK)) return false
        val lower = query.lowercase()
        return Seed.adultKeywords.any { lower.contains(it) }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    if (request.isForMainFrame != false) {
                        FocusDialog.toast(this@TextSearchActivity, getString(R.string.text_search_link_blocked))
                    }
                    return true
                }
                if (isBlockedHost(url)) {
                    if (request.isForMainFrame != false) {
                        FocusDialog.toast(this@TextSearchActivity, getString(R.string.text_search_adult))
                    }
                    return true
                }
                if (isMediaNavigationBlocked(url)) {
                    if (request.isForMainFrame != false) {
                        FocusDialog.toast(this@TextSearchActivity, getString(R.string.text_search_media_off))
                    }
                    return true
                }
                if (request.isForMainFrame == false) return false

                enforceGoogleSafeSearch(url)?.let { safe ->
                    if (safe != url) {
                        view?.loadUrl(safe)
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isBlockedHost(url)) return emptyResponse()
                return if (isMediaResourceUrl(url)) emptyResponse() else null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null && !url.isNullOrBlank()) {
                    hideMediaElements(view)
                }
            }
        }
    }

    private fun isBlockedHost(url: String): Boolean = try {
        val host = Uri.parse(url).host?.lowercase() ?: ""
        host.isNotBlank() && AllowlistStore.isBlockedHost(this, host)
    } catch (_: Exception) {
        false
    }

    private fun enforceGoogleSafeSearch(url: String): String? = try {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (host != "google.com" || uri.path != "/search") {
            null
        } else if (uri.getQueryParameter("safe")?.lowercase() == "active") {
            null
        } else {
            val builder = uri.buildUpon().clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (name != "safe") {
                    uri.getQueryParameter(name)?.let { builder.appendQueryParameter(name, it) }
                }
            }
            builder.appendQueryParameter("safe", "active").build().toString()
        }
    } catch (_: Exception) {
        null
    }

    private fun isMediaNavigationBlocked(url: String): Boolean = try {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path?.lowercase() ?: ""
        val tbm = uri.getQueryParameter("tbm")?.lowercase()

        when {
            isGoogleHost(host) && (tbm == "isch" || tbm == "vid") -> true
            isGoogleHost(host) && (path.contains("/imghp") || path.contains("/imgres")) -> true
            host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("googlevideo.com") -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun isMediaResourceUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("data:image") || lower.startsWith("data:video")) return true

        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: ""
            val path = uri.path?.lowercase() ?: ""
            val query = uri.query?.lowercase() ?: ""

            when {
                imageExtensions.any { path.endsWith(it) } -> true
                videoExtensions.any { path.endsWith(it) } -> true
                host.endsWith("gstatic.com") && (path.contains("/images") || query.contains("tbn")) -> true
                host.endsWith("googleusercontent.com") && query.contains("tbn") -> true
                host.endsWith("ytimg.com") || host.endsWith("googlevideo.com") -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isGoogleHost(host: String): Boolean {
        val normalized = host.removePrefix("www.")
        return normalized == "google.com" || normalized.endsWith(".google.com")
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * Belt and braces: the network layer already refuses the bytes, this removes
     * the empty frames so the page reads as a clean list of results.
     */
    private fun hideMediaElements(view: WebView) {
        val script = """
            (function(){
                ['img','video','picture','svg','canvas'].forEach(function(tag){
                    document.querySelectorAll(tag).forEach(function(n){ n.style.display = 'none'; });
                });
                var tabs = [
                    'a[href*="tbm=isch"]',
                    'a[href*="tbm=vid"]',
                    'a[aria-label="Images"]',
                    'a[aria-label="Videos"]'
                ];
                tabs.forEach(function(sel){
                    document.querySelectorAll(sel).forEach(function(n){ n.style.display = 'none'; });
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }
}
