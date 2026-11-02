package com.focuslock.mdm

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayInputStream

/**
 * The safe browser.
 *
 * A curated internet rather than no internet: only hosts on the user's own list
 * load, there is no address bar to wander out of, and the media stripper turns
 * any page into something you read instead of scroll. This is the substitution
 * half of the product — blocking without a replacement is what people route
 * around.
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens

    private lateinit var webView: WebView
    private lateinit var webRoot: LinearLayout
    private lateinit var pickerLayout: LinearLayout
    private lateinit var categoryTabs: LinearLayout
    private lateinit var rvLinks: RecyclerView
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var tvCurrentSite: TextView
    private lateinit var switchTextOnly: SwitchCompat

    private var selectedCategory = "All"
    private var textOnlyMode = false
    private var currentUrl: String? = null

    private val imageExtensions = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg", ".ico", ".avif"
    )
    private val videoExtensions = setOf(
        ".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".m3u8", ".ts", ".avi"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webRoot = findViewById(R.id.webRoot)
        pickerLayout = findViewById(R.id.pickerLayout)
        categoryTabs = findViewById(R.id.categoryTabs)
        rvLinks = findViewById(R.id.rvLinks)
        bottomBar = findViewById(R.id.bottomBar)
        btnBack = findViewById(R.id.btnBack)
        tvCurrentSite = findViewById(R.id.tvCurrentSite)
        webView = findViewById(R.id.webView)
        switchTextOnly = findViewById(R.id.switchTextOnly)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
                    webView.visibility == View.VISIBLE -> closeWebView()
                    else -> finish()
                }
            }
        })

        textOnlyMode = AllowlistStore.isWebTextOnlyEnabled(this)
        switchTextOnly.isChecked = textOnlyMode
        switchTextOnly.setOnCheckedChangeListener { _, isChecked ->
            textOnlyMode = isChecked
            AllowlistStore.setWebTextOnlyEnabled(this, isChecked)
            applyWebViewSafetyMode()
            if (webView.visibility == View.VISIBLE) webView.reload()
        }

        btnBack.setOnClickListener { closeWebView() }

        setupWebView()
        applyTheme()
        buildCategoryTabs()
        buildLinkList()
        bottomBar.visibility = View.GONE

        intent.getStringExtra(EXTRA_INITIAL_URL)?.takeIf { it.isNotBlank() }?.let { rawUrl ->
            val normalized = AllowlistStore.normalizeUrl(rawUrl)
            if (isUrlAllowed(normalized)) {
                openLink(Constants.WebLink(normalized, normalized, "All"))
            } else {
                askKeeper(normalized)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy.syncLockTaskState(this)
        syncTextOnlyMode()
        applyTheme()
        buildCategoryTabs()
        buildLinkList()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ── Theming ───────────────────────────────────────────────────

    private fun applyTheme() {
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens, tokens.surface)

        if (tokens.wallpaperRes != 0) {
            webRoot.setBackgroundResource(tokens.wallpaperRes)
        } else {
            webRoot.setBackgroundColor(tokens.background)
        }

        styleText(findViewById(R.id.tvWebTitle), 22f, tokens.textPrimary, bold = true)
        styleText(findViewById(R.id.tvWebSubtitle), 13.5f, tokens.textSecondary)
        styleText(findViewById(R.id.tvTextOnlyTitle), 15f, tokens.textPrimary, bold = true)
        styleText(findViewById(R.id.tvTextOnlySubtitle), 12.5f, tokens.textSecondary)

        findViewById<View>(R.id.webDivider).setBackgroundColor(tokens.divider)

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        switchTextOnly.thumbTintList = android.content.res.ColorStateList(
            states,
            intArrayOf(tokens.accent, UiPrefs.blend(tokens.textMuted, tokens.surface, 0.3f))
        )
        switchTextOnly.trackTintList = android.content.res.ColorStateList(
            states,
            intArrayOf(UiPrefs.withAlpha(tokens.accent, 110), tokens.track)
        )

        bottomBar.setBackgroundColor(tokens.surface)
        styleText(btnBack, 14f, tokens.accent, bold = true)
        val pad = FocusUi.dp(this, 10)
        btnBack.setPadding(pad, pad, pad, pad)
        styleText(tvCurrentSite, 12f, tokens.textMuted)

        webView.setBackgroundColor(tokens.background)
    }

    private fun styleText(view: TextView, sizeSp: Float, color: Int, bold: Boolean = false) {
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(sizeSp))
        view.typeface = if (bold) Typeface.create(tokens.typeface, Typeface.BOLD) else tokens.typeface
    }

    // ── WebView ───────────────────────────────────────────────────

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
        }
        applyWebViewSafetyMode()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                if (request.isForMainFrame == false) {
                    return !isUrlAllowed(url)
                }

                // Deep links back into apps (auth handoffs) are allowed only for
                // apps the user has not blocked.
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (isDeepLinkIntentAllowed(intent)) {
                            startActivity(intent)
                        } else {
                            FocusDialog.toast(this@WebViewActivity, getString(R.string.webview_blocked_link))
                        }
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                if (textOnlyMode) {
                    if (isMediaNavigationBlocked(url)) {
                        FocusDialog.toast(this@WebViewActivity, getString(R.string.webview_text_only))
                        return true
                    }
                    enforceGoogleSafeSearch(url)?.let { safe ->
                        if (safe != url) {
                            view?.loadUrl(safe)
                            return true
                        }
                    }
                }

                if (isUrlAllowed(url)) {
                    currentUrl = url
                    updateCurrentSiteLabel()
                    return false
                }

                askKeeper(url)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isAdultHost(url)) return emptyResponse()
                if (!textOnlyMode) return null
                return if (isMediaResourceUrl(url)) emptyResponse() else null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null && !url.isNullOrBlank()) {
                    hideGoogleMediaTabs(view, url)
                    currentUrl = url
                    updateCurrentSiteLabel()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = false
        }

        webView.visibility = View.GONE
    }

    private fun updateCurrentSiteLabel() {
        tvCurrentSite.text = try {
            Uri.parse(currentUrl.orEmpty()).host?.removePrefix("www.").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Allowed when the host matches something on the user's list, or is one of
     * the sign-in domains the listed sites hand off to. The adult filter always
     * wins, even over an explicitly listed host.
     */
    private fun isUrlAllowed(target: String): Boolean =
        AllowlistStore.isListedUrl(this, target) ||
            (!isAdultHost(target) && KeeperCache.isApprovedPage(this, target))

    /** Not on the list: the Keeper looks at the page itself instead of a flat "no". */
    private fun askKeeper(url: String) {
        if (isAdultHost(url)) {
            FocusDialog.toast(this, getString(R.string.webview_not_in_list))
            return
        }
        startActivity(Intent(this, KeeperActivity::class.java).putExtra(KeeperActivity.EXTRA_URL, url))
    }

    private fun isAdultHost(url: String): Boolean = try {
        val host = Uri.parse(url).host?.lowercase() ?: ""
        host.isNotBlank() && AllowlistStore.isBlockedHost(this, host)
    } catch (_: Exception) {
        false
    }

    private fun isDeepLinkIntentAllowed(intent: Intent): Boolean {
        val target = intent.`package`
            ?: intent.component?.packageName
            ?: packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            ?: return false

        if (target == packageName) return true
        if (SystemSurfaces.isSettings(target)) return false
        return RuleEngine.isAllowed(this, target)
    }

    // ── Picker ────────────────────────────────────────────────────

    private fun buildCategoryTabs() {
        categoryTabs.removeAllViews()
        val categories = listOf("All") + AllowlistStore.getWebCategories(this)
        if (selectedCategory !in categories) selectedCategory = "All"

        categories.forEach { category ->
            categoryTabs.addView(
                // "All" is the internal key for the unfiltered view; only its
                // label is translated, so a saved selection survives a
                // language change.
                FocusUi.chip(
                    this,
                    tokens,
                    if (category == "All") getString(R.string.common_all) else category,
                    category == selectedCategory
                ) {
                    selectedCategory = category
                    buildCategoryTabs()
                    buildLinkList()
                }
            )
        }
    }

    private fun buildLinkList() {
        val links = AllowlistStore.getWebLinks(this)
        val filtered = if (selectedCategory == "All") {
            links
        } else {
            links.filter { it.category == selectedCategory }
        }

        rvLinks.layoutManager = LinearLayoutManager(this)
        rvLinks.adapter = LinkAdapter(filtered, this) { link -> openLink(link) }
    }

    private fun openLink(link: Constants.WebLink) {
        currentUrl = link.url
        pickerLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        updateCurrentSiteLabel()
        webView.loadUrl(link.url)
    }

    private fun closeWebView() {
        currentUrl = null
        webView.loadUrl("about:blank")
        webView.visibility = View.GONE
        bottomBar.visibility = View.GONE
        pickerLayout.visibility = View.VISIBLE
        buildLinkList()
    }

    // ── Media stripping ───────────────────────────────────────────

    private fun syncTextOnlyMode() {
        val enabled = AllowlistStore.isWebTextOnlyEnabled(this)
        if (enabled == textOnlyMode) return
        textOnlyMode = enabled
        switchTextOnly.isChecked = enabled
        applyWebViewSafetyMode()
    }

    private fun applyWebViewSafetyMode() {
        webView.settings.loadsImagesAutomatically = !textOnlyMode
        webView.settings.blockNetworkImage = textOnlyMode
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
                hasMediaExtension(path) -> true
                host.endsWith("gstatic.com") && (path.contains("/images") || query.contains("tbn")) -> true
                host.endsWith("googleusercontent.com") &&
                    (query.contains("tbn") || path.contains("/imgres")) -> true
                host.endsWith("ytimg.com") || host.endsWith("googlevideo.com") -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasMediaExtension(path: String): Boolean =
        imageExtensions.any { path.endsWith(it) } || videoExtensions.any { path.endsWith(it) }

    private fun isGoogleHost(host: String): Boolean {
        val normalized = host.removePrefix("www.")
        return normalized == "google.com" || normalized.endsWith(".google.com")
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun hideGoogleMediaTabs(view: WebView, url: String) {
        if (!textOnlyMode) return
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return
        } catch (_: Exception) {
            return
        }
        if (!isGoogleHost(host)) return

        val script = """
            (function(){
                var selectors = [
                    'a[href*="tbm=isch"]',
                    'a[href*="tbm=vid"]',
                    'a[aria-label="Images"]',
                    'a[aria-label="Videos"]'
                ];
                selectors.forEach(function(sel){
                    document.querySelectorAll(sel).forEach(function(n){ n.style.display = 'none'; });
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    companion object {
        const val EXTRA_INITIAL_URL = "extra_initial_url"
    }
}
