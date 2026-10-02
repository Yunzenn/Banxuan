package com.aiwatch.probe.home

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import com.aiwatch.probe.character.AvatarProvider
import com.aiwatch.probe.character.AvatarStageView
import com.aiwatch.probe.character.CharacterProfile
import com.aiwatch.probe.conversation.ConversationListView
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.MessageItem
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionLayoutSpec
import com.aiwatch.probe.theme.CompanionDrawables
import com.aiwatch.probe.voice.PushToTalkView

/**
 * The P0-1 Home surface.
 *
 * Daylight Home reserves a readable transcript tail and 48dp controls, then gives the remaining
 * height to the illustration. System bars remain visible on phone-sized windows.
 *
 * The view owns no conversation logic; it renders what it is handed and forwards presses.
 */
class CompanionHomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onPushToTalkStart: (() -> Unit)? = null
    var onPushToTalkEnd: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null

    private val topBar = CompanionTopBar(context)
    private val stage = AvatarStageView(context)
    private val transcript = ConversationListView(context)
    private val pushToTalk = PushToTalkView(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dotFrame = 0
    private var state = ConversationState.IDLE
    private var messages: List<MessageItem> = emptyList()
    private var characterName = ""

    private val dotTicker = object : Runnable {
        override fun run() {
            dotFrame = (dotFrame + 1) % 3
            transcript.render(messages, dotFrame)
            mainHandler.postDelayed(this, DOT_PERIOD_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(CompanionColors.background)

        val margin = CompanionDrawables.dp(context, CompanionDimensions.edgeMarginDp.toFloat()).toInt()

        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT,
            CompanionDrawables.dp(context, CompanionDimensions.topBarHeightDp.toFloat()).toInt()))

        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            leftMargin = margin
            rightMargin = margin
        })

        addView(transcript, LayoutParams(LayoutParams.MATCH_PARENT,
            CompanionDrawables.dp(context, 48f).toInt()).apply {
            topMargin = CompanionDrawables.dp(context, 4f).toInt()
            leftMargin = margin
            rightMargin = margin
        })

        addView(pushToTalk, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT).apply {
            leftMargin = margin
            rightMargin = margin
            topMargin = CompanionDrawables.dp(context, 4f).toInt()
            bottomMargin = CompanionDrawables.dp(context, 6f).toInt()
        })

        topBar.onSettingsClick = { onSettingsClick?.invoke() }
        pushToTalk.onPressStart = { onPushToTalkStart?.invoke() }
        pushToTalk.onPressEnd = { onPushToTalkEnd?.invoke() }
    }

    fun bind(profile: CharacterProfile, avatarProvider: AvatarProvider) {
        characterName = profile.displayName
        stage.bind(avatarProvider, profile.displayName)
        topBar.render(profile.displayName, state)
        pushToTalk.render(state, profile.displayName)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        val spec = CompanionLayoutSpec.forWindow((availableWidth / density).toInt(), (availableHeight / density).toInt())
        fun px(dp: Int) = (dp * density).toInt()
        val contentWidth = px(spec.contentWidthDp)
        (topBar.layoutParams as LayoutParams).apply {
            width = contentWidth
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for (child in listOf(stage, transcript)) {
            (child.layoutParams as LayoutParams).apply {
                width = (contentWidth - px(spec.edgeDp * 2)).coerceAtLeast(0)
                leftMargin = 0
                rightMargin = 0
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        (transcript.layoutParams as LayoutParams).height = px(spec.transcriptHeightDp)
        (pushToTalk.layoutParams as LayoutParams).apply {
            width = px(spec.pttWidthDp)
            leftMargin = 0
            rightMargin = 0
            gravity = Gravity.CENTER_HORIZONTAL
        }
        transcript.setMessageLimit(spec.messageCount)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun renderState(next: ConversationState) {
        state = next
        stage.render(next)
        topBar.render(characterName, next)
        pushToTalk.render(next, characterName)
        if (next == ConversationState.THINKING) {
            mainHandler.removeCallbacks(dotTicker)
            dotFrame = 0
            mainHandler.post(dotTicker)
        } else {
            mainHandler.removeCallbacks(dotTicker)
            transcript.render(messages)
        }
    }

    fun renderMessages(next: List<MessageItem>) {
        messages = next
        transcript.render(next, if (state == ConversationState.THINKING) dotFrame else -1)
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val DOT_PERIOD_MS = 300L
    }
}
