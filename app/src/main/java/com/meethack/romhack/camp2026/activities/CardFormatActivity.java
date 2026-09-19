package com.meethack.romhack.camp2026.activities;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.meethack.romhack.camp2026.exceptions.FailedToFetchKeys;
import com.meethack.romhack.camp2026.wrappers.NfcWrapper;
import com.meethack.romhack.camp2026.R;

import java.io.IOException;

public class CardFormatActivity extends NfcActivity {
    private static final String TAG = "CardFormatActivity";

    private TextView textWaitingTitle;
    private TextView textWaitingStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_card_format);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        textWaitingTitle = findViewById(R.id.textWaitingTitle);
        textWaitingStatus = findViewById(R.id.textWaitingStatus);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
    }

    @Override
    protected void onTagDiscovered(Tag tag) {
        formatCard(tag);
    }

    private void formatCard(Tag tag) {
        showStatus(getString(R.string.format_status_formatting), R.color.neon_cyan);

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            showStatus(getString(R.string.nfc_error_not_mifare), R.color.neon_red);
            return;
        }

        startNfcAnimation();
        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
            try {
                nfcWrapper.format();
            } catch (IOException | SecurityException e) {
                onNfcResult(() -> {
                    showStatus(getString(R.string.nfc_error_io), R.color.neon_red);
                });
                return;
            } catch (FailedToFetchKeys e) {
                Log.e(TAG, "Cannot fetch keys for card", e);
                onNfcResult(() -> {
                    showStatus(getString(R.string.nfc_error_fetch_keys), R.color.neon_red);
                });
                return;
            }
            onNfcResult(() -> {
                showStatus(getString(R.string.format_status_success), R.color.neon_green);
            });
        }).start();
    }

    private void showStatus(String status, int colorRes) {
        textWaitingTitle.setText(R.string.format_waiting_title);
        textWaitingStatus.setText(status);
        textWaitingStatus.setTextColor(getColor(colorRes));
    }
}
