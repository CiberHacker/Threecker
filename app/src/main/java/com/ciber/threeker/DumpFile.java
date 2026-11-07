package com.ciber.threeker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ciber.threeker.utils.HexUtils;

/**
 * The dump file format: one self-contained text file per dump, holding the sector data plus
 * everything the app knows about it (name, description, when it was scanned and, for an edited
 * dump, the blocks as they were read from the card).
 *
 * Layout of a v2 file (UTF-8, one {@code key: value} pair per line, {@code \\}, {@code \n} and
 * {@code \r} are escaped in text values):
 * <pre>
 * threecker-dump v2
 * uid: 0446855A594D80
 * sector: 8
 * scanned: 2026-11-06T12:38:32+0300
 * name: School card
 * description: bought at the kiosk\nsecond line
 * block0: 45DB100A0F51FE196BA2A40000000000
 * block1: 37D92DF188001400012C10008F12AD88
 * block2: 37D92DF188001400012C10008F12AD88
 * block3: 00000000000078778800000000000000
 * original0: ...   (only when the dump was edited; all four blocks)
 * sector9_block0: ...   (optional, the three data blocks of sector 9 when the card allowed it)
 * </pre>
 *
 * A line-based format (not JSON) keeps the file readable, diffable and hand-editable, needs no
 * dependency and still runs in plain JVM unit tests - {@code org.json} is only a stub there.
 * Files written by older builds (five bare lines: UID, then blocks 0..3) are still read.
 *
 * Kept free of Android APIs so the whole format is unit tested on the JVM.
 */
public final class DumpFile {

    /** First line of a versioned file; anything else is parsed as the legacy five-line layout. */
    public static final String MAGIC = "threecker-dump v2";

    /** A dump is a few hundred bytes; a bigger file in the dumps folder is not ours. */
    protected static final long MAX_SIZE = 1024L * 1024L;

    protected static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";

    private DumpFile() {
    }

    /**
     * Reads a dump, auto-detecting the format.
     *
     * @throws IOException when the file is missing, too large or cannot be parsed.
     */
    public static Dump read(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("No such dump file");
        }
        if (file.length() > MAX_SIZE) {
            throw new IOException("Dump file is too large: " + file.getName());
        }
        String text = readText(file);
        if (isVersioned(text)) {
            return parseVersioned(text, file);
        }
        return parseLegacy(text, file);
    }

    /**
     * @return true when the text starts with {@link #MAGIC}, i.e. is a versioned dump.
     */
    public static boolean isVersioned(String text) {
        return text != null && text.trim().startsWith(MAGIC);
    }

    /**
     * @return the dump as it is written to disk (see the class comment for the layout).
     */
    public static String serialize(Dump dump) {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("uid: ").append(dump.getUidAsString()).append('\n');
        sb.append("sector: ").append(Dump.SECTOR_INDEX).append('\n');
        appendDate(sb, "scanned", dump.getScannedAt());
        appendText(sb, "name", dump.getName());
        appendText(sb, "description", dump.getDescription());

        String[] blocks = dump.getDataAsStrings();
        for (int i = 0; i < blocks.length; i++) {
            sb.append("block").append(i).append(": ").append(blocks[i]).append('\n');
        }

        // The scanned blocks are only worth storing while the data differs from them.
        if (dump.isEdited()) {
            String[] original = dump.getOriginalAsStrings();
            for (int i = 0; i < original.length; i++) {
                sb.append("original").append(i).append(": ").append(original[i]).append('\n');
            }
        }

        String[] sector9 = dump.getSector9AsStrings();
        if (sector9 != null) {
            for (int i = 0; i < sector9.length; i++) {
                sb.append("sector9_block").append(i).append(": ").append(sector9[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Writes the dump to exactly this file, replacing it atomically: the data goes to a sibling
     * {@code .tmp} file first, that file is read back and compared, and only then is it renamed over
     * the target. An interrupted write therefore never destroys the previous version.
     */
    public static void write(Dump dump, File file) throws IOException {
        if (dump == null || file == null) {
            throw new IOException("Nothing to write");
        }
        File dir = file.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("Not a dir");
        }

        File tmp = new File(dir, file.getName() + ".tmp");
        FileOutputStream stream = new FileOutputStream(tmp);
        Writer out = new OutputStreamWriter(stream, "UTF-8");
        try {
            out.write(serialize(dump));
            out.flush();
            stream.getFD().sync();
        } finally {
            out.close();
        }

        // Never replace a dump with content we cannot read back byte for byte.
        Dump check = read(tmp);
        if (!check.getUidAsString().equals(dump.getUidAsString())
                || !Arrays.equals(check.getDataAsStrings(), dump.getDataAsStrings())
                || !Arrays.equals(check.getSector9AsStrings(), dump.getSector9AsStrings())) {
            tmp.delete();
            throw new IOException("Can not verify the written dump: " + file.getName());
        }

        if (!tmp.renameTo(file)) {
            // Some file systems refuse to replace an existing file; the new one is complete now.
            if (!file.delete() || !tmp.renameTo(file)) {
                tmp.delete();
                throw new IOException("Can not replace " + file.getName());
            }
        }
    }

    /**
     * Parses the {@code key: value} layout. Unknown keys are ignored, so a newer build can add
     * fields without breaking this one; the blocks remain the only source of truth.
     */
    protected static Dump parseVersioned(String text, File file) throws IOException {
        String uidHex = null;
        String[] blocks = new String[Dump.BLOCK_COUNT];
        String[] original = new String[Dump.BLOCK_COUNT];
        String[] sector9 = new String[Dump.SECTOR_9_BLOCK_COUNT];
        boolean hasOriginal = false;
        boolean hasSector9 = false;
        Date scanned = null;
        String name = null;
        String description = null;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0 || MAGIC.equals(line)) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = unescape(line.substring(colon + 1).trim());
            if ("uid".equals(key)) {
                uidHex = value;
                continue;
            }
            if ("scanned".equals(key)) {
                scanned = parseDate(value);
                continue;
            }
            if ("name".equals(key)) {
                name = emptyToNull(value);
                continue;
            }
            if ("description".equals(key)) {
                description = emptyToNull(value);
                continue;
            }
            if (key.startsWith("sector9_block")) {
                int sector9Index = blockIndex(key, "sector9_block");
                if (sector9Index < 0 || sector9Index >= Dump.SECTOR_9_BLOCK_COUNT) {
                    throw malformed(file, "invalid value for " + key);
                }
                sector9[sector9Index] = value;
                hasSector9 = true;
                continue;
            }
            int index = blockIndex(key, "block");
            if (index >= 0) {
                blocks[index] = value;
                continue;
            }
            index = blockIndex(key, "original");
            if (index >= 0) {
                original[index] = value;
                hasOriginal = true;
            }
        }

        if (uidHex == null) {
            throw malformed(file, "no uid");
        }
        Dump dump = new Dump(toBytes(uidHex, file), toBlocks(blocks, file));
        if (scanned != null) {
            dump.setScannedAt(scanned);
        }
        dump.setName(name);
        dump.setDescription(description);
        if (hasOriginal) {
            dump.setOriginalData(toBlocks(original, file));
        }
        if (hasSector9) {
            boolean complete = true;
            for (int i = 0; i < Dump.SECTOR_9_BLOCK_COUNT; i++) {
                if (sector9[i] == null) {
                    complete = false;
                }
            }
            if (!complete) {
                throw malformed(file, "incomplete sector9 blocks");
            }
            byte[][] sector9Data = new byte[Dump.SECTOR_9_BLOCK_COUNT][];
            for (int i = 0; i < Dump.SECTOR_9_BLOCK_COUNT; i++) {
                byte[] bytes = toBytes(sector9[i], file);
                if (bytes.length != Dump.BLOCK_SIZE) {
                    throw malformed(file, "sector9_block" + i + " is not " + Dump.BLOCK_SIZE + " bytes");
                }
                sector9Data[i] = bytes;
            }
            dump.setSector9Data(sector9Data);
        }
        return dump;
    }

    /**
     * Parses the five bare lines written by older builds (UID, then blocks 0..3). Blank lines are
     * tolerated and everything after the fifth line is ignored.
     */
    protected static Dump parseLegacy(String text, File file) throws IOException {
        String uidHex = null;
        String[] blocks = new String[Dump.BLOCK_COUNT];
        int seen = 0;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (seen == 0) {
                uidHex = line;
            } else if (seen <= Dump.BLOCK_COUNT) {
                blocks[seen - 1] = line;
            }
            seen++;
        }
        if (uidHex == null) {
            throw malformed(file, "empty");
        }
        if (seen <= Dump.BLOCK_COUNT) {
            throw new IOException("Truncated dump file: " + file.getName());
        }
        Dump dump = new Dump(toBytes(uidHex, file), toBlocks(blocks, file));
        dump.setScannedAt(legacyScanTime(file));
        return dump;
    }

    /**
     * The five-line format has no timestamp, so for an old file the scan time is taken from its
     * (app generated) name and, failing that, from the file timestamp. It is written into the file
     * the first time the dump is saved; from then on the file is the only source of truth. This is
     * the only place where anything is read from a dump file name.
     */
    protected static Date legacyScanTime(File file) {
        Matcher m = Pattern.compile(Dump.FILENAME_REGEXP).matcher(file.getName());
        if (m.matches()) {
            try {
                return new Date(
                        Integer.parseInt(m.group(1)) - 1900,
                        Integer.parseInt(m.group(2)) - 1,
                        Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4).substring(0, 2)),
                        Integer.parseInt(m.group(4).substring(2, 4)),
                        Integer.parseInt(m.group(4).substring(4, 6)));
            } catch (RuntimeException ignored) {
                // unparseable name: fall back to the file timestamp below
            }
        }
        long lastModified = file.lastModified();
        return (lastModified > 0) ? new Date(lastModified) : new Date();
    }

    protected static byte[] toBytes(String hex, File file) throws IOException {
        try {
            byte[] bytes = HexUtils.fromString(hex);
            if (bytes.length == 0) {
                throw new IllegalArgumentException("empty value");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed dump file: " + file.getName(), e);
        }
    }

    protected static byte[][] toBlocks(String[] hex, File file) throws IOException {
        byte[][] blocks = new byte[Dump.BLOCK_COUNT][Dump.BLOCK_SIZE];
        for (int i = 0; i < Dump.BLOCK_COUNT; i++) {
            if (hex[i] == null) {
                throw malformed(file, "no block #" + i);
            }
            byte[] bytes = toBytes(hex[i], file);
            if (bytes.length != Dump.BLOCK_SIZE) {
                throw malformed(file, "block #" + i + " is not " + Dump.BLOCK_SIZE + " bytes");
            }
            blocks[i] = bytes;
        }
        return blocks;
    }

    /**
     * @return the index in "block0".."block3" / "original0".."original3", or -1 for another key.
     */
    protected static int blockIndex(String key, String prefix) {
        if (!key.startsWith(prefix)) {
            return -1;
        }
        try {
            int index = Integer.parseInt(key.substring(prefix.length()));
            return (index >= 0 && index < Dump.BLOCK_COUNT) ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    protected static void appendDate(StringBuilder sb, String key, Date value) {
        if (value == null) {
            return;
        }
        sb.append(key).append(": ")
                .append(new SimpleDateFormat(DATE_FORMAT, Locale.US).format(value)).append('\n');
    }

    /** Text values are skipped when empty, so an unannotated dump stays minimal. */
    protected static void appendText(StringBuilder sb, String key, String value) {
        if (value == null || value.trim().length() == 0) {
            return;
        }
        sb.append(key).append(": ").append(escape(value)).append('\n');
    }

    protected static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    protected static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'n') {
                out.append('\n');
            } else if (next == 'r') {
                out.append('\r');
            } else if (next == '\\') {
                out.append('\\');
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    /** @return the parsed timestamp, or null when it is missing or hand-edited into nonsense. */
    protected static Date parseDate(String value) {
        try {
            return new SimpleDateFormat(DATE_FORMAT, Locale.US).parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    protected static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        return (value.trim().length() == 0) ? null : value;
    }

    protected static String readText(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_SIZE) {
                    throw new IOException("Dump file is too large: " + file.getName());
                }
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    protected static IOException malformed(File file, String reason) {
        return new IOException("Malformed dump file: " + file.getName() + " (" + reason + ")");
    }
}
