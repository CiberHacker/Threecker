package com.ciber.threeker.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * User annotations for saved dumps: a name and a description per dump file.
 *
 * The annotations live in a sidecar {@value #FILENAME} file next to the dumps, so the dump format
 * itself (5 ASCII lines) is never touched and the annotations can simply be deleted. It is a plain
 * {@link Properties} file, which keeps non-ASCII names readable after a reload.
 */
public class DumpMetadata {

    public static final String FILENAME = "dump_metadata.properties";

    protected static final String NAME_SUFFIX = ".name";
    protected static final String DESCRIPTION_SUFFIX = ".description";

    /** Null when the dump directory is not available (unmounted storage). */
    protected final File file;
    protected final Properties properties = new Properties();

    public DumpMetadata(File dumpDirectory) {
        this.file = (dumpDirectory == null) ? null : new File(dumpDirectory, FILENAME);
        reload();
    }

    /**
     * Re-reads the sidecar file, so a screen that was open while another one saved a name sees it.
     * A broken or missing file is not an error: the dumps simply have no annotations.
     */
    public void reload() {
        properties.clear();
        if (file == null || !file.isFile()) {
            return;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
        } catch (IOException e) {
            properties.clear();
        } catch (RuntimeException e) {
            // properties.load also rejects malformed escapes, and that must not stop the list
            properties.clear();
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * @return the name of the dump, or null when it has none.
     */
    public String getName(String dumpFilename) {
        return trimToNull(properties.getProperty(key(dumpFilename, NAME_SUFFIX)));
    }

    /**
     * @return the description of the dump, or null when it has none.
     */
    public String getDescription(String dumpFilename) {
        return trimToNull(properties.getProperty(key(dumpFilename, DESCRIPTION_SUFFIX)));
    }

    /**
     * Stores (or, for blank values, removes) the annotations of one dump and writes the file.
     */
    public void put(String dumpFilename, String name, String description) throws IOException {
        if (file == null) {
            throw new IOException("Dump directory is not available");
        }
        setOrRemove(key(dumpFilename, NAME_SUFFIX), name);
        setOrRemove(key(dumpFilename, DESCRIPTION_SUFFIX), description);
        save();
    }

    /**
     * Removes the annotations of a deleted dump and writes the file. Removing a dump that had no
     * annotations is a no-op that still keeps the sidecar in sync.
     */
    public void remove(String dumpFilename) throws IOException {
        if (file == null) {
            throw new IOException("Dump directory is not available");
        }
        properties.remove(key(dumpFilename, NAME_SUFFIX));
        properties.remove(key(dumpFilename, DESCRIPTION_SUFFIX));
        save();
    }

    protected static String key(String dumpFilename, String suffix) {
        return dumpFilename + suffix;
    }

    protected static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        return (value.length() == 0) ? null : value;
    }

    protected void setOrRemove(String key, String value) {
        value = trimToNull(value);
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    protected void save() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can not make save dir");
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            properties.store(out, null);
        } finally {
            closeQuietly(out);
        }
    }

    protected static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing sensible to do here
        }
    }
}
