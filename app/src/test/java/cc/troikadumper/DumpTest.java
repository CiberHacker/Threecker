package cc.troikadumper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Pure logic tests: they never touch NFC or Android APIs, so they run on the JVM.
 */
public class DumpTest {

    private static final byte[] UID = new byte[]{0x04, (byte) 0xAA, 0x01, 0x02};

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Dump dump = new Dump(UID, new byte[][]{
            block(0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x70),
            block(0x01, 0x02, 0x04, 0x00, 0x00, 0x01, 0xF4),
            block(),
            block()
    });

    private static byte[] block(int... values) {
        byte[] block = new byte[Dump.BLOCK_SIZE];
        for (int i = 0; i < values.length; i++) {
            block[i] = (byte) values[i];
        }
        return block;
    }

    @Test
    public void intval_buildsBigEndianValue() {
        assertEquals(258, Dump.intval((byte) 0x01, (byte) 0x02));
        assertEquals(255, Dump.intval((byte) 0xFF));
        assertEquals(0x12345670, Dump.intval((byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x70));
    }

    @Test
    public void formatCardNumber_groupsDigits() {
        assertEquals("0019 088 743", Dump.formatCardNumber(19088743));
        assertEquals("0000 000 000", Dump.formatCardNumber(0));
        // the first group is (number / 1000000) % 1000, so bigger numbers wrap around
        assertEquals("0234 567 890", Dump.formatCardNumber(1234567890));
    }

    @Test
    public void parse_extractsCardNumberFromFirstBlock() {
        assertEquals(19088743, dump.getCardNumber());
        assertEquals("0019 088 743", dump.getCardNumberAsString());
    }

    @Test
    public void parse_extractsBalanceFromSecondBlock() {
        assertEquals(20, dump.getBalance());
        assertEquals("20 RUB", dump.getBalanceAsString());
    }

    @Test
    public void parse_extractsValidatorIdAndMarksCardAsUsed() {
        assertEquals(258, dump.getLastValidatorId());
        assertEquals("ID# 258", dump.getLastValidatorIdAsString());
        assertTrue(dump.getLastUsageDate() != null);
    }

    @Test
    public void parse_marksUnusedCardAsNeverUsed() {
        Dump unused = new Dump(UID, new byte[][]{block(), block(), block(), block()});
        assertNull(unused.getLastUsageDate());
        assertEquals("<NEVER USED>", unused.getLastUsageDateAsString());
    }

    @Test
    public void parse_extractsUid() {
        assertEquals("04AA0102", dump.getUidAsString());
    }

    @Test
    public void makeFilename_matchesFilenameRegexp() {
        String filename = dump.makeFilename();
        assertTrue(filename, Pattern.matches(Dump.FILENAME_REGEXP, filename));
    }

    @Test
    public void filenameRegexp_matchesGeneratedNamesOnly() {
        assertFalse(Pattern.matches(Dump.FILENAME_REGEXP, "notes.txt"));
        assertFalse(Pattern.matches(Dump.FILENAME_REGEXP, "2026-11-03_130000_1_55RUB.json"));
        assertFalse(Pattern.matches(Dump.FILENAME_REGEXP, "2026-1-03_130000_1_55RUB.txt"));
        assertTrue(Pattern.matches(Dump.FILENAME_REGEXP, "2026-11-03_130000_1234567890_55RUB.txt"));
        // card number and balance are "one or more digits", so short values are valid too
        assertTrue(Pattern.matches(Dump.FILENAME_REGEXP, "2026-11-03_130000_1_5RUB.txt"));
    }

    @Test
    public void toJson_containsParsedValuesAndBlocks() {
        String json = dump.toJson();
        assertTrue(json, json.contains("\"uid\": \"04AA0102\""));
        assertTrue(json, json.contains("\"balance\": 20"));
        assertTrue(json, json.contains("\"lastValidatorId\": 258"));
        assertTrue(json, json.contains("\"blocks\": ["));
    }

    @Test
    public void toText_containsParsedValuesAndBlocks() {
        String text = dump.toText();
        assertTrue(text, text.contains("Card UID:       04AA0102"));
        assertTrue(text, text.contains("Balance:        20 RUB"));
        assertTrue(text, text.contains("0] 00000012345670000000000000000000"));
    }

    @Test
    public void setBalance_updatesParsedFieldAndRawBlockData() {
        dump.setBalance(100);
        assertEquals(100, dump.getBalance());
        assertEquals("100 RUB", dump.getBalanceAsString());
        // balance * 25 = 2500 = 0x09C4 -> data[1][5] = 0x09, data[1][6] = 0xC4
        assertEquals(0x09, dump.getData()[1][5] & 0xFF);
        assertEquals(0xC4, dump.getData()[1][6] & 0xFF);
    }

    @Test
    public void setBalance_zeroEncodesAsZeroBytes() {
        dump.setBalance(0);
        assertEquals(0, dump.getBalance());
        assertEquals(0x00, dump.getData()[1][5] & 0xFF);
        assertEquals(0x00, dump.getData()[1][6] & 0xFF);
    }

    @Test
    public void setBalance_negativeThrows() {
        try {
            dump.setBalance(-1);
            assertTrue("Expected IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void setLastValidatorId_updatesRawBytesAndParsedValue() {
        dump.setLastValidatorId(0x1234);

        assertEquals(0x1234, dump.getLastValidatorId());
        // The validator is the high 16 bits of block #1, bytes 0..2.
        assertEquals(0x12, dump.getData()[1][0] & 0xFF);
        assertEquals(0x34, dump.getData()[1][1] & 0xFF);
    }

    @Test
    public void setLastValidatorId_rejectsOutOfRangeValues() {
        try {
            dump.setLastValidatorId(-1);
            assertTrue("Expected IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            dump.setLastValidatorId(0x10000);
            assertTrue("Expected IllegalArgumentException", false);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void setLastUsageDate_roundTripsThroughThePackedField() {
        Calendar picked = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        picked.set(2026, Calendar.NOVEMBER, 6, 12, 34, 0);
        picked.set(Calendar.MILLISECOND, 0);

        dump.setLastUsageDate(picked.getTime());

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT+3"));
        assertEquals("2026-11-06 12:34", format.format(dump.getLastUsageDate()));
    }

    @Test
    public void setLastUsageDate_andValidatorShareOneField() {
        int before = dump.getLastValidatorId();
        Calendar picked = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        picked.set(2026, Calendar.NOVEMBER, 6, 12, 34, 0);

        dump.setLastUsageDate(picked.getTime());

        // The encoded value is (minutes << 1) | flag, so the 16 high bits are the validator.
        int value = Dump.intval(dump.getData()[1][0], dump.getData()[1][1], dump.getData()[1][2]);
        assertEquals(value >> 8, dump.getLastValidatorId());
        // ...and the time edit necessarily moves the validator as well.
        assertFalse("validator should follow the packed field", before == dump.getLastValidatorId());
    }

    @Test
    public void filenameFor_usesTheDumpFilenamePattern() {
        Calendar when = Calendar.getInstance();
        when.set(2026, Calendar.NOVEMBER, 6, 15, 4, 5);
        when.set(Calendar.MILLISECOND, 0);

        String filename = dump.filenameFor(when.getTime());

        assertEquals("2026-11-06_150405_19088743_20RUB.txt", filename);
        assertTrue(filename.matches(Dump.FILENAME_REGEXP));
    }

    @Test
    public void saveCopy_writesASeparateFileWithTheSameContent() throws IOException {
        File dir = temporaryFolder.newFolder();

        File first = dump.saveCopy(dir);
        File second = dump.saveCopy(dir);

        assertTrue(first.isFile());
        assertTrue(second.isFile());
        assertNotEquals("a copy must never overwrite an existing dump",
                first.getName(), second.getName());
        assertTrue(first.getName().matches(Dump.FILENAME_REGEXP));

        String[] blocks = dump.getDataAsStrings();
        assertEquals(dump.getUidAsString() + "\r\n"
                        + blocks[0] + "\r\n" + blocks[1] + "\r\n"
                        + blocks[2] + "\r\n" + blocks[3] + "\r\n",
                new String(Files.readAllBytes(first.toPath()), StandardCharsets.US_ASCII));

        Dump reloaded = Dump.fromFile(second);
        assertEquals(dump.getUidAsString(), reloaded.getUidAsString());
        assertArrayEquals(blocks, reloaded.getDataAsStrings());
    }

    @Test
    public void saveCopy_rejectsADirectoryThatDoesNotExist() {
        try {
            dump.saveCopy(new File(temporaryFolder.getRoot(), "missing"));
            assertTrue("Expected IOException", false);
        } catch (IOException e) {
            // expected
        }
    }
}
