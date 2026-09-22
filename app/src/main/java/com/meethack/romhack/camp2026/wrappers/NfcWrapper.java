package com.meethack.romhack.camp2026.wrappers;

import android.content.Context;
import android.nfc.tech.MifareClassic;
import android.util.Log;

import com.meethack.romhack.camp2026.assets.Customer;
import com.meethack.romhack.camp2026.exceptions.InvalidValueBlock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/** Centralized, controller-safe MIFARE Classic access for every admin scene. */
public class NfcWrapper {
    private static final String TAG = "NfcWrapper";
    private static final int BLOCK_SIZE = MifareClassic.BLOCK_SIZE;
    private static final int KEY_A_INDEX = 0;
    private static final int KEY_B_INDEX = 1;
    private static final int APPLICATION_SECTOR_COUNT = 16;

    private static final int NAME_SECTOR = 15;
    private static final int SURNAME_SECTOR = 14;
    private static final int CREATION_DATE_SECTOR = 13;
    private static final int AMOUNT_SECTOR = 12;
    private static final byte VALUE_BLOCK_ADDRESS = 0x49;

    private static final byte[] ACCESS_BITS = new byte[]{
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0x49
    };
    private static final byte[] RESET_FACTORY_ACCESS_BITS = new byte[]{
            (byte) 0xFF, (byte) 0x07, (byte) 0x80, (byte) 0x49
    };
    private static final byte[] WRITABLE_ACCESS_BITS = new byte[]{
            (byte) 0xF7, (byte) 0x87, (byte) 0x80, (byte) 0x49
    };
    private static final byte[] SECTOR_0_TRAILER = new byte[]{
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0x90, (byte) 0xF0,
            (byte) 0xF6, (byte) 0x49, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };

    private enum KeyType { A, B }

    private enum KeySource { SERVER, DEFAULT }

    private static final class KeyCandidate {
        final KeyType type;
        final KeySource source;
        final byte[] key;

        KeyCandidate(KeyType type, KeySource source, byte[] key) {
            this.type = type;
            this.source = source;
            this.key = key;
        }
    }

    private static final class Authentication {
        final KeySource source;

        Authentication(KeySource source) {
            this.source = source;
        }
    }

    private final MifareClassic mifareCard;
    private final RequestWrapper requestWrapper;
    private byte[][][] sectorKeys = new byte[0][][];
    private boolean keysLoaded;

    public NfcWrapper(MifareClassic mifareCard, Context context) {
        this.mifareCard = mifareCard;
        this.requestWrapper = new RequestWrapper(context);
    }

    /**
     * Tries one key at a time and resets the Crypto1 session after every failure. This is
     * required by controllers that reject every command after a failed authentication.
     */
    private Authentication authenticateSector(int sector) throws IOException {
        validateSector(sector);
        fetchKeys();

        // Keep the order used by the verified fixed APK.
        for (KeyCandidate candidate : authenticationCandidates(sector)) {
            try {
                if (authenticate(sector, candidate)) {
                    return new Authentication(candidate.source);
                }
            } catch (IOException failure) {
                reconnectAfterFailure(failure);
                continue;
            }
            reconnect();
        }
        return null;
    }

    private boolean authenticate(int sector, KeyCandidate candidate) throws IOException {
        return candidate.type == KeyType.A
                ? mifareCard.authenticateSectorWithKeyA(sector, candidate.key)
                : mifareCard.authenticateSectorWithKeyB(sector, candidate.key);
    }

    private List<KeyCandidate> authenticationCandidates(int sector) {
        List<KeyCandidate> candidates = new ArrayList<>(4);
        addServerCandidate(candidates, sector, KEY_B_INDEX, KeyType.B);
        addServerCandidate(candidates, sector, KEY_A_INDEX, KeyType.A);
        addCandidate(candidates, new KeyCandidate(
                KeyType.B, KeySource.DEFAULT, MifareClassic.KEY_DEFAULT));
        addCandidate(candidates, new KeyCandidate(
                KeyType.A, KeySource.DEFAULT, MifareClassic.KEY_DEFAULT));
        return candidates;
    }

    private List<KeyCandidate> blockOperationCandidates(int sector) {
        List<KeyCandidate> candidates = new ArrayList<>(4);
        addServerCandidate(candidates, sector, KEY_A_INDEX, KeyType.A);
        addServerCandidate(candidates, sector, KEY_B_INDEX, KeyType.B);
        addCandidate(candidates, new KeyCandidate(
                KeyType.A, KeySource.DEFAULT, MifareClassic.KEY_DEFAULT));
        addCandidate(candidates, new KeyCandidate(
                KeyType.B, KeySource.DEFAULT, MifareClassic.KEY_DEFAULT));
        return candidates;
    }

    private void addServerCandidate(List<KeyCandidate> candidates, int sector, int keyIndex,
                                    KeyType type) {
        if (sector >= sectorKeys.length || sectorKeys[sector] == null
                || keyIndex >= sectorKeys[sector].length) {
            return;
        }
        byte[] key = sectorKeys[sector][keyIndex];
        if (key != null && key.length == Utils.keySize) {
            addCandidate(candidates, new KeyCandidate(type, KeySource.SERVER, key));
        }
    }

    private static void addCandidate(List<KeyCandidate> candidates, KeyCandidate candidate) {
        for (KeyCandidate existing : candidates) {
            if (existing.type == candidate.type && Arrays.equals(existing.key, candidate.key)) {
                return;
            }
        }
        candidates.add(candidate);
    }

    /** Reads block zero once, releases NFC during the network call, and caches validated keys. */
    public void fetchKeys() throws IOException {
        if (keysLoaded) {
            return;
        }

        ensureConnected();
        if (!mifareCard.authenticateSectorWithKeyA(0, MifareClassic.KEY_DEFAULT)) {
            throw new IOException("Could not authenticate sector 0 to read block 0");
        }
        byte[] block0 = mifareCard.readBlock(0);
        closeQuietly();

        byte[][][] fetchedKeys = requestWrapper.fetchKeys(block0);
        validateFetchedKeys(fetchedKeys);
        sectorKeys = fetchedKeys;
        keysLoaded = true;
        ensureConnected();
    }

    private static void validateFetchedKeys(byte[][][] fetchedKeys) throws IOException {
        if (fetchedKeys == null || fetchedKeys.length < APPLICATION_SECTOR_COUNT) {
            throw new IOException("The key service did not return all application sector keys");
        }
        for (int sector = 0; sector < APPLICATION_SECTOR_COUNT; sector++) {
            byte[][] keys = fetchedKeys[sector];
            if (keys == null || keys.length < 2
                    || keys[KEY_A_INDEX] == null || keys[KEY_A_INDEX].length != Utils.keySize
                    || keys[KEY_B_INDEX] == null || keys[KEY_B_INDEX].length != Utils.keySize) {
                throw new IOException("Invalid keys returned for sector " + sector);
            }
        }
    }

    public static final class SectorDump {
        public final int sector;
        public final boolean authenticated;
        public final byte[][] blocks;

        SectorDump(int sector, boolean authenticated, byte[][] blocks) {
            this.sector = sector;
            this.authenticated = authenticated;
            this.blocks = blocks;
        }
    }

    public List<SectorDump> dumpSectors() throws IOException {
        ensureConnected();
        try {
            fetchKeys();
            List<SectorDump> dump = new ArrayList<>();
            for (int sector = 0; sector < mifareCard.getSectorCount(); sector++) {
                Authentication authentication = authenticateSector(sector);
                int firstBlock = mifareCard.sectorToBlock(sector);
                int blockCount = mifareCard.getBlockCountInSector(sector);
                byte[][] blocks = new byte[blockCount][];

                if (authentication != null) {
                    for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                        blocks[blockIndex] = readBlockOrZeros(firstBlock + blockIndex);
                    }
                    injectKnownKeys(sector, authentication, blocks, blockCount - 1);
                }
                dump.add(new SectorDump(sector, authentication != null, blocks));
            }
            return dump;
        } finally {
            closeQuietly();
        }
    }

    private void injectKnownKeys(int sector, Authentication authentication, byte[][] blocks,
                                 int trailerIndex) {
        byte[] trailer = blocks[trailerIndex];
        if (trailer == null || trailer.length != BLOCK_SIZE) {
            return;
        }
        byte[] keyA = MifareClassic.KEY_DEFAULT;
        byte[] keyB = MifareClassic.KEY_DEFAULT;
        if (authentication.source == KeySource.SERVER && sector < sectorKeys.length) {
            keyA = sectorKeys[sector][KEY_A_INDEX];
            keyB = sectorKeys[sector][KEY_B_INDEX];
        }
        System.arraycopy(keyA, 0, trailer, 0, Utils.keySize);
        System.arraycopy(keyB, 0, trailer, 10, Utils.keySize);
    }

    public static final class RawBlockEdit {
        public final int sector;
        public final int blockIndexInSector;
        public final byte[] data;

        public RawBlockEdit(int sector, int blockIndexInSector, byte[] data) {
            if (sector < 0 || blockIndexInSector < 0) {
                throw new IllegalArgumentException("Sector and block index must be non-negative");
            }
            if (data == null || data.length != BLOCK_SIZE) {
                throw new IllegalArgumentException("A MIFARE Classic block must contain 16 bytes");
            }
            this.sector = sector;
            this.blockIndexInSector = blockIndexInSector;
            this.data = data.clone();
        }
    }

    static boolean logicW(int c1, int c2, int c3) {
        return (c1 == 0 && c2 == 0 && c3 == 0)
                || (c1 == 1 && c2 == 0 && c3 == 0)
                || (c1 == 1 && c2 == 1 && c3 == 0)
                || (c1 == 0 && c2 == 1 && c3 == 1);
    }

    boolean isBlockWritable(byte[] trailer, int blockIndex) {
        if (trailer == null || trailer.length != BLOCK_SIZE) {
            return false;
        }
        int group = accessGroupForBlock(blockIndex, 4);
        if (group < 0 || group > 2) {
            return false;
        }
        return logicW(bit(trailer[7], 4 + group), bit(trailer[8], group),
                bit(trailer[8], 4 + group));
    }

    private boolean isBlockWritable(byte[] trailer, int blockIndex, int blockCount) {
        if (!hasValidAccessBits(trailer)) {
            return false;
        }
        int group = accessGroupForBlock(blockIndex, blockCount);
        if (group < 0 || group > 2) {
            return false;
        }
        return logicW(bit(trailer[7], 4 + group), bit(trailer[8], group),
                bit(trailer[8], 4 + group));
    }

    private static int accessGroupForBlock(int blockIndex, int blockCount) {
        if (blockIndex < 0 || blockIndex >= blockCount - 1) {
            return -1;
        }
        return blockCount <= 4 ? blockIndex : Math.min(blockIndex / 5, 2);
    }

    private static boolean hasValidAccessBits(byte[] trailer) {
        if (trailer == null || trailer.length != BLOCK_SIZE) {
            return false;
        }
        for (int group = 0; group < 4; group++) {
            int c1 = bit(trailer[7], 4 + group);
            int c2 = bit(trailer[8], group);
            int c3 = bit(trailer[8], 4 + group);
            if (bit(trailer[6], group) != (c1 ^ 1)
                    || bit(trailer[6], 4 + group) != (c2 ^ 1)
                    || bit(trailer[7], group) != (c3 ^ 1)) {
                return false;
            }
        }
        return true;
    }

    private static int bit(byte value, int position) {
        return (value >>> position) & 1;
    }

    public void writeRawBlocks(List<RawBlockEdit> edits) throws IOException {
        if (edits == null) {
            throw new IllegalArgumentException("edits must not be null");
        }
        if (edits.isEmpty()) {
            return;
        }

        ensureConnected();
        try {
            fetchKeys();
            validateEdits(edits);
            int activeSector = -1;
            int firstBlock = -1;
            int blockCount = 0;
            byte[] trailer = null;

            for (RawBlockEdit edit : edits) {
                if (edit.sector != activeSector) {
                    requireAuthentication(edit.sector);
                    activeSector = edit.sector;
                    firstBlock = mifareCard.sectorToBlock(activeSector);
                    blockCount = mifareCard.getBlockCountInSector(activeSector);
                    trailer = readBlockResilient(firstBlock + blockCount - 1);
                }

                int absoluteBlock = firstBlock + edit.blockIndexInSector;
                int trailerIndex = blockCount - 1;
                if (absoluteBlock == 0) {
                    continue;
                }
                if (edit.blockIndexInSector != trailerIndex
                        && !isBlockWritable(trailer, edit.blockIndexInSector, blockCount)) {
                    continue;
                }
                writeBlockResilient(absoluteBlock, edit.data);
                if (edit.blockIndexInSector == trailerIndex) {
                    trailer = edit.data.clone();
                }
            }
        } finally {
            closeQuietly();
        }
    }

    private void validateEdits(List<RawBlockEdit> edits) throws IOException {
        int sectorCount = mifareCard.getSectorCount();
        for (RawBlockEdit edit : edits) {
            if (edit == null) {
                throw new IOException("The write list contains an empty edit");
            }
            if (edit.sector >= sectorCount) {
                throw new IOException("Sector " + edit.sector + " is not present on this card");
            }
            if (edit.blockIndexInSector >= mifareCard.getBlockCountInSector(edit.sector)) {
                throw new IOException("Invalid block in sector " + edit.sector);
            }
        }
    }

    public void saveData(Customer customer) throws IOException {
        if (customer == null) {
            throw new IllegalArgumentException("customer must not be null");
        }
        ensureConnected();
        try {
            fetchKeys();
            requireApplicationCard();
            writeText(NAME_SECTOR, customer.getName());
            writeText(SURNAME_SECTOR, customer.getSurname());
            writeCreationDate(customer.getCreationDate());
            writeAmount(customer.getAmount());
            rewriteAccessBits();
        } finally {
            closeQuietly();
        }
    }

    private void writeText(int sector, String value) throws IOException {
        requireAuthentication(sector);
        byte[] bytes = Utils.stringToHex(value);
        int firstBlock = mifareCard.sectorToBlock(sector);
        for (int index = 0; index < 3; index++) {
            writeBlockResilient(firstBlock + index,
                    Arrays.copyOfRange(bytes, BLOCK_SIZE * index, BLOCK_SIZE * (index + 1)));
        }
    }

    private void writeCreationDate(Calendar creationDate) throws IOException {
        if (creationDate == null) {
            throw new IllegalArgumentException("creationDate must not be null");
        }
        requireAuthentication(CREATION_DATE_SECTOR);
        int firstBlock = mifareCard.sectorToBlock(CREATION_DATE_SECTOR);
        writeBlockResilient(firstBlock, createValueBlock(creationDate.get(Calendar.YEAR)));
        writeBlockResilient(firstBlock + 1,
                createValueBlock(creationDate.get(Calendar.MONTH) + 1));
        writeBlockResilient(firstBlock + 2,
                createValueBlock(creationDate.get(Calendar.DAY_OF_MONTH)));
    }

    private void writeAmount(int amount) throws IOException {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        requireAuthentication(AMOUNT_SECTOR);
        writeBlockResilient(mifareCard.sectorToBlock(AMOUNT_SECTOR), createValueBlock(amount));
    }

    public void recharge(int amount) throws IOException {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        changeAmount(amount, true);
    }

    public void buy(int amount) throws IOException {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        changeAmount(amount, false);
    }

    private void changeAmount(int amount, boolean increment) throws IOException {
        ensureConnected();
        int trailerBlock = -1;
        boolean writableTrailerInstalled = false;
        IOException ioFailure = null;
        RuntimeException runtimeFailure = null;
        try {
            fetchKeys();
            requireApplicationCard();
            requireAuthentication(AMOUNT_SECTOR);
            int valueBlock = mifareCard.sectorToBlock(AMOUNT_SECTOR);
            trailerBlock = valueBlock + mifareCard.getBlockCountInSector(AMOUNT_SECTOR) - 1;
            byte[] original = readBlockResilient(valueBlock);
            if (!isValidValueBlock(original) || !checkAccessBits(trailerBlock)) {
                throw new InvalidValueBlock("This block looks tampered");
            }

            int currentValue = valueFromBlock(original);
            int expectedValue;
            try {
                expectedValue = increment
                        ? Math.addExact(currentValue, amount)
                        : Math.subtractExact(currentValue, amount);
            } catch (ArithmeticException overflow) {
                throw new IOException("The balance operation overflows a signed integer", overflow);
            }
            if (expectedValue < 0) {
                throw new IOException("The balance cannot become negative");
            }

            writeBlockResilient(trailerBlock, createWritableSectorTrailer());
            writableTrailerInstalled = true;
            valueOperationResilient(valueBlock, amount, increment);

            byte[] updated = readBlockResilient(valueBlock);
            if (!isValidValueBlock(updated) || valueFromBlock(updated) != expectedValue) {
                throw new IOException("The balance write could not be verified");
            }
        } catch (IOException failure) {
            ioFailure = failure;
            throw failure;
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
            throw failure;
        } finally {
            if (writableTrailerInstalled) {
                try {
                    writeBlockResilient(trailerBlock, createSectorTrailer(AMOUNT_SECTOR));
                } catch (IOException restoreFailure) {
                    if (ioFailure != null) {
                        ioFailure.addSuppressed(restoreFailure);
                    } else if (runtimeFailure != null) {
                        runtimeFailure.addSuppressed(restoreFailure);
                    } else {
                        closeQuietly();
                        throw restoreFailure;
                    }
                }
            }
            closeQuietly();
        }
    }

    public int readAmount() throws IOException {
        ensureConnected();
        try {
            fetchKeys();
            requireApplicationCard();
            requireAuthentication(AMOUNT_SECTOR);
            int valueBlock = mifareCard.sectorToBlock(AMOUNT_SECTOR);
            int trailerBlock = valueBlock + mifareCard.getBlockCountInSector(AMOUNT_SECTOR) - 1;
            byte[] block = readBlockResilient(valueBlock);
            if (!isValidValueBlock(block) || !checkAccessBits(trailerBlock)) {
                throw new InvalidValueBlock("This block looks tampered");
            }
            return valueFromBlock(block);
        } finally {
            closeQuietly();
        }
    }

    private boolean checkAccessBits(int trailerBlock) throws IOException {
        byte[] trailer = readBlockResilient(trailerBlock);
        return trailer[6] == ACCESS_BITS[0]
                && trailer[7] == ACCESS_BITS[1]
                && trailer[8] == ACCESS_BITS[2]
                && trailer[9] == ACCESS_BITS[3];
    }

    private static boolean isValidValueBlock(byte[] block) throws IOException {
        if (block == null || block.length != BLOCK_SIZE) {
            throw new IOException("Block read returned an invalid length");
        }
        for (int index = 0; index < 4; index++) {
            if (block[index] != block[index + 8]
                    || (byte) ~block[index] != block[index + 4]) {
                return false;
            }
        }
        return block[12] == block[14]
                && (byte) ~block[12] == block[13]
                && (byte) ~block[14] == block[15];
    }

    private static int valueFromBlock(byte[] block) {
        return ByteBuffer.wrap(block, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] createValueBlock(int value) {
        byte[] block = new byte[BLOCK_SIZE];
        byte[] encoded = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value).array();
        for (int index = 0; index < encoded.length; index++) {
            block[index] = encoded[index];
            block[index + 4] = (byte) ~encoded[index];
            block[index + 8] = encoded[index];
        }
        block[12] = VALUE_BLOCK_ADDRESS;
        block[13] = (byte) ~VALUE_BLOCK_ADDRESS;
        block[14] = VALUE_BLOCK_ADDRESS;
        block[15] = (byte) ~VALUE_BLOCK_ADDRESS;
        return block;
    }

    private byte[] createSectorTrailer(int sector) throws IOException {
        if (sector < 0 || sector >= sectorKeys.length) {
            throw new IOException("No server keys available for sector " + sector);
        }
        byte[] trailer = new byte[BLOCK_SIZE];
        System.arraycopy(sectorKeys[sector][KEY_A_INDEX], 0, trailer, 0, Utils.keySize);
        System.arraycopy(ACCESS_BITS, 0, trailer, 6, ACCESS_BITS.length);
        System.arraycopy(sectorKeys[sector][KEY_B_INDEX], 0, trailer, 10, Utils.keySize);
        return trailer;
    }

    private static byte[] createResetFactorySectorTrailer() {
        return createTrailer(MifareClassic.KEY_DEFAULT, RESET_FACTORY_ACCESS_BITS,
                MifareClassic.KEY_DEFAULT);
    }

    private static byte[] createWritableSectorTrailer() {
        return createTrailer(MifareClassic.KEY_DEFAULT, WRITABLE_ACCESS_BITS,
                MifareClassic.KEY_DEFAULT);
    }

    private static byte[] createTrailer(byte[] keyA, byte[] accessBits, byte[] keyB) {
        byte[] trailer = new byte[BLOCK_SIZE];
        System.arraycopy(keyA, 0, trailer, 0, Utils.keySize);
        System.arraycopy(accessBits, 0, trailer, 6, accessBits.length);
        System.arraycopy(keyB, 0, trailer, 10, Utils.keySize);
        return trailer;
    }

    private void rewriteAccessBits() throws IOException {
        requireAuthentication(0);
        int trailerBlock = mifareCard.sectorToBlock(0)
                + mifareCard.getBlockCountInSector(0) - 1;
        writeBlockResilient(trailerBlock, SECTOR_0_TRAILER);

        for (int sector = 1; sector < APPLICATION_SECTOR_COUNT; sector++) {
            requireAuthentication(sector);
            trailerBlock = mifareCard.sectorToBlock(sector)
                    + mifareCard.getBlockCountInSector(sector) - 1;
            writeBlockResilient(trailerBlock, createSectorTrailer(sector));
        }
    }

    public void format() throws IOException {
        ensureConnected();
        try {
            fetchKeys();
            byte[] emptyBlock = new byte[BLOCK_SIZE];
            int sectorLimit = Math.min(APPLICATION_SECTOR_COUNT, mifareCard.getSectorCount());
            for (int sector = 0; sector < sectorLimit; sector++) {
                requireAuthentication(sector);
                int firstBlock = mifareCard.sectorToBlock(sector);
                int blockCount = mifareCard.getBlockCountInSector(sector);
                int trailerBlock = firstBlock + blockCount - 1;
                byte[] factoryTrailer = createResetFactorySectorTrailer();

                writeBlockResilient(trailerBlock, factoryTrailer);
                int firstDataBlock = firstBlock == 0 ? firstBlock + 1 : firstBlock;
                for (int block = firstDataBlock; block < trailerBlock; block++) {
                    writeBlockResilient(block, emptyBlock);
                }
                writeBlockResilient(trailerBlock, factoryTrailer);
            }
        } finally {
            closeQuietly();
        }
    }

    private byte[] readBlockResilient(int block) throws IOException {
        IOException failures;
        try {
            return mifareCard.readBlock(block);
        } catch (IOException failure) {
            failures = failure;
        }

        int sector = mifareCard.blockToSector(block);
        for (KeyCandidate candidate : blockOperationCandidates(sector)) {
            try {
                reconnect();
                if (authenticate(sector, candidate)) {
                    return mifareCard.readBlock(block);
                }
            } catch (IOException failure) {
                failures = appendFailure(failures, failure);
            }
        }
        throw operationFailure("read", block, failures);
    }

    private byte[] readBlockOrZeros(int block) throws IOException {
        try {
            return readBlockResilient(block);
        } catch (IOException failure) {
            Log.w(TAG, "Block " + block + " is not readable with the available keys", failure);
            return new byte[BLOCK_SIZE];
        }
    }

    private void writeBlockResilient(int block, byte[] data) throws IOException {
        IOException failures;
        try {
            mifareCard.writeBlock(block, data);
            return;
        } catch (IOException failure) {
            failures = failure;
        }

        int sector = mifareCard.blockToSector(block);
        for (KeyCandidate candidate : blockOperationCandidates(sector)) {
            try {
                reconnect();
                if (authenticate(sector, candidate)) {
                    mifareCard.writeBlock(block, data);
                    return;
                }
            } catch (IOException failure) {
                failures = appendFailure(failures, failure);
            }
        }
        throw operationFailure("write", block, failures);
    }

    private void valueOperationResilient(int block, int amount, boolean increment)
            throws IOException {
        IOException failures;
        try {
            performValueOperation(block, amount, increment);
            return;
        } catch (IOException failure) {
            failures = failure;
        }

        int sector = mifareCard.blockToSector(block);
        for (KeyCandidate candidate : blockOperationCandidates(sector)) {
            try {
                reconnect();
                if (authenticate(sector, candidate)) {
                    performValueOperation(block, amount, increment);
                    return;
                }
            } catch (IOException failure) {
                failures = appendFailure(failures, failure);
            }
        }
        throw operationFailure(increment ? "increment" : "decrement", block, failures);
    }

    private void performValueOperation(int block, int amount, boolean increment)
            throws IOException {
        if (increment) {
            mifareCard.increment(block, amount);
        } else {
            mifareCard.decrement(block, amount);
        }
        mifareCard.transfer(block);
    }

    private void requireAuthentication(int sector) throws IOException {
        if (authenticateSector(sector) == null) {
            throw new IOException("Authentication failed for sector " + sector);
        }
    }

    private void requireApplicationCard() throws IOException {
        if (mifareCard.getSectorCount() < APPLICATION_SECTOR_COUNT) {
            throw new IOException("This operation requires a MIFARE Classic card with 16 sectors");
        }
    }

    private void validateSector(int sector) throws IOException {
        if (sector < 0 || sector >= mifareCard.getSectorCount()) {
            throw new IOException("Invalid sector " + sector);
        }
    }

    private void ensureConnected() throws IOException {
        if (!mifareCard.isConnected()) {
            mifareCard.connect();
        }
    }

    private void reconnectAfterFailure(IOException failure) throws IOException {
        try {
            reconnect();
        } catch (IOException reconnectFailure) {
            reconnectFailure.addSuppressed(failure);
            throw reconnectFailure;
        }
    }

    private void reconnect() throws IOException {
        IOException closeFailure = null;
        try {
            mifareCard.close();
        } catch (IOException failure) {
            closeFailure = failure;
        }
        try {
            mifareCard.connect();
        } catch (IOException connectFailure) {
            if (closeFailure != null) {
                connectFailure.addSuppressed(closeFailure);
            }
            throw connectFailure;
        }
        if (closeFailure != null) {
            Log.w(TAG, "NFC connection closed with an error before reconnecting", closeFailure);
        }
    }

    private void closeQuietly() {
        if (!mifareCard.isConnected()) {
            return;
        }
        try {
            mifareCard.close();
        } catch (IOException failure) {
            Log.w(TAG, "Could not close the NFC connection", failure);
        }
    }

    private static IOException appendFailure(IOException current, IOException next) {
        current.addSuppressed(next);
        return current;
    }

    private static IOException operationFailure(String operation, int block, IOException cause) {
        return new IOException(
                "Could not " + operation + " block " + block + " with the available keys",
                cause);
    }
}
