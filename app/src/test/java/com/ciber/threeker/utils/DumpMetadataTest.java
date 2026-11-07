package com.ciber.threeker.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class DumpMetadataTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String DUMP = "2026-11-03_130000_111_55RUB.txt";

    @Test
    public void unannotatedDumpHasNoNameAndNoDescription() throws IOException {
        DumpMetadata metadata = new DumpMetadata(folder.newFolder("dumps"));

        assertNull(metadata.getName(DUMP));
        assertNull(metadata.getDescription(DUMP));
    }

    @Test
    public void put_survivesAReload() throws IOException {
        File dir = folder.newFolder("dumps");
        new DumpMetadata(dir).put(DUMP, "School card", "kept in the bag");

        DumpMetadata reloaded = new DumpMetadata(dir);
        assertEquals("School card", reloaded.getName(DUMP));
        assertEquals("kept in the bag", reloaded.getDescription(DUMP));
    }

    @Test
    public void put_keepsCharactersOutsideAscii() throws IOException {
        // escaped on purpose so the test does not depend on the source file encoding
        String name = "Caf\u00e9 \u2116 1";
        File dir = folder.newFolder("dumps");
        new DumpMetadata(dir).put(DUMP, name, null);

        assertEquals(name, new DumpMetadata(dir).getName(DUMP));
    }

    @Test
    public void put_withBlankValuesRemovesTheAnnotation() throws IOException {
        File dir = folder.newFolder("dumps");
        DumpMetadata metadata = new DumpMetadata(dir);
        metadata.put(DUMP, "Name", "Description");
        metadata.put(DUMP, "   ", "");

        DumpMetadata reloaded = new DumpMetadata(dir);
        assertNull(reloaded.getName(DUMP));
        assertNull(reloaded.getDescription(DUMP));
    }

    @Test
    public void reload_picksUpChangesMadeByAnotherInstance() throws IOException {
        File dir = folder.newFolder("dumps");
        DumpMetadata first = new DumpMetadata(dir);
        new DumpMetadata(dir).put(DUMP, "From the other screen", null);

        assertNull(first.getName(DUMP));
        first.reload();
        assertEquals("From the other screen", first.getName(DUMP));
    }

    @Test
    public void brokenMetadataFileIsIgnored() throws IOException {
        File dir = folder.newFolder("dumps");
        FileWriter writer = new FileWriter(new File(dir, DumpMetadata.FILENAME));
        try {
            // a malformed unicode escape is rejected by Properties.load with a RuntimeException.
            // The backslash is concatenated so javac does not treat the sequence as an escape.
            writer.write(DUMP + ".name=" + '\\' + "uZZZZ\n");
        } finally {
            writer.close();
        }

        assertNull(new DumpMetadata(dir).getName(DUMP));
    }

    @Test
    public void remove_deletesBothAnnotations() throws IOException {
        File dir = folder.newFolder("dumps");
        DumpMetadata metadata = new DumpMetadata(dir);
        metadata.put(DUMP, "School card", "kept in the bag");

        metadata.remove(DUMP);

        DumpMetadata reloaded = new DumpMetadata(dir);
        assertNull(reloaded.getName(DUMP));
        assertNull(reloaded.getDescription(DUMP));
    }

    @Test
    public void remove_withoutAnnotationsIsHarmless() throws IOException {
        File dir = folder.newFolder("dumps");
        new DumpMetadata(dir).put(DUMP, "School card", null);

        // a different file name must not disturb the existing annotation
        new DumpMetadata(dir).remove("2026-11-04_090000_222_10RUB.txt");

        assertEquals("School card", new DumpMetadata(dir).getName(DUMP));
    }

    @Test
    public void missingDirectoryIsNotFatalButCannotSave() throws IOException {
        DumpMetadata metadata = new DumpMetadata(null);

        assertNull(metadata.getName(DUMP));
        try {
            metadata.put(DUMP, "name", "description");
            fail("expected IOException");
        } catch (IOException expected) {
            // the dump directory is unavailable
        }
    }
}
