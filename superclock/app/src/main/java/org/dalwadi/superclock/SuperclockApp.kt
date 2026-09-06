package org.dalwadi.superclock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.core.content.getSystemService

class SuperclockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService<NotificationManager>() ?: return

        // Silent and minimal: this one just keeps the mic service alive.
        val voice = NotificationChannel(
            CHANNEL_VOICE,
            getString(R.string.channel_voice_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_voice_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        // The ringing alarm's own audio is played by AlarmService, so the channel
        // itself stays silent — otherwise the notification would double up on it.
        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_alarm_desc)
            setShowBadge(false)
            setSound(null, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        nm.createNotificationChannels(listOf(voice, alarm))
    }

    companion object {
        const val CHANNEL_VOICE = "superclock.voice"
        const val CHANNEL_ALARM = "superclock.alarm"

        const val NOTIF_ID_VOICE = 1001
        const val NOTIF_ID_ALARM = 1002
    }
}
