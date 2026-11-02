package com.focuslock.mdm

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * SafeTube: YouTube with the hooks taken out.
 *
 * Every image on the page is blurred and cannot be unblurred (thumbnails are where
 * the bait lives). Shorts are removed and their addresses refused. The home feed is
 * hidden, so the page starts from a search, not from the algorithm. No video ever
 * plays here: muted inline previews are stopped, and tapping any video sends it to
 * [VideoCheckActivity], which decides whether it may be watched at all.
 */
class SafeTubeBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_URL = "safetube_start"
        private const val HOME = "https://m.youtube.com/"

        private val CSS = """
            img, picture, yt-img-shadow, .ytThumbnailViewModelImage, ytm-thumbnail-cover, .video-thumbnail-img,
            .ytCoreImageHost, .yt-core-image, .ytm-media-item img, [style*="background-image"] {
              filter: blur(26px) saturate(0.3) brightness(0.85) !important;
            }
            video, ytm-inline-playback-renderer, .inline-player-container, .player-container, #player,
            ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2,
            ytm-reel-item-renderer, a[href^="/shorts"], a[href*="/shorts/"], .pivot-shorts,
            ytm-pivot-bar-renderer, ytm-comment-section-renderer, ytm-promoted-sparkles-web-renderer,
            ytm-companion-ad-renderer, ad-slot-renderer, ytm-ad-slot-renderer,
            html.ph-home ytm-rich-grid-renderer, html.ph-home ytm-section-list-renderer {
              display: none !important;
            }
            html.ph-home ytm-browse::before {
              content: "Search for what you came for."; display: block; padding: 48px 24px;
              font: 500 17px/1.4 sans-serif; color: #8a948f; text-align: center;
            }
        """.trimIndent().replace("\n", " ")

        /** Injected on every page start, finish and in-app route change; idempotent. */
        private val JS = """
            (function(){
              var css = ${org.json.JSONObject.quote(CSS)};
              function ensure(){
                if(!document.getElementById('ph-style')){
                  var s=document.createElement('style'); s.id='ph-style'; s.textContent=css;
                  (document.head||document.documentElement).appendChild(s);
                }
                var home = location.pathname==='/' || location.pathname.indexOf('/feed/')===0;
                document.documentElement.classList.toggle('ph-home', home);
                document.querySelectorAll('video').forEach(function(v){ try{ v.pause(); v.muted=true; v.removeAttribute('src'); }catch(e){} });
              }
              ensure();
              if(window.__phInstalled) return; window.__phInstalled=true;
              document.addEventListener('click', function(e){
                var a = e.target && e.target.closest ? e.target.closest('a') : null;
                if(!a) return;
                var h = a.href || '';
                if(h.indexOf('/watch')>=0 || h.indexOf('/shorts/')>=0 || h.indexOf('youtu.be/')>=0){
                  e.preventDefault(); e.stopPropagation(); PineBridge.openVideo(h);
                }
              }, true);
              setInterval(ensure, 700);
            })();
        """.trimIndent()
    }

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)

        val root = FocusUi.column(this).apply { setBackgroundColor(tokens.background) }
        val bar = FocusUi.row(this).apply {
            val p = FocusUi.dp(this@SafeTubeBrowserActivity, 12)
            setPadding(p, p, p, p)
        }
        bar.addView(FocusUi.heading(this, tokens, getString(R.string.safetube_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(FocusUi.smallButton(this, tokens, getString(R.string.safetube_trusted_short)) {
            startActivity(Intent(this, SafeYouTubeActivity::class.java))
        })
        bar.addView(FocusUi.spacerH(this, 8))
        bar.addView(FocusUi.smallButton(this, tokens, getString(R.string.common_close)) { finish() })
        root.addView(bar)

        web = WebView(this)
        web.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "PineBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame == false) return false
                val url = request?.url?.toString() ?: return true
                return route(url)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { view?.evaluateJavascript(JS, null) }
            override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(JS, null) }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                // YouTube changes pages without reloading: catch watch and Shorts routes here too.
                val u = url ?: return
                if (VideoCheck.videoIdFrom(u) != null) { view?.stopLoading(); if (view?.canGoBack() == true) view.goBack(); route(u) }
                view?.evaluateJavascript(JS, null)
            }
        }
        root.addView(web)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        web.loadUrl(intent.getStringExtra(EXTRA_START_URL)?.takeIf { isYouTube(it) } ?: HOME)
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }

    /** true = handled here (do not load). */
    private fun route(url: String): Boolean {
        val id = VideoCheck.videoIdFrom(url)
        if (id != null) { openCheck(id); return true }
        if (!isYouTube(url)) { FocusDialog.toast(this, getString(R.string.safetube_outside_youtube)); return true }
        return false
    }

    private fun isYouTube(url: String): Boolean = try {
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        host == "youtube.com" || host.endsWith(".youtube.com")
    } catch (_: Exception) { false }

    private fun openCheck(id: String) {
        startActivity(Intent(this, VideoCheckActivity::class.java).putExtra(VideoCheckActivity.EXTRA_VIDEO_ID, id))
    }

    private inner class Bridge {
        @JavascriptInterface
        fun openVideo(url: String) {
            val id = VideoCheck.videoIdFrom(url) ?: return
            runOnUiThread { openCheck(id) }
        }
    }

}
