package com.meethack.romhack.camp2026;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CardReadActivity extends AppCompatActivity {

    private enum Mode { READ, WRITE }

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private Mode mode = Mode.READ;

    private View groupWaiting;
    private View groupResult;
    private TextView textWaitingStatus;
    private TextView textUid;
    private TextView textAtqa;
    private TextView textSak;
    private TextView textType;
    private LinearLayout dumpContainer;
    private ImageView imageNfcWaves;
    private View hexKeypad;
    private EditText focusedEditor;

    private final List<EditText> blockEditors = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_card_read);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        groupWaiting = findViewById(R.id.groupWaiting);
        groupResult = findViewById(R.id.groupResult);
        textWaitingStatus = findViewById(R.id.textWaitingStatus);
        textUid = findViewById(R.id.textUid);
        textAtqa = findViewById(R.id.textAtqa);
        textSak = findViewById(R.id.textSak);
        textType = findViewById(R.id.textType);
        dumpContainer = findViewById(R.id.dumpContainer);
        imageNfcWaves = findViewById(R.id.imageNfcWaves);
        hexKeypad = findViewById(R.id.hexKeypad);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonWriteBack).setOnClickListener(v -> onWriteBackClicked());

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
        NfcFeedback.pulse(imageNfcWaves);
        Tag tag = extractTag(intent);
        if (tag == null) {
            return;
        }
        if (mode == Mode.WRITE) {
            writeEditedBlocks(tag);
        } else {
            readCard(tag);
        }
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

    private void readCard(Tag tag) {
        textUid.setText(toHex(tag.getId()));

        NfcA nfcA = NfcA.get(tag);
        if (nfcA != null) {
            textAtqa.setText(toHex(nfcA.getAtqa()));
            textSak.setText(toHex(new byte[]{(byte) nfcA.getSak()}));
        } else {
            textAtqa.setText("--");
            textSak.setText("--");
        }

        dumpContainer.removeAllViews();
        blockEditors.clear();
        focusedEditor = null;
        hideKeypad();

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            textType.setText(getString(R.string.nfc_error_not_mifare));
            showResult();
            return;
        }
        textType.setText(mifareTypeLabel(mifare));

        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, fetchKeys());
            List<NfcWrapper.SectorDump> dump;
            try {
                dump = nfcWrapper.dumpSectors();
            } catch (IOException e) {
                runOnUiThread(() -> {
                    addLabelRow(getString(R.string.nfc_error_io), R.color.neon_red, false);
                    showResult();
                });
                return;
            }

            runOnUiThread(() -> {
                for (NfcWrapper.SectorDump sector : dump) {
                    addLabelRow(getString(R.string.read_sector, sector.sector), R.color.neon_cyan, true);
                    if (!sector.authenticated) {
                        addLabelRow(getString(R.string.read_block_locked), R.color.text_dim, false);
                        continue;
                    }
                    for (int b = 0; b < sector.blocks.length; b++) {
                        byte[] block = sector.blocks[b];
                        String label = getString(R.string.read_block, b);
                        if (block != null) {
                            addBlockRow(label, sector.sector, b, block);
                        } else {
                            addLabelRow(label + ": " + getString(R.string.read_block_locked), R.color.text_dim, false);
                        }
                    }
                }
                showResult();
            });
        }).start();
    }

    private void onWriteBackClicked() {
        mode = Mode.WRITE;
        hideKeypad();
        groupResult.setVisibility(View.GONE);
        groupWaiting.setVisibility(View.VISIBLE);
        textWaitingStatus.setText(getString(R.string.read_waiting_write_subtitle));
        textWaitingStatus.setTextColor(getColor(R.color.text_dim));
    }

    private void writeEditedBlocks(Tag tag) {
        textWaitingStatus.setText(getString(R.string.read_status_writing));
        textWaitingStatus.setTextColor(getColor(R.color.neon_cyan));

        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            textWaitingStatus.setText(getString(R.string.nfc_error_not_mifare));
            textWaitingStatus.setTextColor(getColor(R.color.neon_red));
            mode = Mode.READ;
            return;
        }

        List<NfcWrapper.RawBlockEdit> edits = new ArrayList<>();
        boolean hasInvalidHex = false;
        for (EditText editor : blockEditors) {
            int[] location = (int[]) editor.getTag();
            byte[] data = parseHex(editor.getText().toString());
            if (data == null) {
                hasInvalidHex = true;
                continue;
            }
            edits.add(new NfcWrapper.RawBlockEdit(location[0], location[1], data));
        }

        boolean finalHasInvalidHex = hasInvalidHex;
        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, fetchKeys());
            try {
                nfcWrapper.writeRawBlocks(edits);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    textWaitingStatus.setText(getString(R.string.nfc_error_io));
                    textWaitingStatus.setTextColor(getColor(R.color.neon_red));
                    mode = Mode.READ;
                });
                return;
            }

            runOnUiThread(() -> {
                String status = getString(R.string.read_status_write_success);
                int colorRes = R.color.neon_green;
                if (finalHasInvalidHex) {
                    status = getString(R.string.read_error_invalid_hex);
                    colorRes = R.color.neon_red;
                }
                textWaitingStatus.setText(status);
                textWaitingStatus.setTextColor(getColor(colorRes));
                mode = Mode.READ;
            });
        }).start();
    }

    /** Parses "XX XX XX ..." (16 bytes) back into raw bytes; returns null if malformed. */
    private static byte[] parseHex(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 16) {
            return null;
        }
        byte[] data = new byte[16];
        try {
            for (int i = 0; i < 16; i++) {
                data[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return data;
    }

    private String mifareTypeLabel(MifareClassic mifare) {
        switch (mifare.getSize()) {
            case MifareClassic.SIZE_MINI:
                return "MIFARE Classic Mini";
            case MifareClassic.SIZE_1K:
                return "MIFARE Classic 1K";
            case MifareClassic.SIZE_2K:
                return "MIFARE Classic 2K";
            case MifareClassic.SIZE_4K:
                return "MIFARE Classic 4K";
            default:
                return "MIFARE Classic";
        }
    }

    private void addLabelRow(String text, int colorRes, boolean header) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(getColor(colorRes));
        row.setTypeface(android.graphics.Typeface.MONOSPACE, header ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        row.setTextSize(12);
        row.setLetterSpacing(header ? 0.08f : 0f);
        if (header) {
            row.setAllCaps(true);
            row.setPadding(0, 16, 0, 4);
        } else {
            row.setPadding(12, 1, 0, 1);
        }
        dumpContainer.addView(row);
    }

    private void addBlockRow(String label, int sector, int blockIndex, byte[] block) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(12, 1, 0, 1);

        TextView labelView = new TextView(this);
        labelView.setText(label + ": ");
        labelView.setTextColor(getColor(R.color.text_dim));
        labelView.setTypeface(android.graphics.Typeface.MONOSPACE);
        labelView.setTextSize(12);
        row.addView(labelView);

        EditText editor = new EditText(this);
        editor.setText(toHex(block));
        editor.setTextColor(getColor(R.color.neon_green));
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setTextSize(12);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        editor.setSingleLine(true);
        editor.setBackground(null);
        editor.setPadding(0, 0, 0, 0);
        editor.setTag(new int[]{sector, blockIndex});
        editor.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Flipper Zero-style editing: block system keyboard, use the on-screen hex keypad instead
        editor.setShowSoftInputOnFocus(false);
        editor.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                focusedEditor = editor;
                showKeypad();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
                }
            }
        });
        row.addView(editor);

        blockEditors.add(editor);
        dumpContainer.addView(row);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }

    private void showResult() {
        groupWaiting.setVisibility(View.GONE);
        groupResult.setVisibility(View.VISIBLE);
    }

    public void onHexKeyClick(View v) {
        if (focusedEditor == null) {
            return;
        }
        char digit = ((Button) v).getText().charAt(0);
        replaceHexCharAtCursor(focusedEditor, digit);
    }

    public void onHexBackspaceClick(View v) {
        if (focusedEditor == null) {
            return;
        }
        eraseHexCharBeforeCursor(focusedEditor);
    }

    public void onHexKeypadDoneClick(View v) {
        if (focusedEditor != null) {
            focusedEditor.clearFocus();
        }
        hideKeypad();
    }

    private void showKeypad() {
        hexKeypad.setVisibility(View.VISIBLE);
    }

    private void hideKeypad() {
        hexKeypad.setVisibility(View.GONE);
        focusedEditor = null;
    }

    /** Overwrites the nibble at the cursor, then advances past it (and any separating space). */
    private static void replaceHexCharAtCursor(EditText editor, char digit) {
        String text = editor.getText().toString();
        int pos = editor.getSelectionStart();
        if (pos < 0 || pos >= text.length()) {
            pos = 0;
        }
        if (text.charAt(pos) == ' ') {
            pos++;
        }
        if (pos >= text.length()) {
            return;
        }

        StringBuilder sb = new StringBuilder(text);
        sb.setCharAt(pos, digit);
        editor.setText(sb.toString());

        int next = pos + 1;
        if (next < sb.length() && sb.charAt(next) == ' ') {
            next++;
        }
        editor.setSelection(Math.min(next, sb.length()));
    }

    /** Moves back one nibble (skipping separators) and resets it to "0". */
    private static void eraseHexCharBeforeCursor(EditText editor) {
        String text = editor.getText().toString();
        int pos = editor.getSelectionStart();
        if (pos < 0) {
            pos = text.length();
        }
        pos--;
        if (pos >= 0 && text.charAt(pos) == ' ') {
            pos--;
        }
        if (pos < 0) {
            return;
        }

        StringBuilder sb = new StringBuilder(text);
        sb.setCharAt(pos, '0');
        editor.setText(sb.toString());
        editor.setSelection(pos);
    }
}
