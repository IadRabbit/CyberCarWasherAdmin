package com.meethack.romhack.camp2026.activities;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.meethack.romhack.camp2026.exceptions.FailedToFetchKeys;
import com.meethack.romhack.camp2026.exceptions.InvalidValueBlock;
import com.meethack.romhack.camp2026.wrappers.NfcWrapper;
import com.meethack.romhack.camp2026.R;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sets an arbitrary balance on a card that's already in circulation. */
public class RechargeActivity extends NfcActivity {
    private static final String TAG = "RechargeActivity";

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
    }

    @Override
    protected void onTagDiscovered(Tag tag) {
        if (groupWaiting.getVisibility() != View.VISIBLE) {
            return;
        }
        writeCard(tag);
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

        startNfcAnimation();
        new Thread(() -> {
            try {
                NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
                nfcWrapper.recharge(amount);
            } catch (SecurityException e) {
                Log.e(TAG, "Tag went out of date while writing", e);
                onNfcResult(() -> showWaitingState(getString(R.string.nfc_error_tag_moved), R.color.neon_red));
                return;
            }
            catch (IOException e) {
                error.set(true);
                Log.i(TAG, String.valueOf(e));
            }
            catch (FailedToFetchKeys e){
                Log.e(TAG, "Cannot fetch keys for card", e);
                onNfcResult(() -> showWaitingState(getString(R.string.nfc_error_fetch_keys), R.color.neon_red));
                return;
            }
            catch (InvalidValueBlock e){
                Log.e(TAG, e.toString());
                onNfcResult(() -> showWaitingState(getString(R.string.pos_error_tampered_block), R.color.neon_red));
                return;
            }
            onNfcResult(() -> showWaitingState(
                    error.get() ? getString(R.string.nfc_error_writing_customer_data) : getString(R.string.recharge_status_success),
                    error.get() ? R.color.neon_red : R.color.neon_green));
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
