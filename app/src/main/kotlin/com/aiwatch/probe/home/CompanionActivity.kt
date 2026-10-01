package com.aiwatch.probe.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Window
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.character.AvatarProviders
import com.aiwatch.probe.character.CharacterProfile
import com.aiwatch.probe.conversation.ConversationController
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.MessageItem
import com.aiwatch.probe.product.SettingsActivity
import com.aiwatch.probe.voice.XiaozhiVoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * P0-2A launcher: the wrist companion's Home, now driven by the real Xiaozhi voice session.
 *
 * Thin by design. It owns the profile, the [XiaozhiVoiceSession], and the wiring between that session and
 * [CompanionHomeView]. No conversation logic lives here:
 *
 *  - the four visible states come from the coordinator's conversation machine (`uiState`),
 *  - the transcript comes from STT and TTS text,
 *  - push-to-talk maps to begin/end capture,
 *  - barge-in is the coordinator's own interrupt ordering, not a rule re-implemented here.
 *
 * The scripted `ConversationController` remains reachable through [EXTRA_SCRIPTED_CONVERSATION] so the
 * P0-1 shell test stays deterministic and an endpoint-less build can still demonstrate the UI. The
 * default path is the real session.
 */
class CompanionActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var home: CompanionHomeView
    private val profile: CharacterProfile = CharacterProfile.developmentDefault()

    /** Production path. */
    private var voice: XiaozhiVoiceSession? = null

    /** Test double / no-endpoint demo, selected by [EXTRA_SCRIPTED_CONVERSATION]. */
    private var scripted: ConversationController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        // Legacy Daylight compact-fixture heuristic, NOT watch/model detection or adaptive certification.
        // PR B replaces this configuration-time rule with available-window/inset policy. Keep the
        // existing compact visual baseline unchanged during the main-contract sync; edge swipe restores controls.
        if (resources.configuration.screenHeightDp <= 300) {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }

        home = CompanionHomeView(this)
        setContentView(home)
        home.bind(profile, AvatarProviders.forSource(profile.avatar))
        home.onSettingsClick = {
            startActivity(Intent(this@CompanionActivity, SettingsActivity::class.java))
        }

        if (intent.getBooleanExtra(EXTRA_SCRIPTED_CONVERSATION, false)) {
            startScriptedConversation()
        } else {
            startVoiceSession()
        }
    }

    private fun startVoiceSession() {
        val app = application as ProbeApplication
        // Empty until the customer deploys a reachable HTTPS bootstrap. The session logs and stays idle
        // rather than pretending to be connected; nothing is faked.
        val endpoint = runCatching { app.productStore.server().endpoint }.getOrDefault("")
        val session = XiaozhiVoiceSession(app, endpoint)
        voice = session

        home.onPushToTalkStart = { session.onCaptureStarted() }
        home.onPushToTalkEnd = { session.onCaptureReleased() }

        scope.launch { session.uiState.collect { home.renderState(it) } }
        scope.launch { session.transcript.collect { home.renderMessages(it) } }

        session.connect()
    }

    private fun startScriptedConversation() {
        val controller = ConversationController(object : ConversationController.Listener {
            override fun onMessagesChanged(messages: List<MessageItem>) = home.renderMessages(messages)

            override fun onStateChanged(state: ConversationState) = home.renderState(state)
        })
        scripted = controller
        home.onPushToTalkStart = { controller.onPushToTalkPressed() }
        home.onPushToTalkEnd = { controller.onPushToTalkReleased() }
        controller.seedGreeting()
    }

    override fun onPause() {
        // Never record in the background. The real session stays connected; only capture stops.
        voice?.stopTalking()
        scripted?.cancel()
        home.detach()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        voice?.close()
        voice = null
        super.onDestroy()
    }

    /** For the P0-1 instrumentation assertions; reports whichever path is active. */
    val conversationState: ConversationState
        get() = scripted?.state ?: voice?.uiState?.value ?: ConversationState.IDLE

    val conversationMessages: List<MessageItem>
        get() = scripted?.messages ?: voice?.transcript?.value ?: emptyList()

    companion object {
        /**
         * Runs the scripted conversation instead of the real session. The P0-1 shell test uses it so
         * layout and state rendering can be asserted deterministically; it is also the only way to demo
         * Home before a bootstrap endpoint exists.
         */
        const val EXTRA_SCRIPTED_CONVERSATION = "com.aiwatch.probe.SCRIPTED_CONVERSATION"
    }
}
