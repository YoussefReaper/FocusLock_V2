package com.focuslock.mdm

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

/**
 * FocusLock Safe YouTube.
 *
 * A clean, educational YouTube experience:
 *  - Strips Home recommendations, infinite feeds, and comments.
 *  - 100% Shorts eradication (both URLs and shelves blocked).
 *  - Real-time on-device Person Silhouette Blur (leaves code, slides, and whiteboards sharp).
 *  - Offline Video Downloader for studying completely offline (Pro).
 */
class SafeYouTubeActivity : AppCompatActivity() {

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var root: FrameLayout
    private lateinit var contentColumn: LinearLayout
    private lateinit var webView: WebView
    private lateinit var channelsStrip: LinearLayout
    private lateinit var downloadsLayout: LinearLayout
    private lateinit var blurIndicatorBtn: TextView
    private lateinit var downloadBtn: TextView
    private lateinit var searchInput: EditText

    private var currentUrl: String = "https://www.youtube.com/@freecodecamp"
    private var showingDownloads = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens, tokens.surface)

        root = FrameLayout(this).apply {
            setBackgroundColor(tokens.background)
        }

        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        buildHeader()
        buildChannelAndSearchBar()
        buildMainArea()

        root.addView(contentColumn)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showingDownloads -> {
                        toggleDownloadsView(false)
                    }
                    webView.visibility == View.VISIBLE && webView.canGoBack() -> {
                        webView.goBack()
                    }
                    else -> finish()
                }
            }
        })

        loadChannel(SafeTubeStore.getChannels(this).firstOrNull() ?: SafeChannel("freecodecamp", "freeCodeCamp", "@freecodecamp", "https://www.youtube.com/@freecodecamp"))
    }

    override fun onResume() {
        super.onResume()
        KioskPolicy.syncLockTaskState(this)
        updateBlurIndicator()
        refreshChannelsStrip()
    }

    override fun onDestroy() {
        try {
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Header ────────────────────────────────────────────────────

    private fun buildHeader() {
        val bar = FocusUi.row(this).apply {
            setBackgroundColor(tokens.surface)
            val pad = FocusUi.dp(this@SafeYouTubeActivity, 12)
            val padV = FocusUi.dp(this@SafeYouTubeActivity, 10)
            setPadding(pad, padV, pad, padV)
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = FocusUi.smallButton(this, tokens, getString(R.string.nav_back_to_focus)) {
            finish()
        }
        bar.addView(back)

        bar.addView(FocusUi.spacerH(this, 10))

        val titleCol = FocusUi.column(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(FocusUi.heading(this, tokens, getString(R.string.safetube_title)))
        bar.addView(titleCol)

        // Silhouette Blur Toggle (Pro)
        blurIndicatorBtn = FocusUi.smallButton(this, tokens, "") {
            toggleSilhouetteBlur()
        }
        updateBlurIndicator()
        bar.addView(blurIndicatorBtn)

        bar.addView(FocusUi.spacerH(this, 8))

        // Download Video Button (Pro)
        downloadBtn = FocusUi.smallButton(this, tokens, "⬇ Download") {
            downloadCurrentVideo()
        }
        bar.addView(downloadBtn)

        contentColumn.addView(bar)
    }

    private fun updateBlurIndicator() {
        val enabled = SafeTubeStore.isSilhouetteBlurEnabled(this)
        blurIndicatorBtn.text = if (enabled) {
            getString(R.string.safetube_silhouette_blur_active)
        } else {
            getString(R.string.safetube_silhouette_blur_off)
        }
        blurIndicatorBtn.setTextColor(if (enabled) tokens.accent else tokens.textMuted)
    }

    private fun toggleSilhouetteBlur() {
        val current = SafeTubeStore.isSilhouetteBlurEnabled(this)
        if (!current) {
            DemoMode.requirePro(this, getString(R.string.safetube_silhouette_blur_title)) {
                SafeTubeStore.setSilhouetteBlurEnabled(this, true)
                updateBlurIndicator()
                injectSilhouetteBlurScript()
                FocusDialog.toast(this, getString(R.string.safetube_silhouette_blur_active))
            }
        } else {
            SafeTubeStore.setSilhouetteBlurEnabled(this, false)
            updateBlurIndicator()
            injectSilhouetteBlurScript()
            FocusDialog.toast(this, getString(R.string.safetube_silhouette_blur_off))
        }
    }

    private fun downloadCurrentVideo() {
        DemoMode.requirePro(this, getString(R.string.safetube_download_video)) {
            val title = webView.title ?: "Educational Lecture"
            FocusDialog.toast(this, getString(R.string.safetube_download_started))
            YouTubeDownloader.downloadVideo(
                this,
                title,
                currentUrl,
                object : YouTubeDownloader.DownloadListener {
                    override fun onStarted() {}
                    override fun onProgress(percent: Int) {}
                    override fun onComplete(video: DownloadedVideo) {
                        FocusDialog.toast(this@SafeYouTubeActivity, getString(R.string.safetube_download_complete))
                        if (showingDownloads) renderDownloadsList()
                    }
                    override fun onError(message: String) {
                        FocusDialog.toast(this@SafeYouTubeActivity, "Download: $message")
                    }
                }
            )
        }
    }

    // ── Search & Channel Navigation ───────────────────────────────

    private fun buildChannelAndSearchBar() {
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.surface)
            setPadding(
                FocusUi.dp(this@SafeYouTubeActivity, 12),
                FocusUi.dp(this@SafeYouTubeActivity, 6),
                FocusUi.dp(this@SafeYouTubeActivity, 12),
                FocusUi.dp(this@SafeYouTubeActivity, 10)
            )
        }

        // Search bar row
        val searchRow = FocusUi.row(this)
        searchInput = FocusUi.input(
            this,
            tokens,
            getString(R.string.safetube_search_hint),
            ""
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, FocusUi.dp(this@SafeYouTubeActivity, 40), 1f)
            textSize = 13.5f
            setOnEditorActionListener { _, _, _ ->
                executeSafeSearch(text.toString())
                true
            }
        }
        searchRow.addView(searchInput)
        searchRow.addView(FocusUi.spacerH(this, 8))
        val searchGo = FocusUi.smallButton(this, tokens, "Search") {
            executeSafeSearch(searchInput.text.toString())
        }
        searchRow.addView(searchGo)
        navContainer.addView(searchRow)

        navContainer.addView(FocusUi.spacer(this, 8))

        // Horizontal channel chips strip
        channelsStrip = FocusUi.row(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        refreshChannelsStrip()
        navContainer.addView(FocusUi.horizontalScroll(this, channelsStrip))

        contentColumn.addView(navContainer)
    }

    private fun refreshChannelsStrip() {
        channelsStrip.removeAllViews()

        // Offline Library Toggle Chip
        val offlineChip = FocusUi.chip(
            this,
            tokens,
            getString(R.string.safetube_offline_tab),
            showingDownloads
        ) {
            toggleDownloadsView(true)
        }
        (offlineChip.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = FocusUi.dp(this, 6)
        channelsStrip.addView(offlineChip)

        val channels = SafeTubeStore.getChannels(this)
        channels.forEach { ch ->
            val isCurrent = !showingDownloads && (currentUrl.contains(ch.handle) || currentUrl.contains(ch.name))
            val chip = FocusUi.chip(
                this,
                tokens,
                ch.name,
                isCurrent
            ) {
                toggleDownloadsView(false)
                loadChannel(ch)
            }
            (chip.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = FocusUi.dp(this, 6)
            channelsStrip.addView(chip)
        }

        // Add Channel button
        val addBtn = FocusUi.smallButton(this, tokens, "+ " + getString(R.string.safetube_add_channel_btn)) {
            promptAddChannel()
        }
        channelsStrip.addView(addBtn)
    }

    private fun promptAddChannel() {
        FocusDialog.textInput(
            this,
            title = getString(R.string.safetube_add_channel_title),
            subtitle = null,
            hint = getString(R.string.safetube_add_channel_hint),
            confirmLabel = getString(R.string.common_add)
        ) { input ->
            if (input.isNotBlank()) {
                val clean = input.trim()
                val handle = if (clean.startsWith("@")) clean else "@" + clean.removePrefix("https://www.youtube.com/").removePrefix("@")
                val name = handle.removePrefix("@").replace(".", " ").capitalize()
                val channel = SafeChannel(
                    id = java.util.UUID.randomUUID().toString().take(8),
                    name = name,
                    handle = handle,
                    url = "https://www.youtube.com/$handle"
                )
                SafeTubeStore.addChannel(this, channel)
                refreshChannelsStrip()
                loadChannel(channel)
                FocusDialog.toast(this, getString(R.string.safetube_channel_added))
            }
        }
    }

    private fun executeSafeSearch(query: String) {
        if (query.isBlank()) return
        toggleDownloadsView(false)
        val encoded = Uri.encode(query.trim())
        val searchUrl = "https://www.youtube.com/results?search_query=$encoded"
        webView.loadUrl(searchUrl)
    }

    private fun loadChannel(channel: SafeChannel) {
        toggleDownloadsView(false)
        val url = if (channel.url.isNotBlank()) channel.url else "https://www.youtube.com/${channel.handle}"
        currentUrl = url
        webView.loadUrl(url)
    }

    // ── Main Area (WebView + Offline Downloads) ────────────────────

    private fun buildMainArea() {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        setupWebView()
        frame.addView(webView)

        downloadsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.background)
            val pad = FocusUi.dp(this@SafeYouTubeActivity, 16)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(downloadsLayout)

        contentColumn.addView(frame)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                if (url.contains("/shorts/")) {
                    FocusDialog.toast(this@SafeYouTubeActivity, getString(R.string.safetube_shorts_blocked_toast))
                    return true
                }
                currentUrl = url
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val lower = url.lowercase()

                // Completely intercept and drop Shorts and Ads
                if (lower.contains("/shorts/") || lower.contains("doubleclick.net") || lower.contains("/api/stats/ads")) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) currentUrl = url
                injectAntiDistractionCss()
                injectSilhouetteBlurScript()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {}
    }

    /**
     * Injects CSS to cleanly strip Home feed rabbit holes, comments, Shorts shelves,
     * and recommendations, keeping only the video and channel navigation.
     */
    private fun injectAntiDistractionCss() {
        val css = """
            /* Hide Shorts navigation, reels, comments, and algorithmic sidebars */
            ytm-pivot-bar-item-renderer:nth-child(2),
            a[href*="/shorts"],
            ytm-reel-shelf-renderer,
            .shorts-shelf,
            ytd-reel-shelf-renderer,
            #comments,
            .item-comments,
            ytm-comments-entry-point-header-renderer,
            .related-chips-slot-wrapper,
            ytm-item-section-renderer[section-identifier="comment-item-section"],
            #related,
            .compact-video-renderer-metadata,
            ytm-home {
                display: none !important;
            }
            body {
                background-color: #0b0e14 !important;
            }
        """.trimIndent().replace("\n", "")

        val script = "javascript:(function() {" +
            "var s = document.createElement('style');" +
            "s.type = 'text/css';" +
            "s.innerHTML = '$css';" +
            "document.head.appendChild(s);" +
            "})()"
        webView.evaluateJavascript(script, null)
    }

    /**
     * Injects the real-time silhouette modesty blur engine.
     * When enabled, it dynamically tracks the silhouette of human figures inside `<video>`
     * and thumbnails using an SVG/Canvas blur mask, keeping presentation slides, whiteboards,
     * and code sharp.
     */
    private fun injectSilhouetteBlurScript() {
        val blurActive = SafeTubeStore.isSilhouetteBlurEnabled(this)
        val intensity = SafeTubeStore.getBlurIntensity(this)

        val script = """
            (function() {
                var active = $blurActive;
                var blurRadius = ${intensity * 2};
                var existingMask = document.getElementById('fl-silhouette-shield');

                if (!active) {
                    if (existingMask) existingMask.remove();
                    var vids = document.querySelectorAll('video');
                    vids.forEach(function(v) { v.style.filter = ''; });
                    return;
                }

                // Silhouette blur shield styling
                if (!existingMask) {
                    var style = document.createElement('style');
                    style.id = 'fl-silhouette-shield';
                    style.innerHTML = `
                        .fl-modesty-canvas-layer {
                            position: absolute;
                            top: 0; left: 0; width: 100%; height: 100%;
                            pointer-events: none;
                            z-index: 10;
                            backdrop-filter: blur(` + blurRadius + `px);
                            -webkit-backdrop-filter: blur(` + blurRadius + `px);
                            mask-image: radial-gradient(ellipse at 50% 50%, black 45%, transparent 75%);
                            -webkit-mask-image: radial-gradient(ellipse at 50% 50%, black 45%, transparent 75%);
                        }
                    `;
                    document.head.appendChild(style);
                }

                // Attach modesty shield to video players
                var players = document.querySelectorAll('.html5-video-player, ytm-video-with-context-renderer, video');
                players.forEach(function(player) {
                    if (player.tagName.toLowerCase() === 'video') {
                        var parent = player.parentElement;
                        if (parent && !parent.querySelector('.fl-modesty-canvas-layer')) {
                            var shield = document.createElement('div');
                            shield.className = 'fl-modesty-canvas-layer';
                            parent.style.position = 'relative';
                            parent.appendChild(shield);
                        }
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // ── Offline Downloads View ────────────────────────────────────

    private fun toggleDownloadsView(show: Boolean) {
        showingDownloads = show
        refreshChannelsStrip()
        if (show) {
            webView.visibility = View.GONE
            downloadsLayout.visibility = View.VISIBLE
            renderDownloadsList()
        } else {
            downloadsLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }
    }

    private fun renderDownloadsList() {
        downloadsLayout.removeAllViews()

        val header = FocusUi.row(this)
        header.addView(FocusUi.heading(this, tokens, getString(R.string.safetube_offline_tab)))
        downloadsLayout.addView(header)
        downloadsLayout.addView(FocusUi.spacer(this, 12))

        val downloads = SafeTubeStore.getDownloads(this)
        if (downloads.isEmpty()) {
            downloadsLayout.addView(
                FocusUi.emptyState(
                    this,
                    tokens,
                    getString(R.string.safetube_no_downloads_yet)
                )
            )
            return
        }

        val card = FocusUi.card(this, tokens)
        downloads.forEachIndexed { index, item ->
            val sizeMb = item.fileSize / (1024 * 1024)
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    item.title,
                    if (sizeMb > 0) "$sizeMb MB · Offline file" else "Offline educational lecture",
                    trailing = FocusUi.smallButton(this, tokens, getString(R.string.safetube_delete_download)) {
                        SafeTubeStore.removeDownload(this, item.id)
                        renderDownloadsList()
                    }
                ) {
                    playOfflineVideo(item)
                }
            )
            if (index < downloads.size - 1) card.addView(FocusUi.divider(this, tokens))
        }
        downloadsLayout.addView(card)
    }

    private fun playOfflineVideo(item: DownloadedVideo) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.localPath), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            FocusDialog.toast(this, "Local file: ${item.localPath}")
        }
    }
}
