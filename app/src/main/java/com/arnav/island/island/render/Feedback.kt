package com.arnav.island.island.render

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Island haptics built from vibration primitives (Android 11+). Each cue is paired with a
 * motion: a spin as the island opens, a thud as it closes, light ticks for arrivals and drag
 * notches. Devices without primitive support get the closest predefined effect.
 */
class IslandHaptics(context: Context, private val enabled: () -> Boolean) {

    enum class Cue { PRESS, EXPAND, COLLAPSE, ARRIVAL, NOTCH, TICK, CONFIRM }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val audio = context.getSystemService(AudioManager::class.java)
    private val effects = EnumMap<Cue, VibrationEffect>(Cue::class.java)

    private fun supported(vararg primitives: Int): Boolean =
        vibrator.hasVibrator() && vibrator.arePrimitivesSupported(*primitives).all { it }

    private fun build(cue: Cue): VibrationEffect? {
        fun compose(block: VibrationEffect.Composition.() -> Unit) = VibrationEffect.startComposition().apply(block).compose()
        val s31 = Build.VERSION.SDK_INT >= 31
        return when (cue) {
            Cue.EXPAND -> when {
                s31 && supported(VibrationEffect.Composition.PRIMITIVE_SPIN) -> compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.55f) }
                supported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_CLICK) -> compose {
                    addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.45f)
                    addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 30)
                }
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            Cue.COLLAPSE -> when {
                s31 && supported(VibrationEffect.Composition.PRIMITIVE_THUD) -> compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.5f) }
                supported(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL) -> compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f) }
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            }
            Cue.ARRIVAL -> when {
                supported(VibrationEffect.Composition.PRIMITIVE_TICK) -> compose {
                    addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                    addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 70)
                }
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            Cue.NOTCH, Cue.PRESS -> when {
                s31 && supported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) -> compose {
                    addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, if (cue == Cue.NOTCH) 0.9f else 0.5f)
                }
                supported(VibrationEffect.Composition.PRIMITIVE_TICK) -> compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f) }
                cue == Cue.NOTCH -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                else -> null
            }
            Cue.TICK -> when {
                supported(VibrationEffect.Composition.PRIMITIVE_TICK) -> compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f) }
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            Cue.CONFIRM -> when {
                supported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f) }
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            }
        }
    }

    /** Plays [cue]. [touch] cues follow the touch-feedback setting; others respect silent mode. */
    fun play(cue: Cue, touch: Boolean = true) {
        if (!enabled() || !vibrator.hasVibrator()) return
        if (!touch && audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val effect = effects.getOrPut(cue) { build(cue) ?: return }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val usage = if (touch) VibrationAttributes.USAGE_TOUCH else VibrationAttributes.USAGE_NOTIFICATION
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                vibrator.vibrate(effect)
            }
        } catch (e: RuntimeException) {
            Log.w("IslandHaptics", "Vibration failed", e)
        }
    }
}

/**
 * Optional, very quiet UI sounds synthesised at runtime (no audio assets). Off by default and
 * silent unless the ringer is in normal mode.
 */
class IslandSounds(context: Context, private val enabled: () -> Boolean) {

    enum class Sound { EXPAND, COLLAPSE, ARRIVAL }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val tracks = EnumMap<Sound, AudioTrack>(Sound::class.java)

    fun play(sound: Sound) {
        if (!enabled() || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val track = tracks.getOrPut(sound) { create(sound) }
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: RuntimeException) {
            Log.w("IslandSounds", "Sound failed", e)
        }
    }

    fun release() {
        tracks.values.forEach { it.release() }
        tracks.clear()
    }

    private fun create(sound: Sound): AudioTrack {
        val pcm = synthesize(sound)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    /** Soft sine "pops" with exponential decay: rising for expand, falling for collapse. */
    private fun synthesize(sound: Sound): ShortArray {
        val durationS = when (sound) {
            Sound.EXPAND -> 0.11f
            Sound.COLLAPSE -> 0.09f
            Sound.ARRIVAL -> 0.16f
        }
        val n = (RATE * durationS).toInt()
        val out = ShortArray(n)
        var phaseA = 0.0
        var phaseB = 0.0
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            val k = t / durationS
            val (fa, fb, decay) = when (sound) {
                Sound.EXPAND -> Triple(520f + 300f * k, 780f + 450f * k, 26f)
                Sound.COLLAPSE -> Triple(700f - 240f * k, 1050f - 360f * k, 32f)
                Sound.ARRIVAL -> Triple(1760f, 2640f, 21f)
            }
            phaseA += 2.0 * PI * fa / RATE
            phaseB += 2.0 * PI * fb / RATE
            val attack = min(1f, t / 0.004f)
            val env = attack * exp(-decay * t)
            val sample = (sin(phaseA) * 0.7 + sin(phaseB) * 0.3) * env * VOLUME
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private companion object {
        const val RATE = 44_100
        const val VOLUME = 0.22
    }
}
