package com.ciber.threeker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.ciber.threeker.utils.DumpMetadata;
import com.ciber.threeker.utils.HexUtils;

/**
 * The dumps list must be driven by the dump files themselves: bytes, name, description and scan
 * time all come from the file, and the file name is never interpreted.
 */
public class DumpGroupingTest {

    private static final String UID_HEX = "0446855A594D81";
    private static final int CARD_A = 111;
    private static final int CARD_B = 222;
    private static final long MINUTE = 60000L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Encodes the card number and the balance exactly like {@link Dump#parse()} expects them. */
    private static byte[][] blocks(int cardNumber, int balance) {
        byte[] block0 = new byte[Dump.BLOCK_SIZE];
        int cardValue = cardNumber << 4;
        block0[3] = (byte) (cardValue >>> 24);
        block0[4] = (byte) (cardValue >>> 16);
        block0[5] = (byte) (cardValue >>> 8);
        block0[6] = (byte) cardValue;

        byte[] block1 = new byte[Dump.BLOCK_SIZE];
        int money = balance * 25;
        block1[5] = (byte) (money >> 8);
        block1[6] = (byte) money;

        return new byte[][]{block0, block1, new byte[Dump.BLOCK_SIZE], new byte[Dump.BLOCK_SIZE]};
    }

    private static Dump dump(int cardNumber, int balance, long scannedAt) {
        Dump dump = new Dump(HexUtils.fromString(UID_HEX), blocks(cardNumber, balance));
        dump.setScannedAt(new Date(scannedAt));
        return dump;
    }

    private static DumpGrouping.Entry entry(String filename, int cardNumber, long scannedAt) {
        return new DumpGrouping.Entry(filename, dump(cardNumber, 55, scannedAt), scannedAt);
    }

    /** Writes a versioned dump; the file name is deliberately unrelated to the content. */
    private static File writeDump(File dir, String filename, int cardNumber, int balance,
                                  long scannedAt) throws IOException {
        File file = new File(dir, filename);
        DumpFile.write(dump(cardNumber, balance, scannedAt), file);
        return file;
    }

    /** Writes the five-line format of older builds. */
    private static File writeLegacy(File dir, String filename, int cardNumber, int balance)
            throws IOException {
        byte[][] blocks = blocks(cardNumber, balance);
        StringBuilder text = new StringBuilder(UID_HEX).append("\r\n");
        for (byte[] block : blocks) {
            text.append(HexUtils.toString(block)).append("\r\n");
        }
        File file = new File(dir, filename);
        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    @Test
    public void read_takesTheCardNumberAndBalanceFromTheBytesNotTheName() throws IOException {
        File dir = temporaryFolder.newFolder();
        writeDump(dir, "2020-01-01_000000_999_1RUB.txt", 74565, 20, 1000L);

        List<DumpGrouping.Entry> entries = DumpGrouping.read(dir);

        assertEquals(1, entries.size());
        DumpGrouping.Entry entry = entries.get(0);
        assertTrue(entry.isReadable());
        assertEquals(74565, entry.getCardNumber());
        assertEquals(20, entry.getBalance());
        assertEquals(1000L, entry.getScannedAt());
        assertEquals(1000L, entry.getTimestamp());
    }

    @Test
    public void read_keepsTheNameAndDescriptionFromTheFile() throws IOException {
        File dir = temporaryFolder.newFolder();
        Dump dump = dump(CARD_A, 10, 1000L);
        dump.setName("School card");
        dump.setDescription("line one\nline two \\ end");
        DumpFile.write(dump, new File(dir, "unrelated-name.txt"));

        List<DumpGrouping.Entry> entries = DumpGrouping.read(dir);

        assertEquals("School card", entries.get(0).getName());
        assertEquals("line one\nline two \\ end", entries.get(0).getDescription());
    }

    @Test
    public void read_flagsAnEditedDump() throws IOException {
        File dir = temporaryFolder.newFolder();
        Dump dump = dump(CARD_A, 10, 1000L);
        dump.setBalance(500);
        DumpFile.write(dump, new File(dir, "edited.txt"));

        assertTrue(DumpGrouping.read(dir).get(0).isEdited());
    }

    @Test
    public void read_mergesTheLegacySidecarForAnOldFile() throws IOException {
        File dir = temporaryFolder.newFolder();
        File file = writeLegacy(dir, "2026-11-03_130000_111_55RUB.txt", CARD_A, 55);
        DumpMetadata legacy = new DumpMetadata(dir);
        legacy.put(file.getName(), "Old dump", "written by an older build");

        List<DumpGrouping.Entry> entries = DumpGrouping.read(dir, legacy);

        assertEquals("Old dump", entries.get(0).getName());
        assertEquals("written by an older build", entries.get(0).getDescription());
        assertEquals(CARD_A, entries.get(0).getCardNumber());
    }

    @Test
    public void read_prefersTheNameStoredInTheFileOverTheSidecar() throws IOException {
        File dir = temporaryFolder.newFolder();
        Dump dump = dump(CARD_A, 10, 1000L);
        dump.setName("New name");
        DumpFile.write(dump, new File(dir, "named.txt"));
        DumpMetadata legacy = new DumpMetadata(dir);
        legacy.put("named.txt", "Old name", null);

        assertEquals("New name", DumpGrouping.read(dir, legacy).get(0).getName());
    }

    @Test
    public void read_keepsBrokenFilesAsUnreadableEntries() throws IOException {
        File dir = temporaryFolder.newFolder();
        Files.write(new File(dir, "broken.txt").toPath(),
                "not a dump at all".getBytes(StandardCharsets.US_ASCII));

        List<DumpGrouping.Entry> entries = DumpGrouping.read(dir);

        assertEquals(1, entries.size());
        assertFalse(entries.get(0).isReadable());
        assertEquals(DumpGrouping.UNKNOWN_CARD_NUMBER, entries.get(0).getCardNumber());
        assertTrue("the reason must be kept for the row", entries.get(0).getError() != null);
    }

    @Test
    public void read_ignoresFilesThatAreNotDumps() throws IOException {
        File dir = temporaryFolder.newFolder();
        writeDump(dir, "real.txt", CARD_A, 10, 1000L);
        Files.write(new File(dir, "dump_metadata.properties").toPath(),
                "x.name=y".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(dir, "photo.jpg").toPath(), new byte[]{1, 2, 3});

        List<DumpGrouping.Entry> entries = DumpGrouping.read(dir);

        assertEquals(1, entries.size());
        assertEquals("real.txt", entries.get(0).getFilename());
    }

    @Test
    public void read_handlesAMissingDirectory() {
        assertTrue(DumpGrouping.read(null).isEmpty());
        assertTrue(DumpGrouping.read(new File(temporaryFolder.getRoot(), "nope")).isEmpty());
    }

    @Test
    public void group_usesTheParsedCardNumberNotTheName() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(Arrays.asList(
                entry("2026-11-03_130000_999_1RUB.txt", CARD_A, 3 * MINUTE),
                entry("renamed.txt", CARD_A, 2 * MINUTE),
                entry("2026-11-01_090000_111_1RUB.txt", CARD_B, 1 * MINUTE)));

        assertEquals(2, groups.size());
        assertEquals(CARD_A, groups.get(0).getCardNumber());
        assertEquals(2, groups.get(0).size());
        assertEquals(CARD_B, groups.get(1).getCardNumber());
        assertEquals("renamed.txt", groups.get(0).getEntries().get(1).getFilename());
    }

    @Test
    public void group_ordersEntriesAndGroupsNewestFirst() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(Arrays.asList(
                entry("a-old.txt", CARD_A, 1 * MINUTE),
                entry("b-new.txt", CARD_B, 4 * MINUTE),
                entry("a-new.txt", CARD_A, 3 * MINUTE)));

        // card B holds the newest dump, so its group comes first
        assertEquals(CARD_B, groups.get(0).getCardNumber());
        assertEquals(CARD_A, groups.get(1).getCardNumber());
        assertEquals(Arrays.asList("a-new.txt", "a-old.txt"), filenames(groups.get(1)));
    }

    @Test
    public void group_formatsTheCardNumberLabel() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(
                Arrays.asList(entry("x.txt", CARD_A, MINUTE)));

        assertEquals("0000 000 111", groups.get(0).getCardNumberLabel());
    }

    @Test
    public void group_putsUnreadableEntriesLast() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(Arrays.asList(
                DumpGrouping.Entry.unreadable("broken.txt", 5 * MINUTE, "boom"),
                entry("ok.txt", CARD_A, MINUTE)));

        assertEquals(2, groups.size());
        assertEquals(CARD_A, groups.get(0).getCardNumber());
        assertTrue(groups.get(1).isUnreadableGroup());
        assertNull(groups.get(1).getCardNumberLabel());
        assertEquals("broken.txt", groups.get(1).getEntries().get(0).getFilename());
    }

    @Test
    public void group_doesNotChangeTheInputList() {
        List<DumpGrouping.Entry> entries = new ArrayList<DumpGrouping.Entry>(Arrays.asList(
                entry("2.txt", CARD_B, 2 * MINUTE), entry("1.txt", CARD_A, MINUTE)));

        DumpGrouping.group(entries);

        assertEquals("2.txt", entries.get(0).getFilename());
        assertEquals("1.txt", entries.get(1).getFilename());
    }

    @Test
    public void group_returnsNothingForNoEntries() {
        assertTrue(DumpGrouping.group(new ArrayList<DumpGrouping.Entry>()).isEmpty());
    }

    @Test
    public void newestFirst_sortsByScanTime() {
        List<DumpGrouping.Entry> sorted = DumpGrouping.newestFirst(Arrays.asList(
                entry("old.txt", CARD_A, MINUTE), entry("new.txt", CARD_A, 2 * MINUTE)));

        assertEquals("new.txt", sorted.get(0).getFilename());
        assertEquals("old.txt", sorted.get(1).getFilename());
    }

    private static List<String> filenames(DumpGrouping.Group group) {
        List<String> names = new ArrayList<String>();
        for (DumpGrouping.Entry entry : group.getEntries()) {
            names.add(entry.getFilename());
        }
        return names;
    }
}
