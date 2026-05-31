package com.localbridge.watch.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.util.Log

object SoundDebug {
    private const val TAG = "GalaxyBridgeSoundDebug"
    private const val PREFS_NAME = "galaxy_bridge_sound_debug"
    private const val KEY_SELECTED_PRESET = "selected_diy_sound_preset"

    data class ToneOption(
        val key: String,
        val label: String,
        val streamType: Int,
        val toneType: Int = ToneGenerator.TONE_PROP_BEEP,
        val volume: Int = 100,
        val durationMs: Int = 700
    )

    val toneOptions = listOf(
        ToneOption("notification", "tone notification", AudioManager.STREAM_NOTIFICATION),
        ToneOption("alarm", "tone alarm", AudioManager.STREAM_ALARM),
        ToneOption("ring", "tone ring", AudioManager.STREAM_RING),
        ToneOption("music", "tone music", AudioManager.STREAM_MUSIC),
        ToneOption("system", "tone system", AudioManager.STREAM_SYSTEM)
    )

    data class DiyPreset(
        val key: String,
        val label: String,
        val description: String,
        val tones: List<ToneStep>
    )

    data class ToneStep(
        val delayMs: Long,
        val streamType: Int,
        val toneType: Int,
        val durationMs: Int,
        val volume: Int = 100
    )

    val diyPresets = listOf(
        DiyPreset(
            key = "soft",
            label = "DIY Soft",
            description = "1 short alarm beep",
            tones = listOf(ToneStep(0L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_BEEP, 140))
        ),
        DiyPreset(
            key = "double",
            label = "DIY Double",
            description = "2 short alarm beeps",
            tones = listOf(
                ToneStep(0L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_BEEP2, 180),
                ToneStep(260L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_ACK, 160)
            )
        ),
        DiyPreset(
            key = "urgent",
            label = "DIY Urgent",
            description = "3 fast alarm beeps",
            tones = listOf(
                ToneStep(0L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_BEEP2, 130),
                ToneStep(180L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_BEEP2, 130),
                ToneStep(360L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_ACK, 160)
            )
        ),
        DiyPreset(
            key = "ring",
            label = "DIY Ring",
            description = "ring stream double beep",
            tones = listOf(
                ToneStep(0L, AudioManager.STREAM_RING, ToneGenerator.TONE_PROP_BEEP, 220),
                ToneStep(320L, AudioManager.STREAM_RING, ToneGenerator.TONE_PROP_ACK, 180)
            )
        ),
        DiyPreset(
            key = "system",
            label = "DIY System",
            description = "system stream prompt",
            tones = listOf(ToneStep(0L, AudioManager.STREAM_SYSTEM, ToneGenerator.TONE_PROP_PROMPT, 260))
        ),
        DiyPreset(
            key = "alarm_long",
            label = "DIY Alarm Long",
            description = "strong longer alarm beep",
            tones = listOf(ToneStep(0L, AudioManager.STREAM_ALARM, ToneGenerator.TONE_SUP_ERROR, 450))
        )
    )

    fun playNotificationSound(context: Context, label: String): Boolean {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val notificationVolume = audioManager?.getStreamVolume(AudioManager.STREAM_NOTIFICATION) ?: -1
        val ringVolume = audioManager?.getStreamVolume(AudioManager.STREAM_RING) ?: -1
        val mode = audioManager?.ringerMode ?: -1

        return runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) {
                Log.w(TAG, "SOUND_SKIPPED label=$label reason=no_default_uri volumeNotification=$notificationVolume volumeRing=$ringVolume ringerMode=$mode")
                return false
            }

            val ringtone = RingtoneManager.getRingtone(appContext, uri)
            if (ringtone == null) {
                Log.w(TAG, "SOUND_SKIPPED label=$label reason=ringtone_null uri=$uri volumeNotification=$notificationVolume volumeRing=$ringVolume ringerMode=$mode")
                return false
            }

            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            Log.i(TAG, "SOUND_PLAY_SENT label=$label uri=$uri volumeNotification=$notificationVolume volumeRing=$ringVolume ringerMode=$mode")
            true
        }.getOrElse { error ->
            Log.w(TAG, "SOUND_PLAY_FAILED label=$label error=${error.message} volumeNotification=$notificationVolume volumeRing=$ringVolume ringerMode=$mode")
            false
        }
    }

    fun playTone(context: Context, option: ToneOption): Boolean {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val volumeInfo = volumeInfo(audioManager)

        return runCatching {
            val toneGenerator = ToneGenerator(option.streamType, option.volume)
            val sent = toneGenerator.startTone(option.toneType, option.durationMs)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { toneGenerator.release() }
            }, option.durationMs + 250L)
            Log.i(TAG, "TONE_PLAY_SENT key=${option.key} label=${option.label} stream=${option.streamType} sent=$sent $volumeInfo")
            sent
        }.getOrElse { error ->
            Log.w(TAG, "TONE_PLAY_FAILED key=${option.key} stream=${option.streamType} error=${error.message} $volumeInfo")
            false
        }
    }

    fun playBestEffortAlert(context: Context, label: String): Boolean {
        // On Wear OS, RingtoneManager often returns true but produces no sound.
        // ToneGenerator is more reliable — generate beeps directly.
        // Respect ringer mode: silent → no sound, vibrate → vibration only (vibrate handled separately)
        val appContext = context.applicationContext
        val am = appContext.getSystemService(AudioManager::class.java)
        val ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.i(TAG, "BEST_EFFORT_SILENT skipped=$label reason=silent_mode")
            return false
        }
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.i(TAG, "BEST_EFFORT_VIBRATE skipped=$label reason=vibrate_mode (vibration still active)")
            return false
        }
        am?.let {
            it.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_RAISE, 0)
            it.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_RAISE, 0)
            it.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_RAISE, 0)
        }
        val toneSent = playTone(context, ToneOption("wear_alarm", "$label alarm", AudioManager.STREAM_ALARM, ToneGenerator.TONE_PROP_BEEP2, 100, 600))
        Log.i(TAG, "BEST_EFFORT_ALERT label=$label tone=$toneSent ringerMode=$ringerMode")
        return toneSent
    }

    fun playDiyAlert(context: Context, label: String): Boolean {
        return playSelectedDiyAlert(context, label)
    }

    fun playSelectedDiyAlert(context: Context, label: String): Boolean {
        val preset = selectedPreset(context)
        return playDiyPreset(context, preset, label)
    }

    fun playDiyPreset(context: Context, preset: DiyPreset, label: String): Boolean {
        var firstSent = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        preset.tones.forEachIndexed { index, step ->
            val playStep = {
                val sent = playTone(
                    context,
                    ToneOption(
                        key = "${preset.key}_$index",
                        label = "$label ${preset.label} step=$index",
                        streamType = step.streamType,
                        toneType = step.toneType,
                        volume = step.volume,
                        durationMs = step.durationMs
                    )
                )
                if (index == 0) firstSent = sent
            }
            if (step.delayMs == 0L) playStep() else handler.postDelayed(playStep, step.delayMs)
        }
        Log.i(TAG, "DIY_PRESET_SENT label=$label preset=${preset.key} first=$firstSent")
        return firstSent
    }

    fun selectPreset(context: Context, preset: DiyPreset) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_PRESET, preset.key)
            .apply()
        Log.i(TAG, "DIY_PRESET_SELECTED key=${preset.key} label=${preset.label}")
    }

    fun selectedPreset(context: Context): DiyPreset {
        val key = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PRESET, "double")
        return diyPresets.firstOrNull { it.key == key } ?: diyPresets.first { it.key == "double" }
    }

    private fun volumeInfo(audioManager: AudioManager?): String {
        if (audioManager == null) return "audioManager=null"
        return "volNotification=${audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)} " +
            "volAlarm=${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)} " +
            "volRing=${audioManager.getStreamVolume(AudioManager.STREAM_RING)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)} " +
            "volMusic=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
            "volSystem=${audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)} " +
            "ringerMode=${audioManager.ringerMode} mode=${audioManager.mode}"
    }
}