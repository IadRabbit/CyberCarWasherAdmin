package com.meethack.romhack.camp2026;

import android.nfc.tech.MifareClassic;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class NfcWrapper {
    private final byte[] keyB;
    private final MifareClassic mifareCard;
    private static final int nameSector = 15;
    private static final int surnameSector = 14;
    private static final int creationDateSector = 13;
    private static final int amountSector = 12;
    private static final byte[] accessBits = new byte[]{
            (byte)0xF0, (byte)0xF0, (byte)0xF0, (byte)0x49
    };

    private static final byte[] resetFactoryAccessBits = new byte[]{
            (byte)0xFF, (byte)0x07, (byte)0x80, (byte)0x49
    };

    private static final byte[] writableAccessBits = new byte[]{
            (byte)0xF7, (byte)0x87, (byte)0x80, (byte)0x49
    };

    private static final byte[] defaultKey = new byte[]{
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF
    };

    public NfcWrapper(MifareClassic mifareCard, byte[] keyB){
        this.mifareCard = mifareCard;
        this.keyB = keyB;
    }

    public static byte[] stringToHex(String value){
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, 48);
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

    /** Raw block-by-block dump, no interpretation of the bytes' meaning. */
    public List<SectorDump> dumpSectors() throws IOException {
        this.mifareCard.connect();
        List<SectorDump> dump = new ArrayList<>();
        int sectorCount = this.mifareCard.getSectorCount();

        for (int sector = 0; sector < sectorCount; sector++) {
            boolean authenticated = this.mifareCard.authenticateSectorWithKeyA(sector, this.keyB)
                    || this.mifareCard.authenticateSectorWithKeyB(sector, this.keyB);
            int firstBlockOfSector = this.mifareCard.sectorToBlock(sector);
            int blockCount = this.mifareCard.getBlockCountInSector(sector);
            byte[][] blocks = new byte[blockCount][];

            if (authenticated) {
                for (int b = 0; b < blockCount; b++) {
                    try {
                        blocks[b] = this.mifareCard.readBlock(firstBlockOfSector + b);
                    } catch (IOException ignored) {
                        blocks[b] = null;
                    }
                }
            }

            dump.add(new SectorDump(sector, authenticated, blocks));
        }

        this.mifareCard.close();
        return dump;
    }

    public static final class RawBlockEdit {
        public final int sector;
        public final int blockIndexInSector;
        public final byte[] data;

        public RawBlockEdit(int sector, int blockIndexInSector, byte[] data) {
            this.sector = sector;
            this.blockIndexInSector = blockIndexInSector;
            this.data = data;
        }
    }

    /** Writes back arbitrary blocks as-is, no interpretation of the bytes' meaning. */
    public void writeRawBlocks(List<RawBlockEdit> edits) throws IOException {
        this.mifareCard.connect();
        for (RawBlockEdit edit : edits) {
            boolean authenticated = this.mifareCard.authenticateSectorWithKeyA(edit.sector, this.keyB)
                    || this.mifareCard.authenticateSectorWithKeyB(edit.sector, this.keyB);
            if (!authenticated) {
                continue;
            }
            int block = this.mifareCard.sectorToBlock(edit.sector) + edit.blockIndexInSector;
            this.mifareCard.writeBlock(block, edit.data);
        }
        this.mifareCard.close();
    }

    public void saveData(Customer customer) throws IOException {
        this.mifareCard.connect();
        this.writeName(customer.getName());
        this.writeSurname(customer.getSurname());
        this.writeCreationDate(customer.getCreationDate());
        this.writeAmount(customer.getAmount());
        this.rewriteAccessBits();
        this.mifareCard.close();
    }

    private void writeName(String name) throws IOException {
        this.mifareCard.authenticateSectorWithKeyB(nameSector, this.keyB);
        byte[] nameB = NfcWrapper.stringToHex(name);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(nameSector);

         for (int a = 0; a < 3; a++) {
             byte[] chunk = Arrays.copyOfRange(nameB, 16 * a, 16 * a + 16);
             this.mifareCard.writeBlock(firstBlockOfSector + a, chunk);
         }
    }

    private void writeSurname(String surname) throws IOException {
        this.mifareCard.authenticateSectorWithKeyB(surnameSector, this.keyB);
        byte[] nameB = NfcWrapper.stringToHex(surname);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(surnameSector);

        for (int a = 0; a < 3; a++) {
            byte[] chunk = Arrays.copyOfRange(nameB, 16 * a, 16 * a + 16);
            this.mifareCard.writeBlock(firstBlockOfSector + a, chunk);
        }
    }

    private void writeCreationDate(Calendar creationDate) throws IOException {
        byte[] year = ByteBuffer.allocate(16).putInt(creationDate.get(Calendar.YEAR)).array();
        byte[] month = ByteBuffer.allocate(16).putInt(creationDate.get(Calendar.MONTH)).array();
        byte[] day = ByteBuffer.allocate(16).putInt(creationDate.get(Calendar.DAY_OF_MONTH)).array();
        this.mifareCard.authenticateSectorWithKeyB(creationDateSector, this.keyB);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(creationDateSector);
        this.mifareCard.writeBlock(firstBlockOfSector, year);
        this.mifareCard.writeBlock(firstBlockOfSector + 1, month);
        this.mifareCard.writeBlock(firstBlockOfSector + 2, day);
    }

    private void writeAmount(long amount) throws IOException {
        byte[] amountB = ByteBuffer.allocate(16).putLong(amount).array();
        this.mifareCard.authenticateSectorWithKeyB(amountSector, this.keyB);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(amountSector);
        this.mifareCard.writeBlock(firstBlockOfSector, amountB);
        this.mifareCard.increment(firstBlockOfSector, 1);
        this.mifareCard.transfer(firstBlockOfSector);
    }

    private byte[] createSectorTrailer(){
        byte[] chunk = new byte[16];

        System.arraycopy(keyB, 0, chunk, 0, 6);
        System.arraycopy(accessBits, 0, chunk, 6, 4);
        System.arraycopy(keyB, 0, chunk, 10, 6);

        return chunk;
    }

    private byte[] createResetFactorySectorTrailer(){
        byte[] chunk = new byte[16];

        System.arraycopy(defaultKey, 0, chunk, 0, 6);
        System.arraycopy(resetFactoryAccessBits, 0, chunk, 6, 4);
        System.arraycopy(defaultKey, 0, chunk, 10, 6);

        return chunk;
    }

    private byte[] createWritableSectorTrailer(){
        byte[] chunk = new byte[16];

        System.arraycopy(defaultKey, 0, chunk, 0, 6);
        System.arraycopy(writableAccessBits, 0, chunk, 6, 4);
        System.arraycopy(defaultKey, 0, chunk, 10, 6);

        return chunk;
    }

    private void rewriteAccessBits() throws IOException {
        for (int a = 0; a < 16; a++){
            this.mifareCard.authenticateSectorWithKeyA(a, this.keyB);
            this.mifareCard.authenticateSectorWithKeyB(a, this.keyB);
            int trailerBlockOfSector = this.mifareCard.sectorToBlock(a) + 3;
            this.mifareCard.writeBlock(trailerBlockOfSector, createSectorTrailer());
        }
    }

    public void format() throws IOException {
        this.mifareCard.connect();
        for (int a = 0; a < 16; a++){
            this.mifareCard.authenticateSectorWithKeyA(a, this.keyB);
            this.mifareCard.authenticateSectorWithKeyB(a, this.keyB);
            int firstBlockOfSector = this.mifareCard.sectorToBlock(a);
            this.mifareCard.writeBlock(firstBlockOfSector + 3, createWritableSectorTrailer());

            // block 0 of sector 0 is the read-only manufacturer block
            int firstDataBlock = (a == 0) ? firstBlockOfSector + 1 : firstBlockOfSector;
            for (int block = firstDataBlock; block < firstBlockOfSector + 3; block++) {
                this.mifareCard.writeBlock(block, new byte[16]);
            }

            this.mifareCard.writeBlock(firstBlockOfSector + 3, createResetFactorySectorTrailer());
        }
        this.mifareCard.close();
    }
}
