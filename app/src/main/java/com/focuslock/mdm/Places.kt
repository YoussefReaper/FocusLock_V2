package com.focuslock.mdm

import android.content.Context

/**
 * Reddit and Discord, opened in the Safe Browser instead of their apps, with
 * only the parts you chose: subreddits and servers you approved. Reddit's
 * Home, Popular and All are closed (they are feeds); comments start folded.
 * Discord DMs stay open; server channels need the server approved; Discovery
 * is closed.
 */
object Places {

    enum class Verdict { OPEN, CLOSED, ASK }

    private const val K_SUBS = "places_subreddits"
    private const val K_SERVERS = "places_discord_servers"

    fun subreddits(c: Context): Set<String> = FocusStore.getSet(c, K_SUBS)
    fun servers(c: Context): Set<String> = FocusStore.getSet(c, K_SERVERS)

    fun addSubreddit(c: Context, name: String) {
        val n = cleanSub(name) ?: return
        FocusStore.setSet(c, K_SUBS, subreddits(c) + n)
    }
    fun removeSubreddit(c: Context, name: String) = FocusStore.setSet(c, K_SUBS, subreddits(c) - name)

    /** Accepts a server id or a discord.com/channels/<id>/... link. */
    fun addServer(c: Context, raw: String) {
        val id = Regex("""(\d{15,21})""").find(raw)?.value ?: return
        FocusStore.setSet(c, K_SERVERS, servers(c) + id)
    }
    fun removeServer(c: Context, id: String) = FocusStore.setSet(c, K_SERVERS, servers(c) - id)

    fun cleanSub(raw: String): String? =
        raw.trim().removePrefix("https://").removePrefix("www.").removePrefix("reddit.com/")
            .removePrefix("/").removePrefix("r/").substringBefore('/').lowercase()
            .takeIf { it.matches(Regex("[a-z0-9_]{2,21}")) }

    fun isReddit(host: String) = host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it"
    fun isDiscord(host: String) = host == "discord.com" || host.endsWith(".discord.com") || host == "discord.gg"

    /** Pure, for tests. */
    fun verdict(url: String, subs: Set<String>, servers: Set<String>): Verdict {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return Verdict.ASK
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return Verdict.ASK
        val parts = (u.path ?: "").split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
        if (isReddit(host)) {
            if (host == "redd.it") return Verdict.CLOSED
            if (parts.isEmpty()) return Verdict.CLOSED
            if (parts[0] == "r" && parts.size >= 2) {
                val sub = parts[1]
                if (sub == "popular" || sub == "all") return Verdict.CLOSED
                return if (sub in subs) Verdict.OPEN else Verdict.CLOSED
            }
            if (parts[0] in setOf("login", "account", "settings")) return Verdict.OPEN
            return Verdict.CLOSED
        }
        if (isDiscord(host)) {
            if (host == "discord.gg") return Verdict.CLOSED
            if (parts.isEmpty()) return Verdict.OPEN
            if (parts[0] in setOf("login", "register", "app")) return Verdict.OPEN
            if (parts[0] == "channels" && parts.size >= 2) {
                if (parts[1] == "@me") return Verdict.OPEN
                return if (parts[1] in servers) Verdict.OPEN else Verdict.CLOSED
            }
            if (parts[0] == "assets" || parts[0] == "api" || parts[0] == "cdn-cgi") return Verdict.OPEN
            return Verdict.CLOSED // discovery, invites, store
        }
        return Verdict.ASK
    }

    fun verdict(c: Context, url: String): Verdict = verdict(url, subreddits(c), servers(c))

    /** Folds Reddit comments behind one tap; injected on Reddit pages. */
    const val REDDIT_SCRIPT = """
(function(){
  if (document.getElementById('ph-fold')) return;
  var s=document.createElement('style'); s.id='ph-fold';
  s.textContent='shreddit-comment-tree,.commentarea,#comment-tree{display:none!important}body.ph-show shreddit-comment-tree,body.ph-show .commentarea,body.ph-show #comment-tree{display:block!important}';
  (document.head||document.documentElement).appendChild(s);
  var b=document.createElement('button'); b.textContent='Show comments';
  b.style.cssText='position:fixed;bottom:16px;right:16px;z-index:99999;padding:10px 16px;border-radius:20px;border:0;background:#F0A36A;color:#0a0915';
  b.onclick=function(){document.body.classList.toggle('ph-show'); b.textContent=document.body.classList.contains('ph-show')?'Hide comments':'Show comments';};
  document.body && document.body.appendChild(b);
})();
"""
}
