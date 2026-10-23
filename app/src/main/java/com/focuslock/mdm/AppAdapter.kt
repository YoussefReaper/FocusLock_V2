package com.focuslock.mdm

import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/** A tile in an app grid, wearing the current tokens. */
class AppAdapter(
    private val apps: List<AppItem>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        val context = holder.itemView.context
        val tokens = UiPrefs.resolve(context)

        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.label.setTextColor(tokens.textSecondary)
        holder.label.typeface = tokens.typeface
        holder.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokens.scaled(11.5f))

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
        holder.itemView.setOnClickListener { onClick(app.packageName) }
    }

    override fun getItemCount() = apps.size
}
