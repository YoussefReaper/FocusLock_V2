package com.focuslock.mdm

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Plays one checked video through YouTube's official embedded player, skipping
 * every range the Keeper cut. Nothing else on YouTube is reachable from here.
 *
 * YouTube's own surfaces that could show other videos' thumbnails (the poster
 * before play, the pause screen, the end screen) stay covered by an opaque panel:
 * the player is only visible while it is actually playing a part that passed.
 */
class SafeTubePlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "player_video_id"
        const val EXTRA_CUTS = "player_cuts"   // [from0, to0, from1, to1, ...] in seconds
        private const val BASE = "https://pinehold.app/player/"
    }

    private lateinit var tokens: UiPrefs.Tokens
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = UiPrefs.resolve(this)
        FocusUi.applySystemBars(window, tokens)
        val id = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: run { finish(); return }
        val flat = intent.getIntArrayExtra(EXTRA_CUTS) ?: IntArray(0)
        val cuts = (0 until flat.size / 2).map { flat[2 * it] to flat[2 * it + 1] }
        // Never play something the Keeper has not cleared (or whose verdict was lost).
        val verdict = KeeperCache.lookupVideo(this, id)
        if (verdict == null || !verdict.allow) { finish(); return }

        val root = FocusUi.column(this).apply { setBackgroundColor(tokens.background) }
        val bar = FocusUi.row(this).apply { val p = FocusUi.dp(this@SafeTubePlayerActivity, 12); setPadding(p, p, p, p) }
        bar.addView(FocusUi.heading(this, tokens, getString(R.string.safetube_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(FocusUi.smallButton(this, tokens, getString(R.string.common_close)) { finish() })
        root.addView(bar)

        web = WebView(this)
        val width = resources.displayMetrics.widthPixels
        web.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, width * 9 / 16)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            // The page is ours; anything trying to take the main frame elsewhere
            // ("Watch on YouTube", a suggested video) is refused.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = request?.isForMainFrame != false
        }
        root.addView(web)

        val info = FocusUi.column(this).apply { val p = FocusUi.dp(this@SafeTubePlayerActivity, 20); setPadding(p, p, p, p) }
        if (cuts.isNotEmpty()) {
            info.addView(FocusUi.body(this, tokens, getString(R.string.video_cut_body, cuts.size, VideoCheck.secondsLabel(cuts.sumOf { it.second - it.first }))))
            info.addView(FocusUi.spacer(this, 6))
            info.addView(FocusUi.caption(this, tokens, cuts.joinToString("   ") { (a, b) -> "${clock(a)}–${clock(b)}" }))
        } else {
            info.addView(FocusUi.body(this, tokens, getString(R.string.video_allowed_title)))
        }
        root.addView(info)
        setContentView(root)

        web.loadDataWithBaseURL(BASE, page(id, cuts), "text/html", "utf-8", null)
    }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }

    private fun clock(s: Int) = "%d:%02d".format(s / 60, s % 60)

    private fun page(id: String, cuts: List<Pair<Int, Int>>): String {
        val cutJs = cuts.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
        val bg = String.format("#%06X", 0xFFFFFF and tokens.background)
        val fg = String.format("#%06X", 0xFFFFFF and tokens.textPrimary)
        val play = getString(R.string.player_play); val resume = getString(R.string.player_resume)
        val skipping = getString(R.string.player_skipping); val done = getString(R.string.player_finished)
        val blocked = getString(R.string.player_cannot_embed)
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}#w{position:relative;width:100%;height:100%}#p{position:absolute;inset:0;width:100%;height:100%}
#c{position:absolute;inset:0;background:$bg;color:$fg;display:flex;align-items:center;justify-content:center;font:600 18px sans-serif;z-index:5;text-align:center;padding:12px;box-sizing:border-box}</style>
</head><body><div id="w"><div id="p"></div><div id="c" onclick="go()">▶ ${esc(play)}</div></div>
<script>
var CUTS=$cutJs, player=null, ready=false, state=-1;
var cover=document.getElementById('c');
function show(t){cover.textContent=t;cover.style.display='flex';}
function hide(){cover.style.display='none';}
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{videoId:'$id',width:'100%',height:'100%',
    playerVars:{rel:0,modestbranding:1,playsinline:1,iv_load_policy:3,fs:0,controls:1,origin:'https://pinehold.app'},
    events:{onReady:function(){ready=true;},
            onStateChange:function(e){state=e.data; if(e.data===2)show('▶ ${esc(resume)}'); if(e.data===0){show('${esc(done)}');}},
            onError:function(){show('${esc(blocked)}');}}});
}
function go(){ if(ready&&state!==0){ player.playVideo(); } }
setInterval(function(){
  if(!player||!player.getCurrentTime) return;
  var t=player.getCurrentTime();
  for(var i=0;i<CUTS.length;i++){ if(t>=CUTS[i][0]-0.4&&t<CUTS[i][1]){ show('${esc(skipping)}'); player.seekTo(CUTS[i][1],true); return; } }
  if(state===1) hide();
},150);
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "&lt;")
}
