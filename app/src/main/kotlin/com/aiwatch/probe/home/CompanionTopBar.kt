package com.aiwatch.probe.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import com.aiwatch.probe.R
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.accentColor
import com.aiwatch.probe.conversation.statusLabel
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionDrawables

/**
 * Home header: who the companion is on the left, settings on the right.
 *
 * Nothing technical belongs here. Connection state, endpoints, debug toggles and avatar-engine status live
 * in Settings, because this row is the first thing the user sees and must read as a character, not a probe.
 */
class CompanionTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onSettingsClick: (() -> Unit)? = null

    private val name = TextView(context).apply {
        setTextColor(CompanionColors.primaryText)
        textSize = 13f
        letterSpacing = 0.04f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val statusDot = View(context)

    private val status = TextView(context).apply {
        setTextColor(CompanionColors.secondaryText)
        textSize = 10f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val settings = ImageButton(context).apply {
        setImageResource(R.drawable.ic_material_settings)
        imageTintList = ColorStateList.valueOf(CompanionColors.secondaryText)
        background = RippleDrawable(ColorStateList.valueOf(CompanionColors.hairline), null,
            CompanionDrawables.rounded(context, android.graphics.Color.WHITE, 24f))
        val inset = CompanionDrawables.dp(context, 14f).toInt()
        setPadding(inset, inset, inset, inset)
        contentDescription = "设置"
        setOnClickListener { onSettingsClick?.invoke() }
    }

    init {
        val margin = CompanionDrawables.dp(context, CompanionDimensions.edgeMarginDp.toFloat()).toInt()
        val dotSize = CompanionDrawables.dp(context, 6f).toInt()

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = CompanionDrawables.dp(context, 6f).toInt()
            })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(statusRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = CompanionDrawables.dp(context, 3f).toInt() })
        }

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = margin
            rightMargin = CompanionDrawables.dp(context, 50f).toInt()
        })

        val buttonSize = CompanionDrawables.dp(context, 48f).toInt()
        addView(settings, LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = margin - CompanionDrawables.dp(context, 8f).toInt()
        })
    }

    fun render(characterName: String, state: ConversationState) {
        name.text = characterName
        status.text = state.statusLabel(characterName)
        val accent = state.accentColor()
        status.setTextColor(accent)
        statusDot.background = CompanionDrawables.rounded(context, accent, 4f).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
    }

}
