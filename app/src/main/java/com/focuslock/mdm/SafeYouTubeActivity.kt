package com.focuslock.mdm

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import java.util.Locale
import java.util.UUID

/**
 * Approved YouTube Channels.
 *
 * The trusted list: channels open inside SafeTube, and their videos skip the
 * Keeper's check (it still blurs every thumbnail and removes Shorts).
 */
class SafeYouTubeActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_keeper

    override fun screenTitle(): String = getString(R.string.safetube_title)

    override fun screenSubtitle(): String = getString(R.string.safetube_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildApprovedChannelsCard())

        val remainingSuggestions = SafeTubeStore.suggestedChannels.filter { suggested ->
            SafeTubeStore.getChannels(this).none {
                it.handle.equals(suggested.handle, ignoreCase = true) ||
                    it.name.equals(suggested.name, ignoreCase = true)
            }
        }
        if (remainingSuggestions.isNotEmpty()) {
            column.addView(sectionLabel(getString(R.string.safetube_suggestions_section)))
            column.addView(buildSuggestionsCard(remainingSuggestions))
        }
    }

    private fun buildApprovedChannelsCard(): View = card { card ->
        val channels = SafeTubeStore.getChannels(this)
        if (channels.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.safetube_empty_channels)))
        } else {
            channels.forEachIndexed { index, channel ->
                val actions = FocusUi.row(this)
                actions.addView(
                    FocusUi.smallButton(this, tokens, getString(R.string.safetube_open)) {
                        openChannelInYouTube(channel)
                    }
                )
                actions.addView(FocusUi.spacerH(this, 8))
                actions.addView(
                    FocusUi.smallButton(this, tokens, getString(R.string.common_remove)) {
                        SafeTubeStore.removeChannel(this, channel.id)
                        refresh()
                    }
                )
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        channel.name,
                        channel.handle.ifBlank { channel.url },
                        trailing = actions
                    ) {
                        openChannelInYouTube(channel)
                    }
                )
                if (index < channels.size - 1) {
                    card.addView(FocusUi.divider(this, tokens))
                }
            }
        }

        card.addView(FocusUi.spacer(this, 12))
        card.addView(
            FocusUi.primaryButton(this, tokens, "+ " + getString(R.string.safetube_add_channel_btn)) {
                promptAddChannel()
            }
        )
    }

    private fun buildSuggestionsCard(suggestions: List<SafeChannel>): View = card { card ->
        card.addView(FocusUi.secondary(this, tokens, getString(R.string.safetube_suggestions_blurb)))
        card.addView(FocusUi.spacer(this, 10))
        suggestions.forEachIndexed { index, ch ->
            card.addView(
                FocusUi.listRow(
                    this,
                    tokens,
                    ch.name,
                    ch.handle,
                    trailing = FocusUi.smallButton(this, tokens, "+ " + getString(R.string.common_add)) {
                        SafeTubeStore.addChannel(this, ch)
                        FocusDialog.toast(this, getString(R.string.safetube_channel_added))
                        refresh()
                    }
                ) {
                    SafeTubeStore.addChannel(this, ch)
                    FocusDialog.toast(this, getString(R.string.safetube_channel_added))
                    refresh()
                }
            )
            if (index < suggestions.size - 1) {
                card.addView(FocusUi.divider(this, tokens))
            }
        }
    }

    private fun promptAddChannel() {
        FocusDialog.textInput(
            this,
            title = getString(R.string.safetube_add_channel_title),
            subtitle = getString(R.string.safetube_subtitle),
            hint = getString(R.string.safetube_add_channel_hint),
            confirmLabel = getString(R.string.common_add)
        ) { input ->
            if (input.isNotBlank()) {
                val clean = input.trim()
                val handle = when {
                    clean.startsWith("@") -> clean
                    clean.contains("youtube.com/") -> {
                        val after = clean.substringAfter("youtube.com/").trim('/')
                        if (after.startsWith("@")) after else "@$after"
                    }
                    else -> "@$clean"
                }
                val rawName = handle.removePrefix("@").replace(".", " ").replace("_", " ").replace("-", " ").trim()
                val name = rawName.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }.ifBlank { handle }
                val url = if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    clean
                } else {
                    "https://www.youtube.com/$handle"
                }
                val channel = SafeChannel(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    handle = handle,
                    url = url
                )
                SafeTubeStore.addChannel(this, channel)
                FocusDialog.toast(this, getString(R.string.safetube_channel_added))
                refresh()
            }
        }
    }

    /**
     * Inside SafeTube, never the YouTube app: this used to hand the real app a
     * 30-minute pass, which opened everything else on YouTube along with it.
     */
    private fun openChannelInYouTube(channel: SafeChannel) {
        val url = channel.url.ifBlank { "https://m.youtube.com/${channel.handle}" }
            .replace("://www.youtube.com", "://m.youtube.com")
        startActivity(Intent(this, SafeTubeBrowserActivity::class.java).putExtra(SafeTubeBrowserActivity.EXTRA_START_URL, url))
    }
}
