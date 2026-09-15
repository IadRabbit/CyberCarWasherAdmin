package com.meethack.romhack.camp2026;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONException;

import java.io.IOException;

public class CardFormatActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;

    private TextView textWaitingTitle;
    private TextView textWaitingStatus;
    private ImageView imageNfcWaves;

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
        imageNfcWaves = findViewById(R.id.imageNfcWaves);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());

        // set up NFC dispatch immediately so the card can be tapped as soon as the screen opens
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
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        NfcFeedback.tagDetected(this, imageNfcWaves);
        Tag tag = extractTag(intent);
        if (tag == null) {
            return;
        }
        formatCard(tag);
    }

    @SuppressWarnings("deprecation")
    private static Tag extractTag(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        }
        return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    }

    private byte[] fetchKeys() {
        return new byte[]{
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };
    }

    private void formatCard(Tag tag) {
        showStatus(getString(R.string.format_status_formatting), R.color.neon_cyan);

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            showStatus(getString(R.string.nfc_error_not_mifare), R.color.neon_red);
            return;
        }

        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
            try {
                nfcWrapper.format();
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> showStatus(getString(R.string.nfc_error_io), R.color.neon_red));
                return;
            }
            runOnUiThread(() -> showStatus(getString(R.string.format_status_success), R.color.neon_green));
        }).start();
    }

    private void showStatus(String status, int colorRes) {
        textWaitingTitle.setText(R.string.format_waiting_title);
        textWaitingStatus.setText(status);
        textWaitingStatus.setTextColor(getColor(colorRes));
    }
}
