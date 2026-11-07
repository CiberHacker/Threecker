package com.ciber.threeker.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class HexUtilsTest {

    @Test
    public void toString_formatsBytesAsUpperCaseHex() {
        byte[] raw = new byte[]{(byte) 0x00, (byte) 0x01, (byte) 0xAB, (byte) 0xFF};
        assertEquals("0001ABFF", HexUtils.toString(raw));
    }

    @Test
    public void toString_handlesNegativeBytes() {
        assertEquals("80FF", HexUtils.toString(new byte[]{(byte) 0x80, (byte) 0xFF}));
    }

    @Test
    public void toString_handlesEmptyArray() {
        assertEquals("", HexUtils.toString(new byte[0]));
    }

    @Test
    public void fromString_parsesHexInAnyCase() {
        assertArrayEquals(new byte[]{(byte) 0x01, (byte) 0xAB}, HexUtils.fromString("01aB"));
    }

    @Test
    public void fromString_rejectsOddLength() {
        try {
            HexUtils.fromString("ABC");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void roundTrip_keepsValues() {
        String hex = "E35173494A81";
        assertEquals(hex, HexUtils.toString(HexUtils.fromString(hex)));
    }
}
