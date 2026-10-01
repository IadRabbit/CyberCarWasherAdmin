package com.meethack.romhack.camp2026.activities;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
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

import com.meethack.romhack.camp2026.assets.Customer;
import com.meethack.romhack.camp2026.exceptions.FailedToFetchKeys;
import com.meethack.romhack.camp2026.wrappers.NfcWrapper;
import com.meethack.romhack.camp2026.R;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

public class CreateCustomerActivity extends NfcActivity {
    private static final String TAG = "CreateCustomerActivity";
    private static final String timezone = "Europe/Rome";
    private static final SimpleDateFormat viewDateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.ITALY);
    private static final long FORM_WARNING_DURATION_MS = 3000;
    private static final int DEFAULT_AMOUNT = 18;

    private View groupForm;
    private View groupWaiting;
    private TextView textWaitingTitle;
    private TextView textWaitingStatus;
    private TextView textFormWarning;
    private final Runnable hideFormWarning = () -> textFormWarning.setVisibility(View.GONE);

    private EditText nameText;
    private EditText surnameText;
    private Customer customer;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_create_customer);
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
        textFormWarning = findViewById(R.id.textFormWarning);
        ((TextView) findViewById(R.id.textDateValue)).setText(viewDateFormat.format(new Date()));
        this.nameText = findViewById(R.id.editFirstName);
        this.surnameText = findViewById(R.id.editLastName);
        ((TextView) findViewById(R.id.textDefaultBalance)).setText(getString(R.string.create_label_default_balance, DEFAULT_AMOUNT));
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonBackToForm).setOnClickListener(v -> showFormState());
        findViewById(R.id.buttonWrite).setOnClickListener(v -> onWriteClicked());
    }

    @Override
    protected void onTagDiscovered(Tag tag) {
        // form is still showing: user tapped a tag before pressing the write button
        if (groupWaiting.getVisibility() != View.VISIBLE) {
            showFormWarning();
            return;
        }

        writeCard(tag);
    }

    private void onWriteClicked() {
        String name = this.nameText.getText().toString().trim();
        String surname = this.surnameText.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            this.nameText.setError(getString(R.string.create_error_first_name));
            return;
        }
        if (TextUtils.isEmpty(surname)) {
            this.surnameText.setError(getString(R.string.create_error_last_name));
            return;
        }

        Calendar creationDate = Calendar.getInstance(TimeZone.getTimeZone(timezone));
        creationDate.setTime(new Date());
        this.customer = new Customer(name, surname, creationDate, DEFAULT_AMOUNT);

        hideFormWarning();
        showWaitingState(getString(R.string.create_waiting_subtitle), R.color.text_dim);
    }

    private void writeCard(Tag tag) {
        showWaitingState(getString(R.string.create_status_writing), R.color.neon_cyan);

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            showWaitingState(getString(R.string.nfc_error_not_mifare), R.color.neon_red);
            return;
        }

        Customer customerToWrite = this.customer;
        AtomicBoolean error = new AtomicBoolean(false);

        startNfcAnimation();
        new Thread(() -> {
            try {
                NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
                nfcWrapper.saveData(customerToWrite);
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
            onNfcResult(() -> showWaitingState(
                    error.get() ? getString(R.string.nfc_error_writing_customer_data) : getString(R.string.create_status_success),
                    error.get() ? R.color.neon_red : R.color.neon_green));
        }).start();
    }

    private void showFormState() {
        hideFormWarning();
        groupForm.setVisibility(View.VISIBLE);
        groupWaiting.setVisibility(View.GONE);
    }

    private void showFormWarning() {
        textFormWarning.removeCallbacks(hideFormWarning);
        textFormWarning.setVisibility(View.VISIBLE);
        textFormWarning.postDelayed(hideFormWarning, FORM_WARNING_DURATION_MS);
    }

    private void hideFormWarning() {
        textFormWarning.removeCallbacks(hideFormWarning);
        textFormWarning.setVisibility(View.GONE);
    }

    private void showWaitingState(String status, int colorRes) {
        groupForm.setVisibility(View.GONE);
        groupWaiting.setVisibility(View.VISIBLE);
        textWaitingTitle.setText(R.string.create_waiting_title);
        textWaitingStatus.setText(status);
        textWaitingStatus.setTextColor(getColor(colorRes));
    }
}
