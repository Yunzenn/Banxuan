package com.aiwatch.probe.conversation

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionDrawables

/**
 * Transcript view: the last few messages, newest at the bottom, older ones reachable by scrolling.
 *
 * A plain ScrollView over a LinearLayout on purpose. Home shows at most a handful of bubbles, so a
 * recycling list would add a dependency and a diffing problem for no benefit on a 410x502 panel.
 */
class ConversationListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private var emptyHint: TextView? = null
    private var messageLimit = CompanionDimensions.visibleMessageCount
    private var lastMessages: List<MessageItem> = emptyList()
    private var lastDotFrame = -1

    fun setMessageLimit(limit: Int) {
        if (messageLimit == limit) return
        messageLimit = limit
        render(lastMessages, lastDotFrame)
    }

    init {
        isFillViewport = true
        addView(column)
    }

    /**
     * Rebuilds the transcript. [dotFrame] >= 0 animates the trailing pending bubble, which is how the
     * THINKING state becomes visible without a spinner widget.
     */
    fun render(messages: List<MessageItem>, dotFrame: Int = -1) {
        lastMessages = messages
        lastDotFrame = dotFrame
        column.removeAllViews()
        val visible = messages.takeLast(messageLimit)
        if (visible.isEmpty()) {
            showEmptyHint()
            return
        }
        val margin = CompanionDrawables.dp(context, CompanionDimensions.edgeMarginDp.toFloat()).toInt()
        val gap = CompanionDrawables.dp(context, 6f).toInt()
        visible.forEachIndexed { index, item ->
            val bubble = MessageBubbleView(context)
            bubble.render(item, if (item.pending) dotFrame else -1)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (index == 0) 0 else gap
                gravity = if (item.isUser) Gravity.END else Gravity.START
            }
            bubble.maxWidth = maxBubbleWidth()
            bubble.layoutParams = params
            column.addView(bubble)
        }
        column.setPadding(0, margin / 2, 0, margin / 2)
        post { fullScroll(View.FOCUS_DOWN) }
    }

    private fun showEmptyHint() {
        val margin = CompanionDrawables.dp(context, CompanionDimensions.edgeMarginDp.toFloat()).toInt()
        val hint = TextView(context).apply {
            text = "按住下面的按钮，和我说说话吧"
            setTextColor(CompanionColors.secondaryText)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        emptyHint = hint
        column.setPadding(0, margin, 0, margin)
        column.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun maxBubbleWidth(): Int {
        return ((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0) * CompanionDimensions.bubbleMaxWidthFraction).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = ((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) *
            CompanionDimensions.bubbleMaxWidthFraction).toInt().coerceAtLeast(0)
        for (index in 0 until column.childCount) {
            (column.getChildAt(index) as? MessageBubbleView)?.maxWidth = maxWidth
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
