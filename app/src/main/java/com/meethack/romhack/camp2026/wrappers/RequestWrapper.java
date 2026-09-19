package com.meethack.romhack.camp2026.wrappers;

import android.content.Context;
import android.util.Log;

import com.meethack.romhack.camp2026.R;
import com.meethack.romhack.camp2026.exceptions.FailedToFetchKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class RequestWrapper {
    private static final String TAG = "RequestWrapper";
    private final Context context;


    public RequestWrapper(Context context){
        this.context = context;
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

    public byte[][][] fetchKeys(byte[] block0) throws FailedToFetchKeys {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("uid", Utils.bytesToHex(block0));

            String endpoint = this.context.getString(R.string.keys_endpoint);
            String headerName = this.context.getString(R.string.keys_header_name);
            String headerValue = this.context.getString(R.string.keys_header_value);

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            if (connection instanceof HttpsURLConnection) {
                // The ALB serves a self-signed cert with no SAN, issued for cyber-car-washer.local
                // rather than the ALB's own hostname, so the platform trust store rejects it and
                // hostname verification would too. Pin the exact leaf certificate instead of
                // trusting/verifying nothing.
                this.pinServerCertificate((HttpsURLConnection) connection);
            }
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
                byte[][][] sectorKeys = new byte[16][2][Utils.keySize];

                for (int a = 0; a < keys.length(); a++){
                    long keyA = keys.getJSONObject(a).getLong("key_a");
                    long keyB = keys.getJSONObject(a).getLong("key_b");
                    byte[] _keyAB = ByteBuffer.allocate(8).putLong(keyA).array();
                    byte[] _keyBB = ByteBuffer.allocate(8).putLong(keyB).array();
                    byte[] keyAB = new byte[Utils.keySize];
                    byte[] keyBB = new byte[Utils.keySize];
                    System.arraycopy(_keyAB, 2, keyAB, 0, Utils.keySize);
                    System.arraycopy(_keyBB, 2, keyBB, 0, Utils.keySize);
                    sectorKeys[a][0] = keyAB;
                    sectorKeys[a][1] = keyBB;
                }

                return sectorKeys;

        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new FailedToFetchKeys("Could not fetch the keys");
        } finally {
            if (connection != null){
                connection.disconnect();
            }
        }
    }

    /**
     * Trusts only the ALB's exact leaf certificate (bundled at res/raw/cyber_car_washer_cert.pem),
     * instead of the platform CA store. The pinned cert has no SAN, so identity here comes from
     * matching the exact certificate rather than from hostname verification - the hostname check
     * is disabled deliberately, not skipped by accident.
     */
    private void pinServerCertificate(HttpsURLConnection connection) throws IOException {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate pinnedCertificate;
            try (InputStream certStream = this.context.getResources().openRawResource(R.raw.cyber_car_washer_cert)) {
                pinnedCertificate = certificateFactory.generateCertificate(certStream);
            }

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("cyber-car-washer-alb", pinnedCertificate);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> true);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
