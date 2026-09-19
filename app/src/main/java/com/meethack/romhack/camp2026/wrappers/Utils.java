package com.meethack.romhack.camp2026.wrappers;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Utils {
    public static final int keySize = 6;

    public static String bytesToHex(byte[] bytes) {
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

    public static int[] byte2ArrayBits(byte b){
        int[] bits = new int[8];
        String binary = String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0');

        for (int a = 0; a < bits.length; a++){
            bits[a] = binary.charAt(a) - '0';
        }

        return bits;
    }
}
