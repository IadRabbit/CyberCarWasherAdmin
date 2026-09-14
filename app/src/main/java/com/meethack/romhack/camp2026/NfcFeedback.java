package com.meethack.romhack.camp2026;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;

public final class NfcFeedback {

    private NfcFeedback() {
    }

    public static void tagDetected(Context context, View pulseTarget) {
        pulse(pulseTarget);
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
                .withEndAction(() -> target.animate().scaleX(1f).scaleY(1f).setDuration(2000).start())
                .start();
    }
}
