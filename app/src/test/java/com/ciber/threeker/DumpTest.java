package com.ciber.threeker;

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
import java.util.Date;
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

        String written = new String(Files.readAllBytes(first.toPath()), StandardCharsets.UTF_8);
        assertTrue(written.startsWith(DumpFile.MAGIC));
        assertEquals(DumpFile.serialize(dump), written);

        Dump reloaded = Dump.fromFile(second);
        assertEquals(dump.getUidAsString(), reloaded.getUidAsString());
        assertArrayEquals(dump.getDataAsStrings(), reloaded.getDataAsStrings());
    }

    @Test
    public void saveCopy_keepsNameDescriptionAndScanTime() throws IOException {
        dump.setName("School card");
        dump.setDescription("bought at the kiosk");
        Date scanned = new Date(1762430000000L);
        dump.setScannedAt(scanned);

        File copy = dump.saveCopy(temporaryFolder.newFolder());
        Dump reloaded = Dump.fromFile(copy);

        assertEquals("School card", reloaded.getName());
        assertEquals("bought at the kiosk", reloaded.getDescription());
        // the file stores the timestamp with second resolution
        assertEquals(scanned.getTime() / 1000, reloaded.getScannedAt().getTime() / 1000);
    }

    @Test
    public void saveCopy_keepsTheScannedBlocksOfAnEditedDump() throws IOException {
        String[] scanned = dump.getDataAsStrings();
        assertFalse("a fresh dump is not edited", dump.isEdited());

        dump.setBalance(500);
        assertTrue(dump.isEdited());

        File copy = dump.saveCopy(temporaryFolder.newFolder());
        Dump reloaded = Dump.fromFile(copy);

        assertTrue(reloaded.isEdited());
        assertEquals(500, reloaded.getBalance());
        assertArrayEquals(dump.getDataAsStrings(), reloaded.getDataAsStrings());
        assertArrayEquals(scanned, reloaded.getOriginalAsStrings());
    }

    @Test
    public void revertToOriginal_restoresTheScannedBlocks() {
        String[] scanned = dump.getDataAsStrings();
        dump.setBalance(500);

        dump.revertToOriginal();

        assertFalse(dump.isEdited());
        assertArrayEquals(scanned, dump.getDataAsStrings());
        assertEquals(20, dump.getBalance());
    }

    @Test
    public void fromFile_readsTheLegacyFiveLineFormat() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = writeText(dir, "legacy.txt", legacyText());

        Dump legacy = Dump.fromFile(file);

        assertEquals(dump.getUidAsString(), legacy.getUidAsString());
        assertArrayEquals(dump.getDataAsStrings(), legacy.getDataAsStrings());
        assertNull(legacy.getName());
        assertFalse(legacy.isEdited());
    }

    @Test
    public void fromFile_takesTheScanTimeOfALegacyFileFromItsName() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = writeText(dir, "2026-11-03_135522_19088743_20RUB.txt", legacyText());

        Dump legacy = Dump.fromFile(file);

        Calendar expected = Calendar.getInstance();
        expected.set(2026, Calendar.NOVEMBER, 3, 13, 55, 22);
        expected.set(Calendar.MILLISECOND, 0);
        assertEquals(expected.getTimeInMillis(), legacy.getScannedAt().getTime());
    }

    @Test
    public void fromFile_rejectsATruncatedLegacyFile() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = writeText(dir, "short.txt",
                "0446855A594D81\r\n" + dump.getDataAsStrings()[0] + "\r\n");
        try {
            Dump.fromFile(file);
            assertTrue("Expected IOException", false);
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void fromFile_rejectsAVersionedFileWithoutBlocks() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = writeText(dir, "bad.txt", DumpFile.MAGIC + "\nuid: 0446855A594D81\n");
        try {
            Dump.fromFile(file);
            assertTrue("Expected IOException", false);
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void updateDetails_keepsTheBlocksAndStoresTheText() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);
        String block = dump.getDataAsStrings()[1];

        Dump.updateDetails(file, "School card", "line 1\nline 2 \\ done");

        Dump reloaded = Dump.fromFile(file);
        assertEquals("School card", reloaded.getName());
        assertEquals("line 1\nline 2 \\ done", reloaded.getDescription());
        assertEquals(block, reloaded.getDataAsStrings()[1]);
        assertEquals(dump.getUidAsString(), reloaded.getUidAsString());
    }

    @Test
    public void write_replacesTheFileWithoutLeavingTemporaryFiles() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);

        dump.setBalance(777);
        DumpFile.write(dump, file);

        File[] files = dir.listFiles();
        assertEquals(1, files.length);
        assertEquals(file.getName(), files[0].getName());
        assertEquals(777, Dump.fromFile(file).getBalance());
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

    @Test
    public void restoreBalance_onlyTouchesTheBalance() {
        String[] scanned = dump.getDataAsStrings();
        dump.setBalance(900);

        assertTrue(dump.isBalanceChanged());
        assertFalse(dump.isLastPassChanged());

        assertTrue(dump.restoreBalance());

        assertEquals(20, dump.getBalance());
        assertFalse(dump.isBalanceChanged());
        assertArrayEquals(scanned, dump.getDataAsStrings());
    }

    @Test
    public void restoreLastPass_coversUsageAndValidatorTogether() {
        String[] scanned = dump.getDataAsStrings();
        int validator = dump.getLastValidatorId();
        Calendar picked = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        picked.set(2026, Calendar.NOVEMBER, 6, 12, 34, 0);

        dump.setLastUsageDate(picked.getTime());

        assertTrue(dump.isLastPassChanged());
        assertFalse(dump.isBalanceChanged());

        assertTrue(dump.restoreLastPass());

        assertFalse(dump.isLastPassChanged());
        assertEquals(validator, dump.getLastValidatorId());
        assertArrayEquals(scanned, dump.getDataAsStrings());
    }

    @Test
    public void saveChanges_afterAUsageEdit_keepsTheScannedBlocks() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);
        Dump loaded = Dump.fromFile(file);
        assertFalse(loaded.isEdited());

        Calendar picked = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        picked.set(2022, Calendar.JUNE, 22, 12, 5, 0);
        loaded.setLastUsageDate(picked.getTime());

        DumpFile.write(loaded, file);

        assertTrue(loaded.isEdited());
        assertTrue(loaded.isLastPassChanged());
        assertTrue(readText(file).contains("original0:"));
        assertTrue(Dump.fromFile(file).isEdited());
    }

    @Test
    public void restoringEveryFieldLeavesNoOriginalBlocksInTheFile() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);

        dump.setBalance(500);
        DumpFile.write(dump, file);
        assertTrue(readText(file).contains("original0:"));

        dump.restoreBalance();
        DumpFile.write(dump, file);

        assertFalse(dump.isEdited());
        assertFalse(readText(file).contains("original0:"));
    }

    private static byte[] sector9Block(int seed) {
        byte[] block = new byte[Dump.BLOCK_SIZE];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) (seed + i);
        }
        return block;
    }

    @Test
    public void newDump_hasNoSector9() {
        assertNull(dump.getSector9Data());
        assertFalse(dump.hasSector9());
        assertNull(dump.getSector9AsStrings());
    }

    @Test
    public void sector9_roundTripsThroughTheFile() throws IOException {
        File dir = temporaryFolder.newFolder();
        dump.setSector9Data(new byte[][]{sector9Block(1), sector9Block(2), sector9Block(3)});
        File file = dump.saveCopy(dir);

        Dump loaded = Dump.fromFile(file);
        assertTrue(loaded.hasSector9());
        assertArrayEquals(dump.getSector9Data()[0], loaded.getSector9Data()[0]);
        assertArrayEquals(dump.getSector9Data()[2], loaded.getSector9Data()[2]);
        String text = readText(file);
        assertTrue(text.contains("sector9_block0:"));
        assertTrue(text.contains("sector9_block2:"));
    }

    @Test
    public void dumpWithoutSector9_staysSector8Only() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);

        assertFalse(readText(file).contains("sector9_block"));
        assertNull(Dump.fromFile(file).getSector9Data());
    }

    @Test
    public void incompleteSector9_isRejected() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);
        File broken = writeText(dir, "incomplete.txt",
                readText(file) + "sector9_block0: 000102030405060708090A0B0C0D0E0F\n");
        try {
            Dump.fromFile(broken);
            assertTrue("Expected IOException", false);
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void wrongSizeSector9_isRejected() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = dump.saveCopy(dir);
        File broken = writeText(dir, "wrongsize.txt", readText(file)
                + "sector9_block0: 00010203\n"
                + "sector9_block1: 000102030405060708090A0B0C0D0E0F\n"
                + "sector9_block2: 000102030405060708090A0B0C0D0E0F\n");
        try {
            Dump.fromFile(broken);
            assertTrue("Expected IOException", false);
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void sector9_survivesBalanceEdits() throws IOException {
        File dir = temporaryFolder.newFolder();
        dump.setSector9Data(new byte[][]{sector9Block(1), sector9Block(2), sector9Block(3)});
        File file = dump.saveCopy(dir);
        Dump loaded = Dump.fromFile(file);

        loaded.setBalance(900);
        DumpFile.write(loaded, file);

        Dump again = Dump.fromFile(file);
        assertTrue(again.isEdited());
        assertTrue(again.hasSector9());
        assertEquals(900, again.getBalance());
        assertArrayEquals(dump.getSector9Data()[1], again.getSector9Data()[1]);
    }

    /** Reads a file as UTF-8 text. */
    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Writes a text file exactly as given, used for legacy and broken dumps. */
    private static File writeText(File dir, String name, String text) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** The five-line format of older builds: UID, then blocks 0..3. */
    private String legacyText() {
        String[] blocks = dump.getDataAsStrings();
        return dump.getUidAsString() + "\r\n"
                + blocks[0] + "\r\n" + blocks[1] + "\r\n"
                + blocks[2] + "\r\n" + blocks[3] + "\r\n";
    }
}
