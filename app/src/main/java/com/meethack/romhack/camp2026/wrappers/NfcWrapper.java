package com.meethack.romhack.camp2026.wrappers;

import android.content.Context;
import android.nfc.tech.MifareClassic;

import com.meethack.romhack.camp2026.assets.Customer;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class NfcWrapper {
    private enum LOGGED {
        UNAUTHORIZED,
        DEFAULT_KEYS,
        COMPUTED_KEYS,
    }
    private final MifareClassic mifareCard;
    private final RequestWrapper requestWrapper;
    private static final int nameSector = 15;
    private static final int surnameSector = 14;
    private static final int creationDateSector = 13;
    private static final int amountSector = 12;
    private byte[][][] sectorKeys = new byte[16][2][Utils.keySize];
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

    private static final byte[] defaultValueBlock = new byte[]{
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, // Little endian signed 4 byte value
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,  // Inverted signed
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, // Little endian signed 4 byte value 2 time
            (byte)0x49, (byte)0xB6, (byte)0x49, (byte)0xB6 // Adr bytes
    };

    public NfcWrapper(MifareClassic mifareCard, Context context){
        this.mifareCard = mifareCard;
        this.requestWrapper = new RequestWrapper(context);
    }

    private LOGGED auth(int sector) throws IOException {
        this.fetchKeys();
        boolean authA;
        boolean authB;

        authA = this.mifareCard.authenticateSectorWithKeyA(sector, defaultKey);
        authB = this.mifareCard.authenticateSectorWithKeyB(sector, defaultKey);


        if (authA && authB){
            return LOGGED.DEFAULT_KEYS;
        }

        authA = this.mifareCard.authenticateSectorWithKeyA(sector, this.sectorKeys[sector][0]);
        authB = this.mifareCard.authenticateSectorWithKeyB(sector, this.sectorKeys[sector][1]);


        if (authA && authB){
            return LOGGED.COMPUTED_KEYS;
        }

        return LOGGED.UNAUTHORIZED;
    }

    private boolean isSectorKeysEmpty(){
        for (int sector = 0; sector < 16; sector++){
            for (int key = 0; key < 2; key++){
                for (int b = 0; b < Utils.keySize; b++){
                    if (this.sectorKeys[sector][key][b] != 0){
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public void fetchKeys() throws IOException {
        if (!this.isSectorKeysEmpty()) {
            return;
        }

        boolean authenticated = this.mifareCard.authenticateSectorWithKeyA(0, defaultKey);
        if (!authenticated) {
            throw new IOException("Could not authenticate sector 0 to read block 0");
        }
        byte[] block0 = this.mifareCard.readBlock(0);
        this.sectorKeys = this.requestWrapper.fetchKeys(block0);
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
        this.mifareCard.connect();
        List<SectorDump> dump = new ArrayList<>();
        int sectorCount = this.mifareCard.getSectorCount();

        for (int sector = 0; sector < sectorCount; sector++) {
            LOGGED authenticated = this.auth(sector);
            int firstBlockOfSector = this.mifareCard.sectorToBlock(sector);
            int blockCount = this.mifareCard.getBlockCountInSector(sector);
            byte[][] blocks = new byte[blockCount][];

            if (authenticated != LOGGED.UNAUTHORIZED) {
                for (int b = 0; b < blockCount; b++) {
                    blocks[b] = this.mifareCard.readBlock(firstBlockOfSector + b);
                }

                byte[] keyAB = defaultKey;
                byte[] keyBB = defaultKey;

                if (authenticated == LOGGED.COMPUTED_KEYS) {
                    keyAB = this.sectorKeys[sector][0];
                    keyBB = this.sectorKeys[sector][1];
                }

                System.arraycopy(keyAB, 0, blocks[3], 0, Utils.keySize);
                System.arraycopy(keyBB, 0, blocks[3], 10, Utils.keySize);
            }

            dump.add(new SectorDump(sector, authenticated != LOGGED.UNAUTHORIZED, blocks));
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

    public void writeRawBlocks(List<RawBlockEdit> edits) throws IOException {
        int last_auth = 0;
        LOGGED authenticated = this.auth(0);
        for (RawBlockEdit edit : edits) {
            if (edit.sector != last_auth){
                this.mifareCard.close();
                this.mifareCard.connect();
                authenticated = this.auth(edit.sector);
                last_auth = edit.sector;
            }

            if (authenticated == LOGGED.UNAUTHORIZED) {
                throw new IOException("Unauthorized, wrong keys");
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

    public void recharge(int amount) throws IOException {
        this.mifareCard.connect();
        this.auth(amountSector);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(amountSector);
        this.mifareCard.writeBlock(firstBlockOfSector + 3, createWritableSectorTrailer());
        this.mifareCard.increment(firstBlockOfSector, amount);
        this.mifareCard.transfer(firstBlockOfSector);
        this.mifareCard.writeBlock(firstBlockOfSector + 3, createSectorTrailer(amountSector));
        this.mifareCard.close();
    }

    public void buy(int amount) throws IOException {
        this.mifareCard.connect();
        this.auth(amountSector);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(amountSector);
        this.mifareCard.writeBlock(firstBlockOfSector + 3, createWritableSectorTrailer());
        this.mifareCard.decrement(firstBlockOfSector, amount);
        this.mifareCard.transfer(firstBlockOfSector);
        this.mifareCard.writeBlock(firstBlockOfSector + 3, createSectorTrailer(amountSector));
        this.mifareCard.close();
    }

    public int readAmount() throws IOException {
        this.mifareCard.connect();
        this.auth(amountSector);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(amountSector);
        byte[] block = this.mifareCard.readBlock(firstBlockOfSector);
        this.mifareCard.close();
        return ByteBuffer.wrap(block, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private void writeName(String name) throws IOException {
       this.auth(nameSector);
        byte[] nameB = Utils.stringToHex(name);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(nameSector);

         for (int a = 0; a < 3; a++) {
             byte[] chunk = Arrays.copyOfRange(nameB, 16 * a, 16 * a + 16);
             this.mifareCard.writeBlock(firstBlockOfSector + a, chunk);
         }
    }

    private void writeSurname(String surname) throws IOException {
        this.auth(surnameSector);
        byte[] nameB = Utils.stringToHex(surname);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(surnameSector);

        for (int a = 0; a < 3; a++) {
            byte[] chunk = Arrays.copyOfRange(nameB, 16 * a, 16 * a + 16);
            this.mifareCard.writeBlock(firstBlockOfSector + a, chunk);
        }
    }

    private void writeCreationDate(Calendar creationDate) throws IOException {
        this.auth(creationDateSector);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(creationDateSector);
        this.mifareCard.writeBlock(firstBlockOfSector, defaultValueBlock);
        this.mifareCard.increment(firstBlockOfSector, creationDate.get(Calendar.YEAR));
        this.mifareCard.transfer(firstBlockOfSector++);
        this.mifareCard.writeBlock(firstBlockOfSector, defaultValueBlock);
        this.mifareCard.increment(firstBlockOfSector, creationDate.get(Calendar.MONTH) + 1);
        this.mifareCard.transfer(firstBlockOfSector++);
        this.mifareCard.writeBlock(firstBlockOfSector, defaultValueBlock);
        this.mifareCard.increment(firstBlockOfSector, creationDate.get(Calendar.DAY_OF_MONTH));
        this.mifareCard.transfer(firstBlockOfSector);
    }

    private void writeAmount(int amount) throws IOException {
        this.auth(amountSector);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(amountSector);
        this.mifareCard.writeBlock(firstBlockOfSector, defaultValueBlock);
        this.mifareCard.increment(firstBlockOfSector, amount);
        this.mifareCard.transfer(firstBlockOfSector);
    }

    private byte[] createSectorTrailer(int sector) {
        byte[] chunk = new byte[16];
        System.arraycopy(this.sectorKeys[sector][0], 0, chunk, 0, Utils.keySize);
        System.arraycopy(accessBits, 0, chunk, 6, 4);
        System.arraycopy(this.sectorKeys[sector][1], 0, chunk, 10, Utils.keySize);

        return chunk;
    }

    private byte[] createResetFactorySectorTrailer(){
        byte[] chunk = new byte[16];

        System.arraycopy(defaultKey, 0, chunk, 0, Utils.keySize);
        System.arraycopy(resetFactoryAccessBits, 0, chunk, 6, 4);
        System.arraycopy(defaultKey, 0, chunk, 10, Utils.keySize);

        return chunk;
    }

    private byte[] createWritableSectorTrailer(){
        byte[] chunk = new byte[16];
        System.arraycopy(defaultKey, 0, chunk, 0, Utils.keySize);
        System.arraycopy(writableAccessBits, 0, chunk, 6, 4);
        System.arraycopy(defaultKey, 0, chunk, 10, Utils.keySize);
        return chunk;
    }

    private void rewriteAccessBits() throws IOException {
        for (int a = 1; a < 16; a++){
            this.auth(a);
            int trailerBlockOfSector = this.mifareCard.sectorToBlock(a) + 3;
            this.mifareCard.writeBlock(trailerBlockOfSector, createSectorTrailer(a));
        }
    }

    public void format() throws IOException {
        this.mifareCard.connect();
        for (int a = 0; a < 16; a++){
            this.auth(a);
            int firstBlockOfSector = this.mifareCard.sectorToBlock(a);
            this.mifareCard.writeBlock(firstBlockOfSector + 3, createResetFactorySectorTrailer());

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
