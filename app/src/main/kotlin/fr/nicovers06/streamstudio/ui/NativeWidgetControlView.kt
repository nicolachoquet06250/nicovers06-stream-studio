package fr.nicovers06.streamstudio.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.nicovers06.streamstudio.R
import fr.nicovers06.streamstudio.model.NativeWidgetComponent
import fr.nicovers06.streamstudio.model.PollKind
import fr.nicovers06.streamstudio.model.PollOption
import fr.nicovers06.streamstudio.model.ShapeKind
import fr.nicovers06.streamstudio.model.TextKind
import fr.nicovers06.streamstudio.model.TimerMode
import fr.nicovers06.streamstudio.model.WidgetType
import java.util.Locale

/** Panneau d'édition natif commun aux widgets dynamiques. */
class NativeWidgetControlView(
    context: Context,
    val widgetType: WidgetType,
) : LinearLayout(context) {
    data class Callbacks(
        val onChanged: (NativeWidgetComponent) -> Unit,
        val onRemoved: () -> Unit,
        val onPickMedia: () -> Unit,
        val onValidationError: (String) -> Unit,
    )

    val layerHandle: ImageView
    private val enableSwitch: Switch
    private val options: LinearLayout
    private var binding = false
    private var current: NativeWidgetComponent? = null

    init {
        require(widgetType in NativeWidgetComponent.NATIVE_TYPES)
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        addView(View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(color(R.color.border_soft))
        })
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        layerHandle = ImageView(context).apply {
            layoutParams = LayoutParams(dp(40), dp(52))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_drag_handle)
            contentDescription = context.getString(R.string.widget_layer_handle)
        }
        enableSwitch = Switch(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(color(R.color.text_primary))
            textSize = 14f
        }
        header.addView(layerHandle)
        header.addView(enableSwitch)
        addView(header)

        options = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            setPadding(dp(40), 0, 0, 0)
        }
        addView(options)
    }

    fun bind(
        widget: NativeWidgetComponent,
        displayLabel: String,
        editable: Boolean,
        runtimeActionsEnabled: Boolean = true,
        callbacks: Callbacks,
    ) {
        require(widget.type == widgetType)
        current = widget
        binding = true
        enableSwitch.text = displayLabel
        enableSwitch.isChecked = widget.enabled
        enableSwitch.isEnabled = editable
        layerHandle.isEnabled = editable && widget.type != WidgetType.BACKGROUND
        options.visibility = if (widget.enabled) View.VISIBLE else View.GONE
        options.removeAllViews()
        when (widget.type) {
            WidgetType.TIMER -> buildTimerEditor(widget, editable, runtimeActionsEnabled, callbacks)
            WidgetType.SHAPE -> buildShapeEditor(widget, editable, callbacks)
            WidgetType.BACKGROUND -> buildBackgroundEditor(widget, editable, callbacks)
            WidgetType.TICKER -> buildTickerEditor(widget, editable, callbacks)
            WidgetType.MEDIA -> buildMediaEditor(widget, editable, callbacks)
            WidgetType.ALERT -> buildAlertEditor(widget, editable, runtimeActionsEnabled, callbacks)
            WidgetType.POLL -> buildPollEditor(widget, editable, callbacks)
            WidgetType.TEXT -> buildTextEditor(widget, editable, callbacks)
            else -> Unit
        }
        addRemoveButton(editable, callbacks)
        enableSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!binding) callbacks.onChanged(widget.copy(enabled = enabled))
        }
        binding = false
    }

    private fun buildTimerEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        runtimeActionsEnabled: Boolean,
        callbacks: Callbacks,
    ) {
        val mode = spinner(
            listOf(
                context.getString(R.string.native_widget_timer_countdown),
                context.getString(R.string.native_widget_timer_stopwatch),
            ),
            if (widget.timerMode == TimerMode.COUNTDOWN) 0 else 1,
            editable,
        )
        addLabel(R.string.native_widget_timer_mode)
        options.addView(mode)
        val duration = field(
            R.string.native_widget_timer_duration,
            widget.timerDurationSeconds.toString(),
            InputType.TYPE_CLASS_NUMBER,
            editable,
        )
        options.addView(duration)
        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        val toggle = button(
            if (widget.timerRunning) R.string.native_widget_timer_pause else R.string.native_widget_timer_start,
            runtimeActionsEnabled,
        ).apply {
            layoutParams = LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                val configured = widget.copy(
                    timerMode = if (mode.selectedItemPosition == 0) TimerMode.COUNTDOWN else TimerMode.STOPWATCH,
                    timerDurationSeconds = duration.text.toString().toLongOrNull()?.coerceIn(1L, 359_999L)
                        ?: widget.timerDurationSeconds,
                )
                callbacks.onChanged(if (configured.timerRunning) configured.pauseTimer() else configured.startTimer())
            }
        }
        val reset = button(R.string.native_widget_timer_reset, runtimeActionsEnabled).apply {
            layoutParams = LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) }
            setOnClickListener {
                callbacks.onChanged(
                    widget.copy(
                        timerMode = if (mode.selectedItemPosition == 0) TimerMode.COUNTDOWN else TimerMode.STOPWATCH,
                        timerDurationSeconds = duration.text.toString().toLongOrNull()?.coerceIn(1L, 359_999L)
                            ?: widget.timerDurationSeconds,
                    ).resetTimer(),
                )
            }
        }
        buttonRow.addView(toggle)
        buttonRow.addView(reset)
        options.addView(buttonRow)
    }

    private fun buildShapeEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        callbacks: Callbacks,
    ) {
        addLabel(R.string.native_widget_shape_kind)
        val kind = spinner(
            listOf(
                context.getString(R.string.native_widget_shape_rectangle),
                context.getString(R.string.native_widget_shape_ellipse),
                context.getString(R.string.native_widget_shape_line),
            ),
            widget.shapeKind.ordinal,
            editable,
        )
        options.addView(kind)
        val color = colorField(R.string.native_widget_background_color, widget.backgroundColor, editable)
        options.addView(color)
        addApplyButton(editable) {
            val parsed = parseColor(color, callbacks) ?: return@addApplyButton
            callbacks.onChanged(widget.copy(shapeKind = ShapeKind.entries[kind.selectedItemPosition], backgroundColor = parsed))
        }
    }

    private fun buildBackgroundEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        callbacks: Callbacks,
    ) {
        val primary = colorField(R.string.native_widget_background_color, widget.backgroundColor, editable)
        val accent = colorField(R.string.native_widget_accent_color, widget.accentColor, editable)
        val gradient = switch(R.string.native_widget_background_gradient, widget.gradientEnabled, editable)
        options.addView(primary)
        options.addView(accent)
        options.addView(gradient)
        addApplyButton(editable) {
            val primaryColor = parseColor(primary, callbacks) ?: return@addApplyButton
            val accentColor = parseColor(accent, callbacks) ?: return@addApplyButton
            callbacks.onChanged(
                widget.copy(
                    backgroundColor = primaryColor,
                    accentColor = accentColor,
                    gradientEnabled = gradient.isChecked,
                ),
            )
        }
    }

    private fun buildTickerEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        callbacks: Callbacks,
    ) {
        val title = field(R.string.native_widget_title, widget.title, InputType.TYPE_CLASS_TEXT, editable)
        val speed = field(
            R.string.native_widget_ticker_speed,
            String.format(Locale.ROOT, "%.2f", widget.tickerSpeed),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            editable,
        )
        val foreground = colorField(R.string.native_widget_foreground_color, widget.foregroundColor, editable)
        val background = colorField(R.string.native_widget_background_color, widget.backgroundColor, editable)
        val accent = colorField(R.string.native_widget_accent_color, widget.accentColor, editable)
        options.addView(title)
        options.addView(speed)
        options.addView(foreground)
        options.addView(background)
        options.addView(accent)
        addApplyButton(editable) {
            val colors = parseColors(callbacks, foreground, background, accent) ?: return@addApplyButton
            val parsedSpeed = speed.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.05f, 1.2f)
                ?: widget.tickerSpeed
            callbacks.onChanged(
                widget.copy(
                    title = title.text.toString(),
                    tickerSpeed = parsedSpeed,
                    foregroundColor = colors[0],
                    backgroundColor = colors[1],
                    accentColor = colors[2],
                ),
            )
        }
    }

    private fun buildMediaEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        callbacks: Callbacks,
    ) {
        options.addView(TextView(context).apply {
            text = if (widget.mediaFileName.isBlank()) {
                context.getString(R.string.native_widget_media_none)
            } else {
                context.getString(R.string.native_widget_media_file, widget.mediaDisplayName)
            }
            setTextColor(color(R.color.text_secondary))
            textSize = 11f
        })
        val choose = button(R.string.native_widget_media_choose, editable).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
        }
        val loop = switch(R.string.native_widget_media_loop, widget.mediaLoop, editable)
        val ratio = switch(R.string.native_widget_media_ratio, widget.mediaKeepAspectRatio, editable)
        ratio.setOnCheckedChangeListener { _, enabled ->
            if (!binding) {
                callbacks.onChanged(widget.copy(mediaLoop = loop.isChecked, mediaKeepAspectRatio = enabled))
            }
        }
        choose.setOnClickListener {
            callbacks.onChanged(widget.copy(mediaLoop = loop.isChecked, mediaKeepAspectRatio = ratio.isChecked))
            callbacks.onPickMedia()
        }
        options.addView(choose)
        options.addView(loop)
        options.addView(ratio)
        options.addView(helpText(R.string.native_widget_media_audio_notice))
        addApplyButton(editable) {
            callbacks.onChanged(widget.copy(mediaLoop = loop.isChecked, mediaKeepAspectRatio = ratio.isChecked))
        }
    }

    private fun buildAlertEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        runtimeActionsEnabled: Boolean,
        callbacks: Callbacks,
    ) {
        val title = field(R.string.native_widget_title, widget.title, InputType.TYPE_CLASS_TEXT, editable)
        val subtitle = field(R.string.native_widget_subtitle, widget.subtitle, InputType.TYPE_CLASS_TEXT, editable)
        val duration = field(
            R.string.native_widget_alert_duration,
            widget.alertDurationSeconds.toString(),
            InputType.TYPE_CLASS_NUMBER,
            editable,
        )
        val foreground = colorField(R.string.native_widget_foreground_color, widget.foregroundColor, editable)
        val background = colorField(R.string.native_widget_background_color, widget.backgroundColor, editable)
        val accent = colorField(R.string.native_widget_accent_color, widget.accentColor, editable)
        options.addView(title)
        options.addView(subtitle)
        options.addView(duration)
        options.addView(foreground)
        options.addView(background)
        options.addView(accent)
        fun configured(trigger: Boolean): NativeWidgetComponent? {
            val colors = parseColors(callbacks, foreground, background, accent) ?: return null
            return widget.copy(
                title = title.text.toString(),
                subtitle = subtitle.text.toString(),
                alertDurationSeconds = duration.text.toString().toIntOrNull()?.coerceIn(1, 60)
                    ?: widget.alertDurationSeconds,
                foregroundColor = colors[0],
                backgroundColor = colors[1],
                accentColor = colors[2],
                alertTriggeredAtEpochMs = if (trigger) System.currentTimeMillis() else widget.alertTriggeredAtEpochMs,
            )
        }
        addApplyButton(editable) { configured(false)?.let(callbacks.onChanged) }
        options.addView(button(R.string.native_widget_alert_test, runtimeActionsEnabled).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
            setOnClickListener { configured(true)?.let(callbacks.onChanged) }
        })
        options.addView(helpText(R.string.native_widget_alert_notice))
    }

    private fun buildPollEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        callbacks: Callbacks,
    ) {
        addLabel(R.string.native_widget_poll_kind)
        val kind = spinner(
            listOf(context.getString(R.string.native_widget_poll), context.getString(R.string.native_widget_question)),
            if (widget.pollKind == PollKind.POLL) 0 else 1,
            editable,
        )
        options.addView(kind)
        val title = field(R.string.native_widget_title, widget.title, InputType.TYPE_CLASS_TEXT, editable)
        options.addView(title)
        addLabel(R.string.native_widget_poll_options)
        val optionLines = widget.pollOptions.joinToString("\n") { "${it.label} | ${it.value}" }
        val answers = EditText(context).apply {
            setText(optionLines)
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isEnabled = editable
            applyInputStyle()
        }
        options.addView(answers)
        val foreground = colorField(R.string.native_widget_foreground_color, widget.foregroundColor, editable)
        val background = colorField(R.string.native_widget_background_color, widget.backgroundColor, editable)
        val accent = colorField(R.string.native_widget_accent_color, widget.accentColor, editable)
        options.addView(foreground)
        options.addView(background)
        options.addView(accent)
        addApplyButton(editable) {
            val colors = parseColors(callbacks, foreground, background, accent) ?: return@addApplyButton
            val parsedOptions = answers.text.lineSequence()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@mapNotNull null
                    val parts = trimmed.split('|', limit = 2)
                    PollOption(parts[0].trim(), parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                }
                .take(NativeWidgetComponent.MAX_POLL_OPTIONS)
                .toList()
                .ifEmpty { NativeWidgetComponent.defaultPollOptions() }
            callbacks.onChanged(
                widget.copy(
                    pollKind = if (kind.selectedItemPosition == 0) PollKind.POLL else PollKind.QUESTION,
                    title = title.text.toString(),
                    pollOptions = parsedOptions,
                    foregroundColor = colors[0],
                    backgroundColor = colors[1],
                    accentColor = colors[2],
                ),
            )
        }
    }

    private fun buildTextEditor(
        widget: NativeWidgetComponent,
        editable: Boolean,
        callbacks: Callbacks,
    ) {
        addLabel(R.string.native_widget_text_kind)
        val kind = spinner(
            listOf(context.getString(R.string.native_widget_text_plain), context.getString(R.string.native_widget_text_lower_third)),
            if (widget.textKind == TextKind.TEXT) 0 else 1,
            editable,
        )
        options.addView(kind)
        val title = field(R.string.native_widget_title, widget.title, InputType.TYPE_CLASS_TEXT, editable)
        val subtitle = field(R.string.native_widget_subtitle, widget.subtitle, InputType.TYPE_CLASS_TEXT, editable)
        val foreground = colorField(R.string.native_widget_foreground_color, widget.foregroundColor, editable)
        val background = colorField(R.string.native_widget_background_color, widget.backgroundColor, editable)
        val accent = colorField(R.string.native_widget_accent_color, widget.accentColor, editable)
        options.addView(title)
        options.addView(subtitle)
        options.addView(foreground)
        options.addView(background)
        options.addView(accent)
        addApplyButton(editable) {
            val colors = parseColors(callbacks, foreground, background, accent) ?: return@addApplyButton
            callbacks.onChanged(
                widget.copy(
                    textKind = if (kind.selectedItemPosition == 0) TextKind.TEXT else TextKind.LOWER_THIRD,
                    title = title.text.toString(),
                    subtitle = subtitle.text.toString(),
                    foregroundColor = colors[0],
                    backgroundColor = colors[1],
                    accentColor = colors[2],
                ),
            )
        }
    }

    private fun addRemoveButton(editable: Boolean, callbacks: Callbacks) {
        options.addView(button(R.string.native_widget_remove, editable, danger = true).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
            setOnClickListener { callbacks.onRemoved() }
        })
    }

    private fun addApplyButton(editable: Boolean, action: () -> Unit) {
        options.addView(button(R.string.native_widget_apply, editable).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
            setOnClickListener { action() }
        })
    }

    private fun field(labelRes: Int, value: String, inputType: Int, editable: Boolean): EditText {
        addLabel(labelRes)
        return EditText(context).apply {
            setText(value)
            this.inputType = inputType
            isEnabled = editable
            maxLines = 2
            applyInputStyle()
        }
    }

    private fun colorField(labelRes: Int, value: Int, editable: Boolean): EditText =
        field(labelRes, String.format(Locale.ROOT, "#%08X", value), InputType.TYPE_CLASS_TEXT, editable)

    private fun EditText.applyInputStyle() {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        minHeight = dp(46)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(color(R.color.text_primary))
        setHintTextColor(color(R.color.text_muted))
        setBackgroundResource(R.drawable.bg_input)
        textSize = 13f
    }

    private fun addLabel(labelRes: Int) {
        options.addView(TextView(context).apply {
            setText(labelRes)
            setTextColor(color(R.color.text_secondary))
            textSize = 11f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
    }

    private fun spinner(items: List<String>, selected: Int, editable: Boolean): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        setSelection(selected.coerceIn(0, items.lastIndex), false)
        isEnabled = editable
        setBackgroundResource(R.drawable.bg_input)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) }
    }

    private fun switch(labelRes: Int, checked: Boolean, editable: Boolean): Switch = Switch(context).apply {
        setText(labelRes)
        isChecked = checked
        isEnabled = editable
        setTextColor(color(R.color.text_secondary))
        textSize = 12f
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46))
    }

    private fun button(labelRes: Int, editable: Boolean, danger: Boolean = false): Button = Button(context).apply {
        setText(labelRes)
        isAllCaps = false
        isEnabled = editable
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
        setBackgroundResource(R.drawable.bg_button_outline)
        setTextColor(color(if (danger) R.color.red else R.color.purple_light))
        textSize = 12f
    }

    private fun helpText(textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(color(R.color.text_muted))
        textSize = 10f
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun parseColor(input: EditText, callbacks: Callbacks): Int? = runCatching {
        Color.parseColor(input.text.toString().trim())
    }.getOrElse {
        callbacks.onValidationError(context.getString(R.string.native_widget_color_error))
        null
    }

    private fun parseColors(callbacks: Callbacks, vararg inputs: EditText): IntArray? {
        val result = IntArray(inputs.size)
        inputs.forEachIndexed { index, input ->
            result[index] = parseColor(input, callbacks) ?: return null
        }
        return result
    }

    private fun color(resource: Int): Int = ContextCompat.getColor(context, resource)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
