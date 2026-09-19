package com.meethack.romhack.camp2026.activities;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;

import com.meethack.romhack.camp2026.R;

/**
 * Base activity for screens that read or write MIFARE Classic tags via NFC foreground
 * dispatch. Owns adapter setup, dispatch enable/disable and tag extraction; subclasses just
 * implement {@link #onTagDiscovered} to react to a tapped tag and {@link #getNfcWavesView}
 * for the feedback animation. Subclasses call {@link #startNfcAnimation()} right before
 * kicking off their (possibly network-bound) read/write operation and {@link #stopNfcAnimation()}
 * in every terminal branch (success or failure), so the waves keep pulsing for the operation's
 * actual duration instead of a fixed, guessed-at one.
 */
public abstract class NfcActivity extends AppCompatActivity {
    private static final long PULSE_DURATION_MS = 650;
    private static final float PULSE_MAX_SCALE = 1.35f;

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private ValueAnimator pulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        Intent nfcIntent = new Intent(this, getClass());
        nfcIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        this.nfcPendingIntent = PendingIntent.getActivity(
                this, 0, nfcIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this.nfcAdapter != null) {
            this.nfcAdapter.enableForegroundDispatch(
                    this,
                    nfcPendingIntent,
                    new IntentFilter[]{new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)},
                    new String[][]{{MifareClassic.class.getName()}}
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (this.nfcAdapter != null) {
            this.nfcAdapter.disableForegroundDispatch(this);
        }
        stopNfcAnimation();
    }

    /**
     * Starts an indefinitely looping pulse (scale + fade) on the waves view, meant to keep
     * running for as long as the app is actively reading/writing the tag. Safe to call again
     * while already running.
     */
    protected void startNfcAnimation() {
        View target = getNfcWavesView();
        if (target == null || pulseAnimator != null) {
            return;
        }
        target.animate().cancel();

        ValueAnimator animator = ValueAnimator.ofFloat(1f, PULSE_MAX_SCALE);
        animator.setDuration(PULSE_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float scale = (float) a.getAnimatedValue();
            float progress = (scale - 1f) / (PULSE_MAX_SCALE - 1f);
            target.setScaleX(scale);
            target.setScaleY(scale);
            target.setAlpha(1f - progress * 0.5f);
        });
        animator.start();
        this.pulseAnimator = animator;
    }

    /**
     * Stops the pulse loop and resets the waves view. Subclasses must call this once their
     * background read/write operation (success or failure) is done, so the animation runs for
     * the full duration of the operation rather than a fixed, guessed-at length.
     */
    protected void stopNfcAnimation() {
        if (this.pulseAnimator != null) {
            this.pulseAnimator.cancel();
            this.pulseAnimator = null;
        }
        View target = getNfcWavesView();
        if (target != null) {
            target.animate().cancel();
            target.setScaleX(1f);
            target.setScaleY(1f);
            target.setAlpha(1f);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Tag tag = extractTag(intent);
        if (tag != null) {
            onTagDiscovered(tag);
        }
    }

    @SuppressWarnings("deprecation")
    private static Tag extractTag(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        }
        return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    }

    /**
     * The waves icon to pulse when a tag is detected. Every screen uses the same
     * {@code R.id.imageNfcWaves} id; override if a screen needs something different
     * (or return null to skip the animation).
     */
    protected View getNfcWavesView() {
        return findViewById(R.id.imageNfcWaves);
    }

    /** Called with the discovered tag; implementations decide whether/how to act on it based on UI state. */
    protected abstract void onTagDiscovered(Tag tag);
}
