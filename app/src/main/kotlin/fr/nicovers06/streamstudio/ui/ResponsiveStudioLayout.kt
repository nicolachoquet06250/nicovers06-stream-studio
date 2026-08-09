package fr.nicovers06.streamstudio.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Keeps the editor and live controls side by side in a wide DeX window, then
 * stacks them when the available window width becomes too small.
 */
class ResponsiveStudioLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private var wide = false

    init {
        orientation = VERTICAL
        setBaselineAligned(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd)
            .coerceAtLeast(0)
        val useWideLayout = availableWidth >= dp(WIDE_BREAKPOINT_DP)
        configureChildren(useWideLayout, availableWidth)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun configureChildren(useWideLayout: Boolean, availableWidth: Int) {
        if (childCount < 2) return
        if (wide != useWideLayout) {
            wide = useWideLayout
            orientation = if (wide) HORIZONTAL else VERTICAL
        }

        val editorParams = getChildAt(0).layoutParams as? LayoutParams ?: return
        val controlsParams = getChildAt(1).layoutParams as? LayoutParams ?: return
        if (wide) {
            val controlsWidth = (availableWidth * CONTROLS_WIDTH_RATIO).roundToInt()
                .coerceIn(dp(MIN_CONTROLS_WIDTH_DP), dp(MAX_CONTROLS_WIDTH_DP))
            editorParams.width = 0
            editorParams.height = LayoutParams.WRAP_CONTENT
            editorParams.weight = 1f
            editorParams.marginEnd = dp(PANE_GAP_DP)
            controlsParams.width = controlsWidth
            controlsParams.height = LayoutParams.WRAP_CONTENT
            controlsParams.weight = 0f
            controlsParams.topMargin = 0
        } else {
            editorParams.width = LayoutParams.MATCH_PARENT
            editorParams.height = LayoutParams.WRAP_CONTENT
            editorParams.weight = 0f
            editorParams.marginEnd = 0
            controlsParams.width = LayoutParams.MATCH_PARENT
            controlsParams.height = LayoutParams.WRAP_CONTENT
            controlsParams.weight = 0f
            controlsParams.topMargin = dp(PANE_GAP_DP)
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    companion object {
        private const val WIDE_BREAKPOINT_DP = 840
        private const val MIN_CONTROLS_WIDTH_DP = 340
        private const val MAX_CONTROLS_WIDTH_DP = 430
        private const val PANE_GAP_DP = 16
        private const val CONTROLS_WIDTH_RATIO = 0.31f
    }
}
