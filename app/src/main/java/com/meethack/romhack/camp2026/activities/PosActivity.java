package com.meethack.romhack.camp2026.activities;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
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
import com.meethack.romhack.camp2026.assets.Money;
import com.meethack.romhack.camp2026.assets.Services;

import java.io.IOException;

/** Service selection + charge to a MIFARE Classic card via NFC. */
public class PosActivity extends NfcActivity {
    private static final String TAG = "PosActivity";

    private View groupSelection;
    private View groupWaiting;
    private View groupResult;
    private TextView textWaitingStatus;
    private TextView textResultTitle;
    private TextView textResultClient;
    private TextView textResultService;
    private TextView textResultBalance;
    private TextView buttonResultPrimary;
    private View buttonConfirm;

    private Services.Service selectedService;
    private TextView selectedRow;
    private boolean lastResultSuccess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pos);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (groupSelection.getVisibility() != View.VISIBLE) {
                    showSelectionState();
                } else {
                    finish();
                }
            }
        });

        groupSelection = findViewById(R.id.groupSelection);
        groupWaiting = findViewById(R.id.groupWaiting);
        groupResult = findViewById(R.id.groupResult);
        textWaitingStatus = findViewById(R.id.textWaitingStatus);
        textResultTitle = findViewById(R.id.textResultTitle);
        textResultClient = findViewById(R.id.textResultClient);
        textResultService = findViewById(R.id.textResultService);
        textResultBalance = findViewById(R.id.textResultBalance);
        buttonResultPrimary = findViewById(R.id.buttonResultPrimary);
        buttonConfirm = findViewById(R.id.buttonConfirm);

        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonBackToSelection).setOnClickListener(v -> showSelectionState());
        buttonConfirm.setOnClickListener(v -> onConfirmClicked());
        buttonResultPrimary.setOnClickListener(v -> onResultPrimaryClicked());

        populateServices();
    }

    @Override
    protected void onTagDiscovered(Tag tag) {
        if (groupWaiting.getVisibility() == View.VISIBLE && selectedService != null) {
            chargeCard(tag);
        }
    }

    private void populateServices() {
        LinearLayout container = findViewById(R.id.servicesContainer);
        for (Services.Service service : Services.CATALOG) {
            TextView row = buildServiceRow(service);
            container.addView(row);
        }
    }

    private TextView buildServiceRow(Services.Service service) {
        TextView row = new TextView(this);
        row.setText(service.name + "\n" + Money.format(service.price));
        row.setTextColor(getColor(R.color.text_primary));
        row.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setLineSpacing(dp(4), 1f);
        row.setBackgroundResource(R.drawable.bg_service_row);
        row.setGravity(Gravity.START);
        int padding = (int) dp(16);
        row.setPadding(padding, padding, padding, padding);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) dp(12);
        row.setLayoutParams(params);

        row.setOnClickListener(v -> selectService(service, row));
        return row;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void selectService(Services.Service service, TextView row) {
        if (selectedRow != null) {
            selectedRow.setBackgroundResource(R.drawable.bg_service_row);
        }
        selectedService = service;
        selectedRow = row;
        row.setBackgroundResource(R.drawable.bg_service_row_selected);
        buttonConfirm.setEnabled(true);
    }

    private void onConfirmClicked() {
        if (selectedService == null) return;
        showWaitingState(getString(R.string.pos_waiting_subtitle), R.color.text_dim);
    }

    private void chargeCard(Tag tag) {
        showWaitingState(getString(R.string.pos_status_reading), R.color.neon_cyan);

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            showWaitingState(getString(R.string.nfc_error_not_mifare), R.color.neon_red);
            return;
        }

        Services.Service service = selectedService;

        startNfcAnimation();
        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
            try {
                int balance = nfcWrapper.readAmount();
                if (balance < service.price) {
                    onNfcResult(() -> {
                        showResult(false, service, Money.format(balance));
                    });
                    return;
                }
                nfcWrapper.buy(service.price);
                int newBalance = balance - service.price;
                onNfcResult(() -> {
                    showResult(true, service, Money.format(newBalance));
                });
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "NFC read/write error", e);
                onNfcResult(() -> {
                    showWaitingState(getString(R.string.nfc_error_io), R.color.neon_red);
                });
            }
            catch (FailedToFetchKeys e){
                Log.e(TAG, "Cannot fetch keys for card", e);
                onNfcResult(() -> {
                    showWaitingState(getString(R.string.nfc_error_fetch_keys), R.color.neon_red);
                });
            }
            catch (InvalidValueBlock e){
                Log.e(TAG, e.toString());
                onNfcResult(() -> {
                    showWaitingState(getString(R.string.pos_error_tampered_block), R.color.neon_red);
                });
            }
        }).start();
    }

    private void showResult(boolean success, Services.Service service, String balanceLine) {
        lastResultSuccess = success;
        groupSelection.setVisibility(View.GONE);
        groupWaiting.setVisibility(View.GONE);
        groupResult.setVisibility(View.VISIBLE);

        textResultClient.setVisibility(View.GONE);
        textResultService.setText(getString(R.string.pos_success_service,
                service.name + " — " + Money.format(service.price)));

        if (success) {
            textResultTitle.setText(R.string.pos_success_title);
            textResultTitle.setTextColor(getColor(R.color.neon_green));
            textResultBalance.setText(getString(R.string.pos_success_new_balance, balanceLine));
            textResultBalance.setTextColor(getColor(R.color.neon_green));
            buttonResultPrimary.setText(R.string.pos_button_new_operation);
        } else {
            textResultTitle.setText(R.string.pos_error_insufficient_funds);
            textResultTitle.setTextColor(getColor(R.color.neon_red));
            textResultBalance.setText(getString(R.string.pos_error_insufficient_funds_detail,
                    balanceLine, service.name));
            textResultBalance.setTextColor(getColor(R.color.neon_red));
            buttonResultPrimary.setText(R.string.pos_button_retry);
        }
    }

    private void onResultPrimaryClicked() {
        if (lastResultSuccess) {
            showSelectionState();
        } else {
            showWaitingState(getString(R.string.pos_waiting_subtitle), R.color.text_dim);
        }
    }

    private void showSelectionState() {
        if (selectedRow != null) {
            selectedRow.setBackgroundResource(R.drawable.bg_service_row);
        }
        selectedService = null;
        selectedRow = null;
        buttonConfirm.setEnabled(false);

        groupSelection.setVisibility(View.VISIBLE);
        groupWaiting.setVisibility(View.GONE);
        groupResult.setVisibility(View.GONE);
    }

    private void showWaitingState(String status, int colorRes) {
        groupSelection.setVisibility(View.GONE);
        groupResult.setVisibility(View.GONE);
        groupWaiting.setVisibility(View.VISIBLE);
        textWaitingStatus.setText(status);
        textWaitingStatus.setTextColor(getColor(colorRes));
    }
}
