package com.focuslock.mdm

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * Asks the Keeper about one page.
 *
 * Reached three ways: the safe browser hands over an address that is not on the
 * list, a link is shared to Pinehold ("Share → Pinehold"), or Pinehold is picked to
 * open a link. The page loads behind an opaque card the person cannot see through;
 * only its address, text and a screenshot go to the Keeper. A yes opens that page
 * (or that site, if the Keeper says the whole site is safe) in the safe browser.
 * A no, a failure, or an unsure answer keeps it closed. There is no "but I need it"
 * box anywhere on this screen, on purpose.
 */
class KeeperActivity : AppCompatActivity() {

    companion object { const val EXTRA_URL = "keeper_url" }

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var overlay: LinearLayout
    private var probe: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var finishedLoading = false
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)

        val root = FrameLayout(this)
        root.setBackgroundColor(tokens.background)
        overlay = FocusUi.column(this).apply {
            setBackgroundColor(tokens.background)
            gravity = Gravity.CENTER
            val pad = FocusUi.dp(this@KeeperActivity, 28)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
        }
        setContentView(root)

        val url = incomingUrl()
        if (url == null) { showMessage(getString(R.string.keeper_no_link_title), getString(R.string.keeper_no_link_body)); root.addView(overlay); return }

        when {
            AllowlistStore.isListedUrl(this, url) -> { openInSafeBrowser(url); return }
            isAdult(url) -> showDenied(getString(R.string.keeper_reason_adult_list), remembered = false)
            else -> {
                val known = KeeperCache.lookupPage(this, url)
                when {
                    known?.allow == true -> { openInSafeBrowser(url); return }
                    known != null -> showDenied(known.reason, remembered = true)
                    !Keeper.isReady(this) -> showNeedsKey()
                    else -> { startProbe(root, url); showChecking(url) }
                }
            }
        }
        root.addView(overlay)
    }

    override fun onDestroy() {
        probe?.destroy()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun incomingUrl(): String? {
        val raw = intent.getStringExtra(EXTRA_URL)
            ?: intent.dataString
            ?: AllowlistStore.firstUrlIn(intent.getStringExtra(Intent.EXTRA_TEXT))
        return raw?.takeIf { it.isNotBlank() }?.let { AllowlistStore.normalizeUrl(it) }?.takeIf { AllowlistStore.isValidUrl(it) }
    }

    private fun isAdult(url: String): Boolean = try {
        AllowlistStore.isBlockedHost(this, android.net.Uri.parse(url).host.orEmpty())
    } catch (_: Exception) { true }

    // ── The hidden look at the page ──────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun startProbe(root: FrameLayout, url: String) {
        val w = FocusUi.dp(this, 400); val h = FocusUi.dp(this, 860)
        val view = WebView(this)
        view.layoutParams = FrameLayout.LayoutParams(w, h)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                // Redirects are followed so the Keeper judges where the link really goes,
                // but nothing outside the main frame may navigate it, and adult hosts never load.
                val target = request?.url?.toString() ?: return true
                return isAdult(target) || !(target.startsWith("https://") || target.startsWith("http://"))
            }
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val target = request?.url?.toString() ?: return null
                return if (isAdult(target)) WebResourceResponse("text/plain", "utf-8", null) else null
            }
            override fun onPageFinished(v: WebView?, finalUrl: String?) {
                finishedLoading = true
                main.postDelayed({ captureAndAsk(finalUrl ?: url) }, 1600)
            }
        }
        root.addView(view)   // under the opaque overlay: laid out and drawn, never seen
        probe = view
        view.loadUrl(url)
        main.postDelayed({ if (!finishedLoading) captureAndAsk(view.url ?: url) }, 25_000)
    }

    private fun captureAndAsk(finalUrl: String) {
        if (asked) return
        asked = true
        val view = probe ?: return
        if (isAdult(finalUrl)) { showDenied(getString(R.string.keeper_reason_adult_list), remembered = false); return }
        val js = "(function(){return JSON.stringify([document.title||'', (document.body&&document.body.innerText||'').slice(0,5000)]);})()"
        view.evaluateJavascript(js) { raw ->
            var title = ""; var text = ""
            try {
                val arr = JSONArray(org.json.JSONTokener(raw).nextValue() as String)
                title = arr.optString(0); text = arr.optString(1)
            } catch (_: Exception) { }
            val shot = try {
                Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.RGB_565).also { view.draw(Canvas(it)) }
            } catch (_: Exception) { null }
            thread(name = "keeper-web") {
                val img = shot?.let { KeeperClient.jpegDataUrl(it, maxWidth = 768) }
                val result = KeeperClient.ask(this, Keeper.Purpose.UNLOCK, KeeperPrompts.webSystem(this), KeeperPrompts.webUser(finalUrl, title, text), img)
                main.post { onAnswer(finalUrl, result) }
            }
        }
    }

    private fun onAnswer(finalUrl: String, result: KeeperResult) {
        if (isFinishing || isDestroyed) return
        when (result) {
            is KeeperResult.Answered -> {
                val verdict = KeeperParse.webVerdict(result.json, KeeperFocus.activeIds(this))
                KeeperCache.rememberPage(this, finalUrl, verdict)
                if (verdict.allow) openInSafeBrowser(finalUrl) else showDenied(verdict.reason, remembered = false)
            }
            is KeeperResult.Failed -> showFailed(result.why)
        }
    }

    private fun openInSafeBrowser(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_INITIAL_URL, url))
        finish()
    }

    // ── What the person sees ─────────────────────────────────────

    private fun reset() { overlay.removeAllViews() }

    private fun showChecking(url: String) {
        reset()
        overlay.addView(FocusUi.title(this, tokens, getString(R.string.keeper_checking_title)).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 10))
        overlay.addView(FocusUi.secondary(this, tokens, KeeperCache.siteKey(url).orEmpty()).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 18))
        overlay.addView(FocusUi.caption(this, tokens, getString(R.string.keeper_checking_body)).apply { gravity = Gravity.CENTER })
    }

    private fun showDenied(reason: String, remembered: Boolean) {
        probe?.stopLoading(); probe?.visibility = View.GONE
        reset()
        overlay.addView(FocusUi.title(this, tokens, getString(R.string.keeper_denied_title)).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 12))
        if (reason.isNotBlank()) overlay.addView(FocusUi.body(this, tokens, reason).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 10))
        overlay.addView(FocusUi.caption(this, tokens, getString(if (remembered) R.string.keeper_denied_remembered else R.string.keeper_denied_body)).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 22))
        overlay.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.common_close)) { finish() })
    }

    private fun showFailed(why: KeeperResult.Why) {
        probe?.stopLoading(); probe?.visibility = View.GONE
        val body = when (why) {
            KeeperResult.Why.BUDGET, KeeperResult.Why.QUOTA -> R.string.keeper_failed_budget
            KeeperResult.Why.NO_KEY -> R.string.keeper_needs_key_body
            KeeperResult.Why.DAILY_LIMIT -> R.string.keeper_plus_daily_limit
            KeeperResult.Why.BUSY -> R.string.keeper_busy
            else -> R.string.keeper_failed_network
        }
        val text = if (why == KeeperResult.Why.DAILY_LIMIT) getString(body, Account.keeperLimit(this)) else getString(body)
        showMessage(getString(R.string.keeper_failed_title), text)
    }

    private fun showNeedsKey() {
        reset()
        overlay.addView(FocusUi.title(this, tokens, getString(R.string.keeper_needs_key_title)).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 12))
        overlay.addView(FocusUi.body(this, tokens, getString(R.string.keeper_needs_key_body)).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 22))
        overlay.addView(FocusUi.primaryButton(this, tokens, getString(R.string.keeper_settings_title)) {
            startActivity(Intent(this, KeeperSettingsActivity::class.java)); finish()
        })
    }

    private fun showMessage(title: String, body: String) {
        reset()
        overlay.addView(FocusUi.title(this, tokens, title).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 12))
        overlay.addView(FocusUi.body(this, tokens, body).apply { gravity = Gravity.CENTER })
        overlay.addView(FocusUi.spacer(this, 22))
        overlay.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.common_close)) { finish() })
    }
}
