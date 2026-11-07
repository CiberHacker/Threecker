package com.ciber.threeker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Pure logic tests for the diff tool: they never touch NFC or Android APIs.
 */
public class DumpDiffTest {

    private static final byte[] UID = new byte[]{0x04, (byte) 0xAA, 0x01, 0x02};

    private static byte[] block(int... values) {
        byte[] block = new byte[Dump.BLOCK_SIZE];
        for (int i = 0; i < values.length; i++) {
            block[i] = (byte) values[i];
        }
        return block;
    }

    /**
     * @param blockTwoFirstByte differs between dumps to simulate a changed raw block.
     * @param balanceLow        low byte of the balance, 25 units per RUB.
     * @param balanceHigh       high byte of the balance.
     */
    private static Dump dump(int blockTwoFirstByte, int balanceLow, int balanceHigh) {
        return new Dump(UID, new byte[][]{
                block(0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x70),
                block(0x01, 0x02, 0x04, 0x00, 0x00, balanceLow, balanceHigh),
                block(blockTwoFirstByte),
                block()
        });
    }

    @Test
    public void compare_identicalDumpsReportNothingChanged() {
        Dump dump = dump(0x00, 0x01, 0xF4);
        List<DumpDiff.Row> rows = DumpDiff.compare(dump, dump);

        assertEquals(5 + Dump.BLOCK_COUNT, rows.size());
        assertEquals(0, DumpDiff.countChanged(rows));
        for (DumpDiff.Row row : rows) {
            assertTrue(row.isSame());
        }
    }

    @Test
    public void compare_detectsChangedBalance() {
        Dump a = dump(0x00, 0x00, 0x64); // 100 / 25 = 4 RUB
        Dump b = dump(0x00, 0x01, 0xF4); // 500 / 25 = 20 RUB
        List<DumpDiff.Row> rows = DumpDiff.compare(a, b);

        // the parsed balance and the raw block that carries it both differ
        assertEquals(2, DumpDiff.countChanged(rows));
        DumpDiff.Row balance = rows.get(DumpDiff.FIELD_BALANCE);
        assertEquals("4 RUB", balance.getValueA());
        assertEquals("20 RUB", balance.getValueB());
        assertFalse(balance.isSame());
    }

    @Test
    public void compare_detectsChangedBlock() {
        Dump a = dump(0x00, 0x01, 0xF4);
        Dump b = dump(0x7F, 0x01, 0xF4);
        List<DumpDiff.Row> rows = DumpDiff.compare(a, b);

        assertEquals(1, DumpDiff.countChanged(rows));
        DumpDiff.Row block = rows.get(5 + 2);
        assertEquals(DumpDiff.BLOCK_BASE + 2, block.getId());
        assertFalse(block.isSame());
    }

    @Test
    public void compare_coversEveryFieldAndEveryBlock() {
        List<DumpDiff.Row> rows = DumpDiff.compare(dump(0x00, 0x01, 0xF4), dump(0x00, 0x01, 0xF4));

        assertEquals(DumpDiff.FIELD_UID, rows.get(0).getId());
        assertEquals(DumpDiff.FIELD_CARD_NUMBER, rows.get(1).getId());
        assertEquals(DumpDiff.FIELD_BALANCE, rows.get(2).getId());
        assertEquals(DumpDiff.FIELD_LAST_USAGE, rows.get(3).getId());
        assertEquals(DumpDiff.FIELD_LAST_VALIDATOR, rows.get(4).getId());
        for (int i = 0; i < Dump.BLOCK_COUNT; i++) {
            assertEquals(DumpDiff.BLOCK_BASE + i, rows.get(5 + i).getId());
        }
    }
}
