package com.meethack.romhack.camp2026;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;

/** Haptic + visual pulse fired the moment an NFC tag intent is received. */
public final class NfcFeedback {

    private NfcFeedback() {
    }

    public static void tagDetected(Context context, View pulseTarget) {
        vibrate(context);
        pulse(pulseTarget);
    }

    private static void vibrate(Context context) {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    public static void pulse(View target) {
        if (target == null) {
            return;
        }
        target.animate().cancel();
        target.setScaleX(1f);
        target.setScaleY(1f);
        target.animate()
                .scaleX(1.18f)
                .scaleY(1.18f)
                .setDuration(120)
                .withEndAction(() -> target.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                .start();
    }
}
