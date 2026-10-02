package com.aiwatch.probe.voice

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.text.TextPaint
import android.text.TextUtils
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.accentColor
import com.aiwatch.probe.conversation.isBusy
import com.aiwatch.probe.conversation.pushToTalkLabel
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionDrawables

/**
 * Bottom push-to-talk capsule.
 *
 * Drawn rather than assembled from a Button and a compound drawable: the default widget cannot express the
 * press scale, the state-tinted fill and a mic glyph that keeps its optical weight at this size. The brief
 * explicitly rules out shipping the platform default Button on Home.
 *
 * P0-1 only reports presses. No audio is captured here.
 */
class PushToTalkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPressStart: (() -> Unit)? = null
    var onPressEnd: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = CompanionDrawables.dp(context, 1.6f)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = CompanionDimensions.pttTextSp * resources.displayMetrics.scaledDensity
    }

    private val radius = CompanionDrawables.dp(context, CompanionDimensions.pttRadiusDp)
    private val glyphWidth = CompanionDrawables.dp(context, 12f)
    private val glyphGap = CompanionDrawables.dp(context, 7f)

    private var pressed = false
    private var state = ConversationState.IDLE
    private var characterName = ""
    private var labelText: String = ""
    private var pressAnimator: ObjectAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "按住和我说话"
    }

    fun render(state: ConversationState, characterName: String) {
        this.state = state
        this.characterName = characterName
        labelText = state.pushToTalkLabel(characterName)
        contentDescription = labelText
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = CompanionDrawables.dp(context, CompanionDimensions.pttHeightDp).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val accent = state.accentColor()
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        fill.color = when {
            pressed -> accent
            state.isBusy() -> accent
            else -> CompanionColors.companion
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        val textColor = CompanionColors.background
        val glyphColor = textColor

        label.color = textColor
        glyph.color = glyphColor

        // Preserve user font scale; long character names truncate, never shrink text to fit.
        val visibleLabel = TextUtils.ellipsize(labelText, label,
            (width - glyphWidth - glyphGap - CompanionDrawables.dp(context, 16f)).coerceAtLeast(0f),
            TextUtils.TruncateAt.END).toString()
        val textWidth = label.measureText(visibleLabel)
        val total = glyphWidth + glyphGap + textWidth
        val startX = ((width - total) / 2f).coerceAtLeast(0f)
        val centerY = height / 2f

        drawMic(canvas, startX, centerY)
        canvas.drawText(visibleLabel, startX + glyphWidth + glyphGap, centerY - (label.descent() + label.ascent()) / 2f, label)
    }

    /** Small microphone: capsule, cradle arc, stem, base. */
    private fun drawMic(canvas: Canvas, left: Float, centerY: Float) {
        val cx = left + glyphWidth / 2f
        val top = centerY - CompanionDrawables.dp(context, 6.5f)
        val capsuleBottom = centerY + CompanionDrawables.dp(context, 1.5f)
        val capsuleHalf = CompanionDrawables.dp(context, 2.4f)
        val capsule = RectF(cx - capsuleHalf, top, cx + capsuleHalf, capsuleBottom)
        canvas.drawRoundRect(capsule, capsuleHalf, capsuleHalf, glyph)

        val cradle = RectF(cx - CompanionDrawables.dp(context, 5f), top + CompanionDrawables.dp(context, 2f),
            cx + CompanionDrawables.dp(context, 5f), centerY + CompanionDrawables.dp(context, 4f))
        canvas.drawArc(cradle, 0f, 180f, false, glyph)
        canvas.drawLine(cx, centerY + CompanionDrawables.dp(context, 4f), cx, centerY + CompanionDrawables.dp(context, 6.5f), glyph)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                animateScale(CompanionDimensions.pttPressedScale)
                invalidate()
                onPressStart?.invoke()
                return true
            }

            MotionEvent.ACTION_UP -> {
                release()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                release()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun release() {
        if (!pressed) return
        pressed = false
        animateScale(1f)
        invalidate()
        onPressEnd?.invoke()
    }

    private fun animateScale(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ObjectAnimator.ofPropertyValuesHolder(
            this,
            android.animation.PropertyValuesHolder.ofFloat(SCALE_X, target),
            android.animation.PropertyValuesHolder.ofFloat(SCALE_Y, target),
        ).apply {
            duration = 90L
            start()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun blend(foreground: Int, background: Int, foregroundWeight: Float): Int {
        val inverse = 1f - foregroundWeight
        fun channel(shift: Int): Int =
            ((foreground shr shift and 0xFF) * foregroundWeight + (background shr shift and 0xFF) * inverse)
                .toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
