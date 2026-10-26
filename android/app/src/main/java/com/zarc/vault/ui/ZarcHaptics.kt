package com.zarc.vault.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

object ZarcHaptics {
    fun click(context: Context? = null, view: View? = null) {
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        context?.let { ctx ->
            vibrate(ctx, durationMs = 15, amplitude = 80)
        }
    }

    fun longPress(context: Context? = null, view: View? = null) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        context?.let { ctx ->
            vibrate(ctx, durationMs = 45, amplitude = 180)
        }
    }

    fun success(context: Context? = null, view: View? = null) {
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        context?.let { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val vibrator = getVibrator(ctx)
                try {
                    vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                } catch (_: Exception) {
                    vibratePattern(ctx, longArrayOf(0, 30, 40, 30))
                }
            } else {
                vibratePattern(ctx, longArrayOf(0, 30, 40, 30))
            }
        }
    }

    fun error(context: Context? = null, view: View? = null) {
        view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        context?.let { ctx ->
            vibratePattern(ctx, longArrayOf(0, 50, 50, 50))
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(context: Context, durationMs: Long, amplitude: Int) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        amplitude.coerceIn(1, 255),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibratePattern(context: Context, timings: LongArray) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }
}
