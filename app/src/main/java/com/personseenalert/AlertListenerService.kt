package com.personseenalert

import android.app.Notification
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

class AlertListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "AlertListenerService"
        private const val PREFS_NAME = "settings"
        private const val COOLDOWN_MS = 5000L
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var currentMediaPlayer: MediaPlayer? = null
    private var savedAlarmVolume: Int = -1
    private var volumeWasModified: Boolean = false
    private val recentAlerts = ConcurrentHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        prefs // Force lazy init to warm the SharedPreferences cache
        recoverOrphanedVolume()
    }

    override fun onListenerDisconnected() {
        synchronized(this) {
            releasePlayer()
            val audioManager = getSystemService(AudioManager::class.java) ?: return
            restoreAlarmVolume(audioManager)
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        synchronized(this) {
            releasePlayer()
            val audioManager = getSystemService(AudioManager::class.java)
            if (audioManager != null) restoreAlarmVolume(audioManager)
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val combined = "$title $text $bigText"

            val now = System.currentTimeMillis()

            // Clean stale cooldown entries unconditionally
            recentAlerts.entries.removeIf { now - it.value > COOLDOWN_MS * 2 }

            var soundFired = false
            var vibrateFired = false
            for (rule in AlertRule.PREDEFINED) {
                if (sbn.packageName != rule.packageName) continue
                if (!rule.keyword.containsMatchIn(combined)) continue
                if (!prefs.getBoolean("rule_${rule.id}_enabled", false)) continue

                // Per-rule cooldown
                val lastAlert = recentAlerts["rule_${rule.id}"]
                if (lastAlert != null && now - lastAlert < COOLDOWN_MS) continue

                val scheduleOk = !prefs.getBoolean("rule_${rule.id}_schedule_enabled", false)
                        || isWithinSchedule(rule.id)

                if (scheduleOk) {
                    if (!soundFired && prefs.getBoolean("rule_${rule.id}_sound", false)) {
                        playForcedAlertSound()
                        soundFired = true
                    }
                    if (!vibrateFired && prefs.getBoolean("rule_${rule.id}_vibrate", false)) {
                        vibrate()
                        vibrateFired = true
                    }
                    recentAlerts["rule_${rule.id}"] = now
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing notification", e)
        }
    }

    private fun isWithinSchedule(ruleId: String): Boolean {
        return try {
            val startHour = prefs.getInt("rule_${ruleId}_schedule_start_hour", 22).coerceIn(0, 23)
            val startMinute = prefs.getInt("rule_${ruleId}_schedule_start_minute", 0).coerceIn(0, 59)
            val endHour = prefs.getInt("rule_${ruleId}_schedule_end_hour", 7).coerceIn(0, 23)
            val endMinute = prefs.getInt("rule_${ruleId}_schedule_end_minute", 0).coerceIn(0, 59)

            val now = LocalTime.now()
            val start = LocalTime.of(startHour, startMinute)
            val end = LocalTime.of(endHour, endMinute)

            if (start <= end) {
                now >= start && now < end
            } else {
                now >= start || now < end
            }
        } catch (e: Exception) {
            Log.w(TAG, "Schedule check failed, allowing alert", e)
            true
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java) ?: return
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator) ?: return
            }
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
            val effect = if (vibrator.hasAmplitudeControl()) {
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                VibrationEffect.createWaveform(pattern, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(pattern, -1)
            }
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    // Must be called while holding the intrinsic lock (synchronized on this)
    private fun playForcedAlertSound() {
        synchronized(this) {
            val audioManager = getSystemService(AudioManager::class.java)
            if (audioManager == null) {
                Log.w(TAG, "AudioManager unavailable")
                return
            }

            try {
                releasePlayer()

                if (savedAlarmVolume == -1) {
                    savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    volumeWasModified = false
                }

                if (savedAlarmVolume == 0) {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        (maxVolume * 0.6).toInt().coerceAtLeast(1),
                        0
                    )
                    volumeWasModified = true
                }

                if (volumeWasModified) {
                    persistSavedVolume(savedAlarmVolume)
                }

                val soundUri = RingtoneManager.getActualDefaultRingtoneUri(
                    this, RingtoneManager.TYPE_NOTIFICATION
                ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: run { restoreAlarmVolume(audioManager); return }

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlertListenerService, soundUri)
                    setOnPreparedListener { mp -> mp.start() }
                    setOnCompletionListener { mp ->
                        synchronized(this@AlertListenerService) {
                            mp.release()
                            if (currentMediaPlayer === mp) {
                                currentMediaPlayer = null
                                restoreAlarmVolume(audioManager)
                            }
                        }
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        synchronized(this@AlertListenerService) {
                            mp.release()
                            if (currentMediaPlayer === mp) {
                                currentMediaPlayer = null
                                restoreAlarmVolume(audioManager)
                            }
                        }
                        true
                    }
                    prepareAsync()
                }
                currentMediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play alert sound", e)
                releasePlayer()
                restoreAlarmVolume(audioManager)
            }
        }
    }

    // Caller must hold the intrinsic lock (synchronized on this)
    private fun releasePlayer() {
        currentMediaPlayer?.let {
            it.setOnCompletionListener(null)
            it.setOnPreparedListener(null)
            it.setOnErrorListener(null)
            it.release()
        }
        currentMediaPlayer = null
    }

    private fun restoreAlarmVolume(audioManager: AudioManager) {
        val vol = savedAlarmVolume
        if (vol != -1) {
            savedAlarmVolume = -1
            if (volumeWasModified) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
                volumeWasModified = false
            }
            clearPersistedVolume()
        }
    }

    private fun persistSavedVolume(volume: Int) {
        prefs.edit().putInt("pending_alarm_volume_restore", volume).apply()
    }

    private fun clearPersistedVolume() {
        prefs.edit().remove("pending_alarm_volume_restore").apply()
    }

    private fun recoverOrphanedVolume() {
        val staleVolume = prefs.getInt("pending_alarm_volume_restore", -1)
        if (staleVolume != -1) {
            val audioManager = getSystemService(AudioManager::class.java)
            if (audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, staleVolume, 0)
                Log.i(TAG, "Recovered orphaned alarm volume: $staleVolume")
            }
            clearPersistedVolume()
        }
    }
}
