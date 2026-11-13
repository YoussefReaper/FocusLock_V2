package com.focuslock.mdm

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Themed dialogs.
 *
 * The old build used `AlertDialog.Builder`, which meant every confirmation and
 * every app picker ignored the user's theme entirely — the single most visible
 * hole in the old design. These are plain [Dialog]s wearing a [FocusUi] card,
 * so a dialog looks like the screen that opened it, in every theme, at every
 * text scale.
 */
object FocusDialog {

    /**
     * A bottom sheet, not a centred card (2026-09 design system pass): pinned
     * to the bottom edge, top corners only. All four dialog "shapes" - alert,
     * singleChoice, the custom per-app sheet, textInput+banner+toast - ride
     * this one shell, so this is the one change that reshapes every dialog
     * in the app at once.
     */
    private fun shell(context: Context, tokens: UiPrefs.Tokens): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        val padH = FocusUi.dp(context, 20)
        card.setPadding(padH, FocusUi.dp(context, 10), padH, FocusUi.dp(context, 24))
        card.background = FocusUi.cornersShape(
            context,
            tokens.surface,
            topLeftDp = 28,
            topRightDp = 28,
            bottomRightDp = 0,
            bottomLeftDp = 0
        )

        // Quiet Pine grab handle: says "this slides away" without words.
        val grab = android.view.View(context)
        grab.background = FocusUi.roundedShape(context, UiPrefs.blend(tokens.divider, tokens.textMuted, 0.3f), 3)
        grab.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        card.addView(grab, LinearLayout.LayoutParams(FocusUi.dp(context, 38), FocusUi.dp(context, 5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = FocusUi.dp(context, 16)
        })

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(if (tokens.isLight) 0.35f else 0.6f)
            setGravity(Gravity.BOTTOM)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            attributes = attributes.apply {
                windowAnimations = android.R.style.Animation_InputMethod // slides from the bottom, no platform dialog fade
            }
            // The sheet rides above the keyboard: it draws edge to edge and lifts its own
            // content by the keyboard's height, so a field and its Save never hide behind it.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val baseBottom = card.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(card) { v, insets ->
            val types = androidx.core.view.WindowInsetsCompat.Type.ime() or
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + insets.getInsets(types).bottom)
            insets
        }
        return dialog to card
    }

    /** Heading role (19/25/700) - the dialog title, distinct from a screen's own larger title. */
    private fun addTitle(context: Context, tokens: UiPrefs.Tokens, card: LinearLayout, title: String) {
        if (title.isBlank()) return
        val heading = FocusUi.heading(context, tokens, title)
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(22f))
        FocusUi.applyDisplayFont(heading, tokens)
        card.addView(heading)
    }

    private fun addMessage(
        context: Context,
        tokens: UiPrefs.Tokens,
        card: LinearLayout,
        message: String
    ): TextView? {
        if (message.isBlank()) return null
        val view = FocusUi.secondary(context, tokens, message)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 10) }
        card.addView(view)
        return view
    }

    private fun addActions(
        context: Context,
        tokens: UiPrefs.Tokens,
        card: LinearLayout,
        confirmLabel: String?,
        cancelLabel: String?,
        destructive: Boolean,
        onConfirm: (() -> Unit)?,
        onCancel: (() -> Unit)?,
        dialog: Dialog
    ): TextView? {
        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END
        actions.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 18) }

        if (cancelLabel != null) {
            val cancel = FocusUi.ghostButton(context, tokens, cancelLabel) {
                dialog.dismiss()
                onCancel?.invoke()
            }
            cancel.layoutParams = LinearLayout.LayoutParams(
                0,
                FocusUi.dp(context, tokens.density.buttonHeightDp),
                1f
            ).apply { marginEnd = FocusUi.dp(context, 10) }
            actions.addView(cancel)
        }

        var confirmView: TextView? = null
        if (confirmLabel != null) {
            val confirm = if (destructive) {
                FocusUi.dangerButton(context, tokens, confirmLabel) {
                    dialog.dismiss()
                    onConfirm?.invoke()
                }
            } else {
                FocusUi.primaryButton(context, tokens, confirmLabel) {
                    dialog.dismiss()
                    onConfirm?.invoke()
                }
            }
            confirm.layoutParams = LinearLayout.LayoutParams(
                0,
                FocusUi.dp(context, tokens.density.buttonHeightDp),
                1f
            )
            actions.addView(confirm)
            confirmView = confirm
        }

        card.addView(actions)
        return confirmView
    }

    // ── Alert ─────────────────────────────────────────────────────

    fun alert(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String = context.getString(R.string.common_ok),
        cancelLabel: String? = null,
        destructive: Boolean = false,
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        addMessage(context, tokens, card, message)
        addActions(context, tokens, card, confirmLabel, cancelLabel, destructive, onConfirm, onCancel, dialog)
        dialog.show()
        return dialog
    }

    fun info(context: Context, title: String, message: String) {
        alert(context, title, message, confirmLabel = context.getString(R.string.common_got_it))
    }

    /**
     * The deliberate-pause confirmation, used for anything irreversible.
     * The countdown is the point: it makes a destructive tap impossible to do
     * by reflex, without ever refusing the user their own decision.
     */
    fun confirmWithCountdown(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        seconds: Int,
        onConfirm: () -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        val messageView = addMessage(context, tokens, card, message)

        val countdownView = FocusUi.caption(context, tokens, "")
        countdownView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 12) }
        card.addView(countdownView)

        val confirm = addActions(
            context, tokens, card, confirmLabel, context.getString(R.string.common_cancel), true, onConfirm, null, dialog
        )
        confirm?.isEnabled = false
        confirm?.alpha = 0.45f

        val handler = Handler(Looper.getMainLooper())
        var remaining = seconds
        val ticker = object : Runnable {
            override fun run() {
                remaining -= 1
                if (remaining <= 0) {
                    countdownView.text = context.getString(R.string.dialog_confirm_available_now)
                    confirm?.isEnabled = true
                    confirm?.alpha = 1f
                    return
                }
                countdownView.text = context.getString(R.string.dialog_confirm_in, remaining)
                handler.postDelayed(this, 1_000L)
            }
        }
        countdownView.text = context.getString(R.string.dialog_confirm_in, seconds)
        handler.postDelayed(ticker, 1_000L)
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }

        messageView?.setTextColor(tokens.textSecondary)
        dialog.show()
        return dialog
    }

    // ── Choice ────────────────────────────────────────────────────

    data class Choice(
        val key: String,
        val label: String,
        val subtitle: String? = null,
        val leadingPackage: String? = null
    )

    /**
     * A searchable multi-select.
     *
     * The old picker was a raw `setMultiChoiceItems` over every installed app,
     * which on a real phone is a 200-row unlabelled list with no way to find
     * anything. Search, a live count and a select-all shortcut are what make
     * this usable rather than technically present.
     */
    fun multiChoice(
        context: Context,
        title: String,
        subtitle: String?,
        choices: List<Choice>,
        selected: Set<String>,
        confirmLabel: String = context.getString(R.string.common_save),
        onSave: (Set<String>) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        if (!subtitle.isNullOrBlank()) addMessage(context, tokens, card, subtitle)

        val working = selected.toMutableSet()

        val countView = FocusUi.caption(context, tokens, context.getString(R.string.dialog_selected_count, working.size))
        countView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 8) }

        val search = FocusUi.input(context, tokens, context.getString(R.string.common_search))
        search.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 14) }
        card.addView(search)
        card.addView(countView)

        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL

        val scroll = FocusUi.scroll(context, list)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            FocusUi.dp(context, 360)
        ).apply { topMargin = FocusUi.dp(context, 6) }
        card.addView(scroll)

        fun render(query: String) {
            list.removeAllViews()
            val needle = query.trim().lowercase(Locale.getDefault())
            val visible = choices.filter { choice ->
                needle.isEmpty() ||
                    choice.label.lowercase(Locale.getDefault()).contains(needle) ||
                    choice.key.lowercase(Locale.getDefault()).contains(needle)
            }

            if (visible.isEmpty()) {
                list.addView(FocusUi.emptyState(context, tokens, context.getString(R.string.dialog_nothing_matches)))
                return
            }

            visible.forEach { choice ->
                val control = FocusUi.switchControl(context, tokens, choice.key in working) { checked ->
                    if (checked) working.add(choice.key) else working.remove(choice.key)
                    countView.text = context.getString(R.string.dialog_selected_count, working.size)
                }
                val leading = choice.leadingPackage?.let { FocusUi.appIcon(context, tokens, it, 32) }
                list.addView(
                    FocusUi.listRow(
                        context,
                        tokens,
                        choice.label,
                        choice.subtitle,
                        trailing = control,
                        leading = leading
                    ) { control.isChecked = !control.isChecked }
                )
            }
        }

        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                render(s?.toString().orEmpty())
            }
        })

        addActions(
            context, tokens, card, confirmLabel, context.getString(R.string.common_cancel), false,
            { onSave(working.toSet()) }, null, dialog
        )
        dialog.show()
        return dialog
    }

    fun singleChoice(
        context: Context,
        title: String,
        subtitle: String?,
        choices: List<Choice>,
        selectedKey: String?,
        onSelect: (String) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        if (!subtitle.isNullOrBlank()) addMessage(context, tokens, card, subtitle)

        // The policy ladder's own option row (2026-09 design system pass):
        // 62h/r16, current selection = accentSoft fill + a 1.5dp accent ring
        // + a "Now" pill, replacing the old bullet/circle glyph marker.
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        choices.forEach { choice ->
            val isCurrent = choice.key == selectedKey
            val option = LinearLayout(context)
            option.orientation = LinearLayout.HORIZONTAL
            option.gravity = Gravity.CENTER_VERTICAL
            val padH = FocusUi.dp(context, 15)
            option.setPadding(padH, FocusUi.dp(context, 13), padH, FocusUi.dp(context, 13))
            option.minimumHeight = FocusUi.dp(context, 62)
            option.background = FocusUi.withRipple(
                context,
                FocusUi.roundedShape(
                    context,
                    if (isCurrent) tokens.accentSoft else android.graphics.Color.TRANSPARENT,
                    tokens.rowRadiusDp,
                    if (isCurrent) tokens.accent else null,
                    strokeWidthDp = if (isCurrent) 2 else 1 // nearest whole dp to the doc's 1.5dp ring
                ),
                tokens
            )
            option.isClickable = true
            option.isFocusable = true
            option.setOnClickListener {
                dialog.dismiss()
                onSelect(choice.key)
            }

            val textColumn = LinearLayout(context)
            textColumn.orientation = LinearLayout.VERTICAL
            textColumn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val titleView = TextView(context)
            titleView.text = choice.label
            titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(15f))
            titleView.setTextColor(tokens.textPrimary)
            FocusUi.applyFont(titleView, tokens, weight = 600)
            textColumn.addView(titleView)
            if (!choice.subtitle.isNullOrBlank()) {
                val subtitleView = TextView(context)
                subtitleView.text = choice.subtitle
                subtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tokens.scaled(13f))
                // The current option's subtitle is promoted to textSecondary
                // for extra emphasis; every other option stays at textMuted.
                subtitleView.setTextColor(if (isCurrent) tokens.textSecondary else tokens.textMuted)
                FocusUi.applyFont(subtitleView, tokens, weight = 400)
                subtitleView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = FocusUi.dp(context, 2) }
                textColumn.addView(subtitleView)
            }
            option.addView(textColumn)

            if (isCurrent) {
                option.addView(FocusUi.spacerH(context, 10))
                option.addView(FocusUi.pill(context, tokens, context.getString(R.string.common_now), tokens.accent))
            }

            list.addView(option)
            list.addView(FocusUi.spacer(context, 6))
        }

        val scroll = FocusUi.boundedScroll(context, list, FocusUi.dialogScrollMaxHeight(context))
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 8) }
        card.addView(scroll)

        addActions(context, tokens, card, null, context.getString(R.string.common_close), false, null, null, dialog)
        dialog.show()
        return dialog
    }

    // ── Input ─────────────────────────────────────────────────────

    fun textInput(
        context: Context,
        title: String,
        subtitle: String?,
        hint: String,
        value: String = "",
        multiline: Boolean = false,
        numeric: Boolean = false,
        password: Boolean = false,
        confirmLabel: String = context.getString(R.string.common_save),
        onSave: (String) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        if (!subtitle.isNullOrBlank()) addMessage(context, tokens, card, subtitle)

        val field = if (password) passwordField(context, tokens, card, hint, 14) else
            FocusUi.input(context, tokens, hint, value, multiline, numeric).also {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = FocusUi.dp(context, 14) }
                card.addView(it)
            }
        field.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        addActions(
            context, tokens, card, confirmLabel, context.getString(R.string.common_cancel), false,
            { onSave(field.text?.toString()?.let { if (password) it else it.trim() }.orEmpty()) }, null, dialog
        )
        dialog.show()
        return dialog
    }

    /**
     * A new password, made carefully: typed twice, an eye to see it, and the rules shown
     * while typing (each one lights up as it is met), so Save only works once all are.
     */
    fun newPassword(
        context: Context,
        title: String,
        subtitle: String?,
        hint: String,
        repeatHint: String,
        rules: List<Pair<String, (String) -> Boolean>>,
        matchRule: String,
        onSave: (String) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        if (!subtitle.isNullOrBlank()) addMessage(context, tokens, card, subtitle)

        val first = passwordField(context, tokens, card, hint, 14)
        val second = passwordField(context, tokens, card, repeatHint, 10)

        val all = rules + (matchRule to { _: String -> first.text.toString() == second.text.toString() && second.text.isNotEmpty() })
        val lines = all.map { (label, _) ->
            FocusUi.secondary(context, tokens, label).also { line ->
                line.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = FocusUi.dp(context, 6) }
                card.addView(line)
            }
        }
        val confirm = addActions(
            context, tokens, card, context.getString(R.string.common_save), context.getString(R.string.common_cancel), false,
            { onSave(first.text.toString()) }, null, dialog
        )
        fun refresh() {
            val typed = first.text.toString()
            var ok = true
            all.forEachIndexed { i, (label, test) ->
                val met = test(typed)
                ok = ok && met
                lines[i].text = (if (met) "\u2713  " else "\u2022  ") + label
                lines[i].setTextColor(if (met) tokens.accent else tokens.textMuted)
            }
            confirm?.isEnabled = ok
            confirm?.alpha = if (ok) 1f else 0.4f
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        first.addTextChangedListener(watcher)
        second.addTextChangedListener(watcher)
        refresh()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        first.requestFocus()
        dialog.show()
        return dialog
    }

    /** A password box with an eye on its end that shows or hides what was typed. */
    private fun passwordField(context: Context, tokens: UiPrefs.Tokens, card: LinearLayout, hint: String, topDp: Int): android.widget.EditText {
        val hidden = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val shown = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val field = FocusUi.input(context, tokens, hint)
        field.inputType = hidden
        // A password type switches the box to monospace; keep the app's own face.
        FocusUi.applyFont(field, tokens, mono = false, weight = 400)
        field.setPaddingRelative(field.paddingStart, field.paddingTop, FocusUi.dp(context, 52), field.paddingBottom)
        val frame = android.widget.FrameLayout(context)
        frame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, topDp) }
        frame.addView(field, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val eye = android.widget.ImageView(context)
        val pad = FocusUi.dp(context, 12)
        eye.setPadding(pad, pad, pad, pad)
        eye.setImageResource(R.drawable.ic_eye)
        eye.imageTintList = android.content.res.ColorStateList.valueOf(tokens.textMuted)
        eye.contentDescription = context.getString(R.string.password_show)
        eye.setOnClickListener {
            val showing = field.inputType == shown
            val at = field.selectionEnd
            field.inputType = if (showing) hidden else shown
            // Changing the input type resets the typeface; keep the app's own.
            FocusUi.applyFont(field, tokens, mono = false, weight = 400)
            field.setSelection(at.coerceIn(0, field.text.length))
            eye.setImageResource(if (showing) R.drawable.ic_eye else R.drawable.ic_eye_off)
            eye.contentDescription = context.getString(if (showing) R.string.password_show else R.string.password_hide)
        }
        frame.addView(eye, android.widget.FrameLayout.LayoutParams(
            FocusUi.dp(context, 48), FocusUi.dp(context, 48), Gravity.END or Gravity.CENTER_VERTICAL))
        card.addView(frame)
        return field
    }

    /**
     * A time picker in the app's own language.
     *
     * The platform picker cannot be themed to arbitrary tokens, and a stock
     * blue wheel in the middle of a themed app is exactly the inconsistency
     * this refactor set out to remove.
     *
     * Typed, not dragged. It used to be two sliders, so picking 9:05 meant
     * dragging a 60-step track to exactly the right pixel; and it was hardcoded
     * to 24-hour whatever the phone said. Both fields take a real number now,
     * and a 12-hour phone gets an AM/PM pair beside them. See [TimeText].
     */
    fun timePicker(
        context: Context,
        title: String,
        initialMinutes: Int,
        onPick: (Int) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)

        val twelveHour = !TimeText.uses24Hour(context)
        var hour = (initialMinutes / 60).coerceIn(0, 23)
        var minute = (initialMinutes % 60).coerceIn(0, 59)

        val display = FocusUi.display(context, tokens, TimeText.ofDay(context, hour, minute))
        display.gravity = Gravity.CENTER
        display.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = FocusUi.dp(context, 14)
            bottomMargin = FocusUi.dp(context, 14)
        }
        card.addView(display)

        fun refresh() {
            display.text = TimeText.ofDay(context, hour, minute)
        }

        val fields = FocusUi.row(context)
        fields.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // On a 12-hour clock the hour field counts 1-12 and the AM/PM pair
        // carries the other twelve hours, so 12am and 12pm both exist and
        // neither of them is "0".
        val hourField = FocusUi.numberStepper(
            context,
            tokens,
            value = if (twelveHour) displayHour(hour) else hour,
            min = if (twelveHour) 1 else 0,
            max = if (twelveHour) 12 else 23,
            wrap = true,
            format = { if (twelveHour) it.toString() else it.toString().padStart(2, '0') }
        ) { typed ->
            hour = if (twelveHour) combine(typed, hour >= 12) else typed
            refresh()
        }
        hourField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        fields.addView(hourField)
        fields.addView(FocusUi.spacerH(context, 10))

        val minuteField = FocusUi.numberStepper(
            context,
            tokens,
            value = minute,
            min = 0,
            max = 59,
            step = 5,
            wrap = true
        ) { typed ->
            minute = typed
            refresh()
        }
        minuteField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        fields.addView(minuteField)
        card.addView(fields)

        if (twelveHour) {
            card.addView(FocusUi.spacer(context, 10))
            val halves = listOf(TimeText.ofDay(context, 9, 0), TimeText.ofDay(context, 21, 0))
                .map { it.substringAfter(' ') }
            card.addView(
                FocusUi.chipStrip(context, tokens, halves, if (hour < 12) 0 else 1) { index ->
                    hour = combine(displayHour(hour), index == 1)
                    refresh()
                }
            )
        }

        addActions(
            context, tokens, card, context.getString(R.string.common_set), context.getString(R.string.common_cancel), false,
            { onPick(hour * 60 + minute) }, null, dialog
        )
        dialog.show()
        return dialog
    }

    /** 0-23 as it reads on a 12-hour face: midnight and noon are both 12. */
    private fun displayHour(hour24: Int): Int = if (hour24 % 12 == 0) 12 else hour24 % 12

    /** The inverse: a 12-hour face reading plus which half of the day it is. */
    private fun combine(displayed: Int, afternoon: Boolean): Int {
        val base = if (displayed == 12) 0 else displayed
        return if (afternoon) base + 12 else base
    }

    /**
     * A date and time picker in the app's own language.
     *
     * Built around "how many days from now" rather than a calendar grid, because
     * that is how people actually phrase a task deadline — tomorrow, the weekend,
     * next week — and because a themed calendar widget is a lot of surface for a
     * question with five common answers.
     */
    fun dateTimePicker(
        context: Context,
        title: String,
        initialMs: Long?,
        allowClear: Boolean = true,
        onPick: (Long?) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)

        val calendar = java.util.Calendar.getInstance()
        if (initialMs != null && initialMs > 0L) calendar.timeInMillis = initialMs
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        var offsetDays = (((calendar.timeInMillis - startOfToday) / 86_400_000L).toInt()).coerceIn(0, 180)
        var hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        var minute = calendar.get(java.util.Calendar.MINUTE)

        fun resolved(): Long {
            val out = java.util.Calendar.getInstance()
            out.timeInMillis = startOfToday
            out.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
            out.set(java.util.Calendar.HOUR_OF_DAY, hour)
            out.set(java.util.Calendar.MINUTE, minute)
            return out.timeInMillis
        }

        val display = FocusUi.title(context, tokens, "")
        display.gravity = Gravity.CENTER
        display.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = FocusUi.dp(context, 14)
            bottomMargin = FocusUi.dp(context, 4)
        }

        fun refreshDisplay() {
            display.text = TimeText.dateTime(context, resolved(), withWeekday = true)
        }
        refreshDisplay()
        card.addView(display)

        val quick = listOf(
            context.getString(R.string.common_today) to 0,
            context.getString(R.string.common_tomorrow) to 1,
            context.getString(R.string.dialog_days_in_3) to 3,
            context.getString(R.string.dialog_days_next_week) to 7,
            context.getString(R.string.dialog_days_in_2_weeks) to 14
        )
        card.addView(
            FocusUi.chipStrip(
                context,
                tokens,
                quick.map { it.first },
                quick.indexOfFirst { it.second == offsetDays }
            ) { index ->
                offsetDays = quick[index].second
                refreshDisplay()
            }
        )

        card.addView(
            FocusUi.sliderRow(context, tokens, context.getString(R.string.dialog_days_from_today), 0, 180, offsetDays, {
                when (it) {
                    0 -> context.getString(R.string.common_today)
                    1 -> context.getString(R.string.common_tomorrow)
                    else -> context.getString(R.string.dialog_in_days, it)
                }
            }) { value ->
                offsetDays = value
                refreshDisplay()
            }
        )
        // Typed, like the time picker above - dragging a 24-step track to land
        // on a specific hour is not a way to enter a number.
        val clock = FocusUi.row(context)
        clock.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 10) }

        val hourField = FocusUi.numberStepper(context, tokens, hour, 0, 23, wrap = true) {
            hour = it
            refreshDisplay()
        }
        hourField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clock.addView(hourField)
        clock.addView(FocusUi.spacerH(context, 10))

        val minuteField = FocusUi.numberStepper(context, tokens, minute, 0, 59, step = 5, wrap = true) {
            minute = it
            refreshDisplay()
        }
        minuteField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clock.addView(minuteField)
        card.addView(clock)

        if (allowClear) {
            card.addView(
                FocusUi.ghostButton(context, tokens, context.getString(R.string.dialog_no_date)) {
                    dialog.dismiss()
                    onPick(null)
                }
            )
        }

        addActions(context, tokens, card, context.getString(R.string.common_set), context.getString(R.string.common_cancel), false, { onPick(resolved()) }, null, dialog)
        dialog.show()
        return dialog
    }

    /** Free-form themed dialog for screens that need their own body. */
    /**
     * @param build Given the body to fill in, the tokens, and a `refresh`
     * callback. A row whose displayed value comes from a nested picker
     * (a time, a day set, a chosen app list) has to call `refresh()` after
     * changing its backing variable, or the row just keeps showing what it
     * was built with - the dialog is only built once, so mutating a local
     * `var` never touches the TextView already on screen. `refresh()` tears
     * the body down and rebuilds it from the current values, which is what
     * makes an edit actually visible without closing and reopening the sheet.
     */
    fun custom(
        context: Context,
        title: String,
        subtitle: String? = null,
        confirmLabel: String? = context.getString(R.string.common_save),
        cancelLabel: String? = context.getString(R.string.common_cancel),
        onConfirm: (() -> Unit)? = null,
        build: (LinearLayout, UiPrefs.Tokens, () -> Unit) -> Unit
    ): Dialog {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, title)
        if (!subtitle.isNullOrBlank()) addMessage(context, tokens, card, subtitle)

        val body = LinearLayout(context)
        body.orientation = LinearLayout.VERTICAL
        fun rebuildBody() {
            body.removeAllViews()
            build(body, tokens, ::rebuildBody)
        }
        rebuildBody()

        // Bounded, not WRAP_CONTENT: a schedule/task editor's body routinely
        // runs longer than the screen, and a WRAP_CONTENT scroll view doesn't
        // scroll at all - it just grows the whole sheet off the bottom,
        // taking Save with it. See FocusUi.boundedScroll.
        val scroll = FocusUi.boundedScroll(context, body, FocusUi.dialogScrollMaxHeight(context))
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 12) }
        card.addView(scroll)

        addActions(context, tokens, card, confirmLabel, cancelLabel, false, onConfirm, null, dialog)
        dialog.show()
        return dialog
    }

    /**
     * The one honest line shown when a safety capability is switched off.
     * It states the consequence, offers no argument, and never blocks the change.
     */
    fun weakenNotice(context: Context, spec: CapabilitySpec) {
        val tokens = UiPrefs.resolve(context)
        val (dialog, card) = shell(context, tokens)
        addTitle(context, tokens, card, context.getString(R.string.cap_is_off, spec.label(context)))

        val note = FocusUi.body(context, tokens, Copy.weakenWarning(context, spec))
        note.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = FocusUi.dp(context, 12) }
        card.addView(note)

        addActions(context, tokens, card, context.getString(R.string.common_understood), null, false, null, null, dialog)
        dialog.show()
    }

    fun toast(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** A themed snack-style banner, for confirmations that do not need a dialog. */
    fun banner(host: ViewGroup, tokens: UiPrefs.Tokens, message: String) {
        val context = host.context
        // r16 surfaceAlt, not a filled accent block - a banner reports
        // something, it isn't a call to action, so it shouldn't compete
        // with a primary button for the eye.
        val view = FocusUi.body(context, tokens, message)
        view.setTextColor(tokens.textSecondary)
        val padding = FocusUi.dp(context, 14)
        view.setPadding(padding, padding, padding, padding)
        view.background = FocusUi.roundedShape(context, tokens.surfaceAlt, tokens.rowRadiusDp)
        view.alpha = 0f
        host.addView(view)
        view.animate().alpha(1f).setDuration(180L).start()
        Handler(Looper.getMainLooper()).postDelayed({
            view.animate().alpha(0f).setDuration(180L).withEndAction {
                (view.parent as? ViewGroup)?.removeView(view)
            }.start()
        }, 2_600L)
    }
}
