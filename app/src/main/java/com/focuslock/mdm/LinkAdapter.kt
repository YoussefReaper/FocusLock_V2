package com.focuslock.mdm

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * A row in the safe browser's link list.
 *
 * Binds against resolved tokens rather than the raw theme, so the list picks up
 * accent, radius, text scale and the bedtime palette like every other surface.
 */
class LinkAdapter(
    private val links: List<Constants.WebLink>,
    private val context: Context,
    private val onClick: (Constants.WebLink) -> Unit
) : RecyclerView.Adapter<LinkAdapter.VH>() {

    private var tokens: UiPrefs.Tokens = UiPrefs.resolve(context)

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.linkTitle)
        val category: TextView = view.findViewById(R.id.linkTimer)
        val url: TextView = view.findViewById(R.id.linkUrl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_link, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val link = links[position]

        holder.title.text = link.title
        holder.url.text = link.url.removePrefix("https://").removePrefix("http://")
        holder.category.text = if (link.category.isBlank()) holder.itemView.context.getString(R.string.link_category_custom) else link.category

        holder.title.setTextColor(tokens.textPrimary)
        holder.title.typeface = Typeface.create(tokens.typeface, Typeface.BOLD)
        holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))

        holder.url.setTextColor(tokens.textMuted)
        holder.url.typeface = tokens.typeface
        holder.url.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(12f))

        holder.category.setTextColor(tokens.accent)
        holder.category.typeface = tokens.typeface
        holder.category.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(11.5f))

        holder.itemView.background = FocusUi.withRipple(
            context,
            FocusUi.roundedShape(
                context,
                tokens.surface,
                tokens.radiusDp,
                UiPrefs.blend(tokens.divider, tokens.surface, 0.2f)
            ),
            tokens
        )
        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener { onClick(link) }
    }

    fun refreshTokens() {
        tokens = UiPrefs.resolve(context)
        notifyDataSetChanged()
    }

    override fun getItemCount() = links.size
}
