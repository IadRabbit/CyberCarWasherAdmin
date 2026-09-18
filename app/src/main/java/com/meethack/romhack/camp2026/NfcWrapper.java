package com.meethack.romhack.camp2026;

import android.content.Context;
import android.nfc.tech.MifareClassic;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class NfcWrapper {
    private enum LOGGED {
        UNAUTHORIZED,
        DEFAULT_KEYS,
        COMPUTED_KEYS,
    }
    private final MifareClassic mifareCard;
    private final Context context;
    private static final int keySize = 6;
    private static final int nameSector = 15;
    private static final int surnameSector = 14;
    private static final int creationDateSector = 13;
    private static final int amountSector = 12;
    private final byte[][][] sectorKeys = new byte[16][2][6];
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
        this.context = context;
    }

    private boolean isSectorKeysEmpty(){
        for (int sector = 0; sector < 16; sector++){
            for (int key = 0; key < 2; key++){
                for (int b = 0; b < keySize; b++){
                    if (this.sectorKeys[sector][key][b] != 0){
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /** POSTs the tag's first block to the configured endpoint and returns {keyA, keyB}. Must be called off the main thread. */
    public void fetchKeys() throws IOException {
        if (!this.isSectorKeysEmpty()) {
            return;
        }

        byte[] block0;
        boolean authenticated = this.mifareCard.authenticateSectorWithKeyA(0, defaultKey);
        if (!authenticated) {
            throw new IOException("Could not authenticate sector 0 to read block 0");
        }
        block0 = this.mifareCard.readBlock(0);

        JSONObject payload = new JSONObject();
        try {
            payload.put("uid", bytesToHex(block0));
        } catch (JSONException e) {
            throw new IOException(e);
        }

        String endpoint = this.context.getString(R.string.keys_endpoint);
        String headerName = this.context.getString(R.string.keys_header_name);
        String headerValue = this.context.getString(R.string.keys_header_value);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        if (connection instanceof HttpsURLConnection) {
            // Mirrors the reference client's verify=False: the ALB currently serves a cert
            // the platform doesn't trust. Do not ship this without fixing the cert instead.
            disableTlsVerification((HttpsURLConnection) connection);
        }
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty(headerName, headerValue);

            try (OutputStream body = connection.getOutputStream()) {
                body.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            InputStream responseStream = (status >= 200 && status < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readAll(responseStream);

            if (status < 200 || status >= 300) {
                throw new IOException("fetchKeys failed: HTTP " + status + " - " + responseBody);
            }

            JSONArray keys = new JSONObject(responseBody).getJSONArray("keys");

            for (int a = 0; a < keys.length(); a++){
                long keyA = keys.getJSONObject(a).getLong("key_a");
                long keyB = keys.getJSONObject(a).getLong("key_b");
                byte[] _keyAB = ByteBuffer.allocate(8).putLong(keyA).array();
                byte[] _keyBB = ByteBuffer.allocate(8).putLong(keyB).array();
                byte[] keyAB = new byte[keySize];
                byte[] keyBB = new byte[keySize];
                System.arraycopy(_keyAB, 2, keyAB, 0, keySize);
                System.arraycopy(_keyBB, 2, keyBB, 0, keySize);
                this.sectorKeys[a][0] = keyAB;
                this.sectorKeys[a][1] = keyBB;
            }

        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            connection.disconnect();
        }
    }

    private static void disableTlsVerification(HttpsURLConnection connection) throws IOException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            connection.setSSLSocketFactory(socketFactory);
            connection.setHostnameVerifier((hostname, session) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException(e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
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

    public List<SectorDump> dumpSectors() throws IOException, JSONException {
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

                System.arraycopy(keyAB, 0, blocks[3], 0, keySize);
                System.arraycopy(keyBB, 0, blocks[3], 10, keySize);
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

    /** Writes back arbitrary blocks as-is, no interpretation of the bytes' meaning. */
    public void writeRawBlocks(List<RawBlockEdit> edits) throws IOException {
        int last_auth = -1;
        LOGGED authenticated = LOGGED.UNAUTHORIZED;
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

    public void saveData(Customer customer) throws IOException, JSONException {
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

    /** Reads the current balance without changing it. */
    public int readAmount() throws IOException {
        this.mifareCard.connect();
        this.auth(amountSector);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(amountSector);
        byte[] block = this.mifareCard.readBlock(firstBlockOfSector);
        this.mifareCard.close();
        return ByteBuffer.wrap(block, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
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

    private void writeName(String name) throws IOException, JSONException {
       this.auth(nameSector);
        byte[] nameB = NfcWrapper.stringToHex(name);
        int firstBlockOfSector = this.mifareCard.sectorToBlock(nameSector);

         for (int a = 0; a < 3; a++) {
             byte[] chunk = Arrays.copyOfRange(nameB, 16 * a, 16 * a + 16);
             this.mifareCard.writeBlock(firstBlockOfSector + a, chunk);
         }
    }

    private void writeSurname(String surname) throws IOException {
        this.auth(surnameSector);
        byte[] nameB = NfcWrapper.stringToHex(surname);
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
        System.arraycopy(this.sectorKeys[sector][0], 0, chunk, 0, keySize);
        System.arraycopy(accessBits, 0, chunk, 6, 4);
        System.arraycopy(this.sectorKeys[sector][1], 0, chunk, 10, keySize);

        return chunk;
    }

    private byte[] createResetFactorySectorTrailer(){
        byte[] chunk = new byte[16];

        System.arraycopy(defaultKey, 0, chunk, 0, keySize);
        System.arraycopy(resetFactoryAccessBits, 0, chunk, 6, 4);
        System.arraycopy(defaultKey, 0, chunk, 10, keySize);

        return chunk;
    }

    private byte[] createWritableSectorTrailer(){
        byte[] chunk = new byte[16];
        System.arraycopy(writableAccessBits, 0, chunk, 6, 4);
        return chunk;
    }

    private void rewriteAccessBits() throws IOException {
        for (int a = 1; a < 16; a++){
            this.auth(a);
            int trailerBlockOfSector = this.mifareCard.sectorToBlock(a) + 3;
            this.mifareCard.writeBlock(trailerBlockOfSector, createSectorTrailer(a));
        }
    }

    public void format() throws IOException, JSONException {
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
