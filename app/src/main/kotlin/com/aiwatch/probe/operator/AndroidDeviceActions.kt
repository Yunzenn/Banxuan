package com.aiwatch.probe.operator

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock

/** Reference: stixez/droid-mcp@aeaa5b9 (Apache-2.0) SetVolumeTool/LaunchAppTool/CreateTimerTool.
 * Uses platform APIs directly, not the MCP runtime. Changed semantics: media-only readback,
 * no clamping, foreground Activity only, and explicit clock UI handoff rather than silent creation.
 * No upstream source copied; see REUSE_AUDIT.md.
 */
class AndroidDeviceActions(private val activity: Activity) : DeviceActionPort {
    private val audio get() = activity.getSystemService(AudioManager::class.java)
    override fun mediaRange(): IntRange? = audio?.let {
        if (it.isVolumeFixed) null else it.getStreamMinVolume(AudioManager.STREAM_MUSIC)..it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
    override fun setMediaVolume(level: Int): Int {
        val manager = checkNotNull(audio)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    fun currentVolume(): Int = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    override fun canLaunch(packageName: String): Boolean =
        activity.packageManager.getLaunchIntentForPackage(packageName) != null
    override fun launch(packageName: String) {
        activity.startActivity(checkNotNull(activity.packageManager.getLaunchIntentForPackage(packageName)))
    }
    internal fun timerIntent(seconds: Int) = Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
    override fun canSetTimer(): Boolean = timerIntent(60).resolveActivity(activity.packageManager) != null
    override fun openTimer(seconds: Int) { activity.startActivity(timerIntent(seconds)) }
}
