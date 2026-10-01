package com.meethack.romhack.camp2026.wrappers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies isBlockWritable()/logicW() against the NXP MIFARE Classic
 * access-condition table (MF1S50yyX datasheet, sector trailer bytes 6-8).
 */
public class NfcWrapperAccessBitsTest {

    // (C1, C2, C3) -> is the data block writable with key A or key B.
    private static final int[][] WRITABLE_COMBINATIONS = {
            {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 1}
    };
    private static final int[][] NOT_WRITABLE_COMBINATIONS = {
            {0, 0, 1}, {0, 1, 0}, {1, 0, 1}, {1, 1, 1}
    };

    @Test
    public void logicW_matchesNxpTable() {
        for (int[] c : WRITABLE_COMBINATIONS) {
            assertTrue("C1=" + c[0] + " C2=" + c[1] + " C3=" + c[2] + " should be writable", NfcWrapper.logicW(c[0], c[1], c[2]));
        }
        for (int[] c : NOT_WRITABLE_COMBINATIONS) {
            assertFalse("C1=" + c[0] + " C2=" + c[1] + " C3=" + c[2] + " should NOT be writable", NfcWrapper.logicW(c[0], c[1], c[2]));
        }
    }

    @Test
    public void isBlockWritable_extractsCorrectBitsForEachDataBlock() {
        for (int blockNum = 0; blockNum <= 2; blockNum++) {
            for (int[] c : WRITABLE_COMBINATIONS) {
                byte[] trailer = buildTrailer(blockNum, c[0], c[1], c[2]);
                assertTrue("block " + blockNum + " with C1=" + c[0] + " C2=" + c[1] + " C3=" + c[2]
                        + " should be writable", NfcWrapper.isBlockWritable(trailer, blockNum));
            }
            for (int[] c : NOT_WRITABLE_COMBINATIONS) {
                byte[] trailer = buildTrailer(blockNum, c[0], c[1], c[2]);
                assertFalse("block " + blockNum + " with C1=" + c[0] + " C2=" + c[1] + " C3=" + c[2]
                        + " should NOT be writable", NfcWrapper.isBlockWritable(trailer, blockNum));
            }
        }
    }

    @Test
    public void isBlockWritable_onlyReadsBitsForTheRequestedBlock() {
        // Block 0 locked (111), block 1 open (000): the two must not interfere.
        byte[] trailer = buildTrailer(0, 1, 1, 1);
        setAccessBits(trailer, 1, 0, 0, 0);

        assertFalse(NfcWrapper.isBlockWritable(trailer, 0));
        assertTrue(NfcWrapper.isBlockWritable(trailer, 1));
    }

    /**
     * Builds a 16 byte sector trailer with the access bits (bytes 6-8) set so
     * that the given data block has the requested C1/C2/C3. The other two
     * data blocks are left at 0,0,0 (transport configuration, writable) and
     * block 3 (sector trailer) is irrelevant to isBlockWritable().
     */
    private static byte[] buildTrailer(int blockNum, int c1, int c2, int c3) {
        byte[] trailer = new byte[16];
        setAccessBits(trailer, blockNum, c1, c2, c3);
        return trailer;
    }

    /** Sets C1/C2/C3 for one block (0-3) inside the trailer's access-bit bytes 6-8. */
    private static void setAccessBits(byte[] trailer, int blockNum, int c1, int c2, int c3) {
        int byte7 = trailer[7] & 0xFF;
        int byte8 = trailer[8] & 0xFF;

        // Byte 7: C1_3 C1_2 C1_1 C1_0 | ~C3_3 ~C3_2 ~C3_1 ~C3_0  (bit 7..4 | bit 3..0)
        byte7 = setBit(byte7, 4 + blockNum, c1);
        byte7 = setBit(byte7, blockNum, c3 ^ 1);

        // Byte 8: C3_3 C3_2 C3_1 C3_0 | C2_3 C2_2 C2_1 C2_0  (bit 7..4 | bit 3..0)
        byte8 = setBit(byte8, 4 + blockNum, c3);
        byte8 = setBit(byte8, blockNum, c2);

        trailer[7] = (byte) byte7;
        trailer[8] = (byte) byte8;
    }

    private static int setBit(int value, int bitIndex, int bitValue) {
        if (bitValue != 0) {
            return value | (1 << bitIndex);
        }
        return value & ~(1 << bitIndex);
    }
}
