package com.meethack.romhack.camp2026;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sets an arbitrary balance on a card that's already in circulation. */
public class RechargeActivity extends AppCompatActivity {
    private static final String TAG = "RechargeActivity";

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;

    private View groupForm;
    private View groupWaiting;
    private TextView textWaitingTitle;
    private TextView textWaitingStatus;

    private EditText amountText;
    private int amountToWrite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recharge);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (groupWaiting.getVisibility() == View.VISIBLE) {
                    showFormState();
                } else {
                    finish();
                }
            }
        });

        groupForm = findViewById(R.id.groupForm);
        groupWaiting = findViewById(R.id.groupWaiting);
        textWaitingTitle = findViewById(R.id.textWaitingTitle);
        textWaitingStatus = findViewById(R.id.textWaitingStatus);
        this.amountText = findViewById(R.id.editAmount);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonBackToForm).setOnClickListener(v -> showFormState());
        findViewById(R.id.buttonWrite).setOnClickListener(v -> onWriteClicked());

        // set up NFC dispatch immediately so a tag tapped while still typing the amount
        // is caught by this activity instead of being ignored by the system
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
        NfcFeedback.tagDetected(this, findViewById(R.id.imageNfcWaves));
        Tag tag = extractTag(intent);
        if (tag == null) {
            return;
        }
        if (groupWaiting.getVisibility() != View.VISIBLE) {
            return;
        }
        writeCard(tag);
    }

    @SuppressWarnings("deprecation")
    private static Tag extractTag(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        }
        return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    }

    private void onWriteClicked() {
        Editable amountE = this.amountText.getText();
        int amount;

        if (TextUtils.isEmpty(amountE)) {
            this.amountText.setError(getString(R.string.recharge_error_amount));
            return;
        }

        try {
            amount = Integer.parseInt(amountE.toString());
            if (amount < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            this.amountText.setError(getString(R.string.recharge_error_amount));
            return;
        }

        this.amountToWrite = amount;
        showWaitingState(getString(R.string.recharge_waiting_subtitle), R.color.text_dim);
    }

    private void writeCard(Tag tag) {
        showWaitingState(getString(R.string.recharge_status_writing), R.color.neon_cyan);

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            showWaitingState(getString(R.string.nfc_error_not_mifare), R.color.neon_red);
            return;
        }

        int amount = this.amountToWrite;
        AtomicBoolean error = new AtomicBoolean(false);

        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
            try {
                nfcWrapper.recharge(amount);
            } catch (IOException e) {
                error.set(true);
                Log.i(TAG, String.valueOf(e));
            }
            runOnUiThread(() -> showWaitingState(
                    error.get() ? getString(R.string.nfc_error_writing_customer_data) : getString(R.string.recharge_status_success),
                    error.get() ? R.color.neon_red : R.color.neon_green)
            );
        }).start();
    }

    private void showFormState() {
        groupForm.setVisibility(View.VISIBLE);
        groupWaiting.setVisibility(View.GONE);
    }

    private void showWaitingState(String status, int colorRes) {
        groupForm.setVisibility(View.GONE);
        groupWaiting.setVisibility(View.VISIBLE);
        textWaitingTitle.setText(R.string.recharge_waiting_title);
        textWaitingStatus.setText(status);
        textWaitingStatus.setTextColor(getColor(colorRes));
    }
}
