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
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class CreateCustomerActivity extends AppCompatActivity {
    private static final String TAG = "CreateCustomerActivity";
    private static final String timezone = "Europe/Rome";
    private static final SimpleDateFormat viewDateFormat = new SimpleDateFormat("dd/MM/yyyy");
    private static final long FORM_WARNING_DURATION_MS = 3000;

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;

    private View groupForm;
    private View groupWaiting;
    private TextView textWaitingTitle;
    private TextView textWaitingStatus;
    private TextView textFormWarning;
    private ImageView imageNfcWaves;
    private final Runnable hideFormWarning = () -> textFormWarning.setVisibility(View.GONE);

    private EditText nameText;
    private EditText surnameText;
    private EditText amountText;
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
        imageNfcWaves = findViewById(R.id.imageNfcWaves);
        ((TextView) findViewById(R.id.textDataValue)).setText(viewDateFormat.format(new Date()));
        this.nameText = findViewById(R.id.editNome);
        this.surnameText = findViewById(R.id.editCognome);
        this.amountText = findViewById(R.id.editSaldo);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonBackToForm).setOnClickListener(v -> showFormState());
        findViewById(R.id.buttonWrite).setOnClickListener(v -> onWriteClicked());

        // set up NFC dispatch immediately so a tag tapped while still filling the form
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
        NfcFeedback.tagDetected(this, imageNfcWaves);
        Tag tag = extractTag(intent);
        if (tag == null) {
            return;
        }

        // form is still showing: user tapped a tag before pressing the write button
        if (groupWaiting.getVisibility() != View.VISIBLE) {
            showFormWarning();
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
        String name = this.nameText.getText().toString().trim();
        String surname = this.surnameText.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            this.nameText.setError(getString(R.string.create_error_nome));
            return;
        }
        if (TextUtils.isEmpty(surname)) {
            this.surnameText.setError(getString(R.string.create_error_cognome));
            return;
        }

        Editable amountE = this.amountText.getText();
        int amount;

        try {
            amount = amountE.isEmpty() ? 0 : Integer.parseInt(amountE.toString());
            if (amount < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            this.amountText.setError(getString(R.string.create_error_saldo));
            return;
        }

        Calendar creationDate = Calendar.getInstance(TimeZone.getTimeZone(timezone));
        creationDate.setTime(new Date());
        this.customer = new Customer(name, surname, creationDate, amount);

        hideFormWarning();
        showWaitingState(getString(R.string.create_waiting_subtitle), R.color.text_dim);
    }

    private byte[] fetchKeys() {
        return new byte[]{
//                (byte) 0xA1, (byte) 0xA1, (byte) 0xA1,
//                (byte) 0xA1, (byte) 0xA1, (byte) 0xA1
                (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff
        };
    }
    private void writeCard(Tag tag) {
        showWaitingState(getString(R.string.create_status_writing), R.color.neon_cyan);

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            showWaitingState(getString(R.string.nfc_error_not_mifare), R.color.neon_red);
            return;
        }

        Customer customerToWrite = this.customer;
        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, fetchKeys());
            try {
                nfcWrapper.saveData(customerToWrite);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            runOnUiThread(() -> showWaitingState(getString(R.string.create_status_success), R.color.neon_green));
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
