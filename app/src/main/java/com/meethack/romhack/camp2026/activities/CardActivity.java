package com.meethack.romhack.camp2026.activities;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.meethack.romhack.camp2026.exceptions.FailedToFetchKeys;
import com.meethack.romhack.camp2026.wrappers.NfcWrapper;
import com.meethack.romhack.camp2026.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// This activity is vibecoded 95%

public class CardActivity extends NfcActivity {
    private static final String TAG = "CardActivity";

    private enum Mode { READ, WRITE }

    private static final class BlockEditorMetadata {
        final int sector;
        final int blockIndex;
        final byte[] originalData;

        BlockEditorMetadata(int sector, int blockIndex, byte[] originalData) {
            this.sector = sector;
            this.blockIndex = blockIndex;
            this.originalData = originalData.clone();
        }
    }

    private Mode mode = Mode.READ;

    private View groupWaiting;
    private View screenBox;
    private View groupInfo;
    private View groupData;
    private TextView tabInfo;
    private TextView tabData;
    private TextView buttonHintAction;
    private boolean onDataTab;
    private TextView textWaitingStatus;
    private TextView textUid;
    private TextView textAtqa;
    private TextView textSak;
    private TextView textType;
    private LinearLayout dumpContainer;
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
        screenBox = findViewById(R.id.screenBox);
        groupInfo = findViewById(R.id.groupInfo);
        groupData = findViewById(R.id.groupData);
        tabInfo = findViewById(R.id.tabInfo);
        tabData = findViewById(R.id.tabData);
        buttonHintAction = findViewById(R.id.buttonHintAction);
        textWaitingStatus = findViewById(R.id.textWaitingStatus);
        textUid = findViewById(R.id.textUid);
        textAtqa = findViewById(R.id.textAtqa);
        textSak = findViewById(R.id.textSak);
        textType = findViewById(R.id.textType);
        dumpContainer = findViewById(R.id.dumpContainer);
        hexKeypad = findViewById(R.id.hexKeypad);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        tabInfo.setOnClickListener(v -> showInfo());
        tabData.setOnClickListener(v -> showData());
        buttonHintAction.setOnClickListener(v -> onHintActionClicked());
    }

    @Override
    protected void onTagDiscovered(Tag tag) {
        if (mode == Mode.WRITE) {
            writeEditedBlocks(tag);
        } else {
            readCard(tag);
        }
    }

    private void readCard(Tag tag) {
        textUid.setText(toHex(tag.getId()));

        android.nfc.tech.NfcA nfcA = android.nfc.tech.NfcA.get(tag);
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

        startNfcAnimation();
        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
            List<NfcWrapper.SectorDump> dump;
            try {
                dump = nfcWrapper.dumpSectors();
            } catch (IOException e) {
                onNfcResult(() -> {
                    addLabelRow(getString(R.string.nfc_error_io), R.color.lcd_ink, false);
                    Log.e(TAG, String.valueOf(e));
                    showData();
                });
                return;
            }
            catch (FailedToFetchKeys e){
                Log.e(TAG, "Cannot fetch keys for card", e);
                onNfcResult(() -> {
                    addLabelRow(getString(R.string.nfc_error_fetch_keys), R.color.lcd_ink, false);
                    showData();
                });
                return;
            }

            onNfcResult(() -> {
                for (NfcWrapper.SectorDump sector : dump) {
                    addLabelRow(getString(R.string.read_sector, sector.sector), R.color.lcd_ink, true);
                    if (!sector.authenticated) {
                        addLabelRow(getString(R.string.read_block_locked), R.color.lcd_dim, false);
                        continue;
                    }
                    for (int b = 0; b < sector.blocks.length; b++) {
                        byte[] block = sector.blocks[b];
                        String label = getString(R.string.read_block, b);
                        if (block != null) {
                            addBlockRow(label, sector.sector, b, block);
                        } else {
                            addLabelRow(label + ": " + getString(R.string.read_block_locked), R.color.lcd_dim, false);
                        }
                    }
                }
                showResult();
            });
        }).start();
    }

    private void onHintActionClicked() {
        if (onDataTab) {
            onWriteBackClicked();
        } else {
            showData();
        }
    }

    private void onWriteBackClicked() {
        mode = Mode.WRITE;
        hideKeypad();
        screenBox.setVisibility(View.GONE);
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
            BlockEditorMetadata metadata = (BlockEditorMetadata) editor.getTag();
            byte[] data = parseHex(editor.getText().toString());
            if (data == null) {
                hasInvalidHex = true;
                continue;
            }
            if (!Arrays.equals(data, metadata.originalData)) {
                edits.add(new NfcWrapper.RawBlockEdit(
                        metadata.sector, metadata.blockIndex, data));
            }
        }

        if (hasInvalidHex) {
            textWaitingStatus.setText(getString(R.string.read_error_invalid_hex));
            textWaitingStatus.setTextColor(getColor(R.color.neon_red));
            mode = Mode.READ;
            return;
        }
        if (edits.isEmpty()) {
            textWaitingStatus.setText(getString(R.string.read_status_no_changes));
            textWaitingStatus.setTextColor(getColor(R.color.text_dim));
            mode = Mode.READ;
            return;
        }

        startNfcAnimation();
        new Thread(() -> {
            NfcWrapper nfcWrapper = new NfcWrapper(mifare, this);
            try {
                nfcWrapper.writeRawBlocks(edits);
            } catch (IOException e) {
                Log.e(TAG, String.valueOf(e));

                onNfcResult(() -> {
                    textWaitingStatus.setText(getString(R.string.nfc_error_io));
                    textWaitingStatus.setTextColor(getColor(R.color.neon_red));
                    mode = Mode.READ;
                });
                return;
            } catch (FailedToFetchKeys e) {
                Log.e(TAG, "Cannot fetch keys for card", e);

                onNfcResult(() -> {
                    textWaitingStatus.setText(getString(R.string.nfc_error_fetch_keys));
                    textWaitingStatus.setTextColor(getColor(R.color.neon_red));
                    mode = Mode.READ;
                });
                return;
            }

            onNfcResult(() -> {
                textWaitingStatus.setText(getString(R.string.read_status_write_success));
                textWaitingStatus.setTextColor(getColor(R.color.neon_green));
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
        row.setTypeface(android.graphics.Typeface.MONOSPACE, header ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        row.setTextSize(12);
        row.setLetterSpacing(header ? 0.08f : 0f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (header) {
            params.topMargin = 12;
        }
        row.setLayoutParams(params);
        if (header) {
            // inverted highlight bar, matching Flipper's filled sector/section headers
            row.setAllCaps(true);
            row.setBackgroundColor(getColor(R.color.lcd_ink));
            row.setTextColor(getColor(R.color.lcd_bg));
            row.setPadding(8, 8, 8, 8);
        } else {
            row.setTextColor(getColor(colorRes));
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
        labelView.setTextColor(getColor(R.color.lcd_dim));
        labelView.setTypeface(android.graphics.Typeface.MONOSPACE);
        labelView.setTextSize(12);
        row.addView(labelView);

        EditText editor = new EditText(this);
        editor.setText(toHex(block));
        editor.setTextColor(getColor(R.color.lcd_ink));
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setTextSize(12);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        editor.setSingleLine(true);
        editor.setBackground(null);
        editor.setPadding(0, 0, 0, 0);
        editor.setTag(new BlockEditorMetadata(sector, blockIndex, block));
        editor.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (sector == 0 && blockIndex == 0) {
            editor.setTextColor(getColor(R.color.lcd_dim));
            editor.setFocusable(false);
            editor.setEnabled(false);
        }

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
        showInfo();
    }

    private void showInfo() {
        onDataTab = false;
        groupWaiting.setVisibility(View.GONE);
        screenBox.setVisibility(View.VISIBLE);
        groupData.setVisibility(View.GONE);
        groupInfo.setVisibility(View.VISIBLE);
        buttonHintAction.setText("▸ " + getString(R.string.read_hint_view_data));
        styleTabs();
    }

    private void showData() {
        onDataTab = true;
        groupWaiting.setVisibility(View.GONE);
        screenBox.setVisibility(View.VISIBLE);
        groupInfo.setVisibility(View.GONE);
        groupData.setVisibility(View.VISIBLE);
        buttonHintAction.setText("▸ " + getString(R.string.read_button_write_back));
        styleTabs();
    }

    /** Active tab gets Flipper's inverted (filled) highlight; inactive stays plain. */
    private void styleTabs() {
        TextView active = onDataTab ? tabData : tabInfo;
        TextView inactive = onDataTab ? tabInfo : tabData;
        active.setBackgroundColor(getColor(R.color.lcd_ink));
        active.setTextColor(getColor(R.color.lcd_bg));
        inactive.setBackgroundColor(getColor(R.color.lcd_bg));
        inactive.setTextColor(getColor(R.color.lcd_ink));
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
