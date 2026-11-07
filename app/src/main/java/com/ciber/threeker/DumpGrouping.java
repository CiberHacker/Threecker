package com.ciber.threeker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ciber.threeker.utils.DumpMetadata;

/**
 * Reads the dump files of the dumps directory and groups them by the card number stored in the
 * dump bytes for the "Dumps" tab.
 *
 * Nothing is taken from the file name: a renamed, hand-edited or truncated file still shows what it
 * really contains, and a file that cannot be parsed becomes an unreadable entry (listed, so it can
 * be deleted) instead of breaking the list. The old name scheme is still used when writing a dump,
 * but only as a readable label on disk. Kept free of Android APIs so it can be unit tested on the
 * JVM.
 */
public final class DumpGrouping {

    /** Dump files are plain text; anything else in the directory is ignored. */
    public static final String EXTENSION = ".txt";

    /** Card number reported for dumps whose bytes could not be parsed. */
    public static final int UNKNOWN_CARD_NUMBER = -1;

    private DumpGrouping() {
    }

    /**
     * One dump file with the dump parsed from its bytes. An unreadable file keeps only its name,
     * its timestamp and the reason, so it can still be listed.
     */
    public static class Entry {
        protected final String filename;
        /** Null when the file could not be parsed. */
        protected final Dump dump;
        protected final long savedAt;
        protected final String error;

        public Entry(String filename, Dump dump, long savedAt) {
            this(filename, dump, savedAt, null);
        }

        protected Entry(String filename, Dump dump, long savedAt, String error) {
            this.filename = filename;
            this.dump = dump;
            this.savedAt = savedAt;
            this.error = error;
        }

        /**
         * @param filename file whose bytes could not be parsed.
         * @param savedAt  last modified time, or 0 when unknown.
         * @param error    why it could not be parsed; may be null.
         */
        public static Entry unreadable(String filename, long savedAt, String error) {
            return new Entry(filename, null, savedAt, error);
        }

        public String getFilename() {
            return filename;
        }

        /** The dump as read from the file; null for an unreadable file. */
        public Dump getDump() {
            return dump;
        }

        /** Parsed from the dump bytes; {@link #UNKNOWN_CARD_NUMBER} when unreadable. */
        public int getCardNumber() {
            return isReadable() ? dump.getCardNumber() : UNKNOWN_CARD_NUMBER;
        }

        /** Parsed from the dump bytes. */
        public int getBalance() {
            return isReadable() ? dump.getBalance() : 0;
        }

        /** The name stored inside the dump file; null when it has none. */
        public String getName() {
            return isReadable() ? dump.getName() : null;
        }

        /** The description stored inside the dump file; null when it has none. */
        public String getDescription() {
            return isReadable() ? dump.getDescription() : null;
        }

        /**
         * @return when the card was scanned, taken from the dump file itself; 0 when unknown (the
         * file predates the versioned format or was hand-edited).
         */
        public long getScannedAt() {
            if (!isReadable() || dump.getScannedAt() == null) {
                return 0;
            }
            return dump.getScannedAt().getTime();
        }

        /** True when the stored blocks differ from the scanned ones kept in the same file. */
        public boolean isEdited() {
            return isReadable() && dump.isEdited();
        }

        /** When the file was last written, i.e. when the card was scanned; 0 when unknown. */
        public long getSavedAt() {
            return savedAt;
        }

        /**
         * Timestamp used for ordering and labels: the scan time from the dump file, falling back to
         * the file timestamp of older files.
         */
        public long getTimestamp() {
            long scannedAt = getScannedAt();
            return (scannedAt > 0) ? scannedAt : savedAt;
        }

        public String getError() {
            return error;
        }

        public boolean isReadable() {
            return dump != null;
        }
    }

    /**
     * One card number and its dumps, newest first.
     */
    public static class Group {
        protected final int cardNumber;
        protected final List<Entry> entries;

        protected Group(int cardNumber, List<Entry> entries) {
            this.cardNumber = cardNumber;
            this.entries = entries;
        }

        public int getCardNumber() {
            return cardNumber;
        }

        /** True for the group that holds the files whose bytes could not be parsed. */
        public boolean isUnreadableGroup() {
            return cardNumber == UNKNOWN_CARD_NUMBER;
        }

        /**
         * @return the card number as it is printed in the app ("0019 088 743"), or null for the
         * unreadable group.
         */
        public String getCardNumberLabel() {
            return isUnreadableGroup() ? null : Dump.formatCardNumber(cardNumber);
        }

        public List<Entry> getEntries() {
            return Collections.unmodifiableList(entries);
        }

        public int size() {
            return entries.size();
        }

        /** Newest timestamp in the group, used to order the groups. */
        public long getNewestTimestamp() {
            return entries.get(0).getTimestamp();
        }
    }

    /**
     * Reads every dump file in {@code dir}; names and descriptions stored inside a file are used as
     * they are.
     *
     * @param dir dumps directory; null or unreadable directories give an empty list.
     */
    public static List<Entry> read(File dir) {
        return read(dir, null);
    }

    /**
     * Reads every dump file in {@code dir} and parses its bytes.
     *
     * @param dir    dumps directory; null or unreadable directories give an empty list.
     * @param legacy annotations of older builds (the old sidecar file, keyed by file name); they are
     *               used only for files that do not carry a name/description themselves, i.e. dumps
     *               written before format v2. Nothing is rewritten here: the annotations are folded
     *               into the file the next time it is saved.
     * @return one entry per {@value #EXTENSION} file, in directory order (nothing is parsed from the
     * name); files that fail to parse are returned as unreadable entries.
     */
    public static List<Entry> read(File dir, DumpMetadata legacy) {
        List<Entry> entries = new ArrayList<Entry>();
        if (dir == null) {
            return entries;
        }
        String[] filenames = dir.list();
        if (filenames == null) {
            return entries;
        }
        for (String filename : filenames) {
            if (!filename.toLowerCase(Locale.US).endsWith(EXTENSION)) {
                continue;
            }
            File file = new File(dir, filename);
            long savedAt = file.lastModified();
            try {
                Dump dump = Dump.fromFile(file);
                mergeLegacyAnnotations(dump, filename, legacy);
                entries.add(new Entry(filename, dump, savedAt));
            } catch (IOException e) {
                // A truncated or hand-edited file must not break the whole list.
                entries.add(Entry.unreadable(filename, savedAt, e.getMessage()));
            } catch (RuntimeException e) {
                // Same for anything the parser did not expect (e.g. a binary file renamed to .txt).
                entries.add(Entry.unreadable(filename, savedAt, e.toString()));
            }
        }
        return entries;
    }

    /**
     * Fills in name/description from the pre-v2 sidecar, so old dumps keep their annotations.
     */
    protected static void mergeLegacyAnnotations(Dump dump, String filename, DumpMetadata legacy) {
        if (dump == null || legacy == null) {
            return;
        }
        if (dump.getName() == null) {
            dump.setName(legacy.getName(filename));
        }
        if (dump.getDescription() == null) {
            dump.setDescription(legacy.getDescription(filename));
        }
    }

    /**
     * @return the entries sorted newest first, without touching the given list.
     */
    public static List<Entry> newestFirst(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<Entry>(entries);
        Collections.sort(sorted, NEWEST_FIRST);
        return sorted;
    }

    /**
     * @param entries dumps read by {@link #read(File)}, in any order.
     * @return one group per parsed card number, the group holding the newest dump first, entries
     * newest first inside a group. The unreadable group sorts last.
     */
    public static List<Group> group(List<Entry> entries) {
        List<Entry> sorted = newestFirst(entries);

        Map<Integer, List<Entry>> byCard = new LinkedHashMap<Integer, List<Entry>>();
        for (Entry entry : sorted) {
            List<Entry> bucket = byCard.get(entry.getCardNumber());
            if (bucket == null) {
                bucket = new ArrayList<Entry>();
                byCard.put(entry.getCardNumber(), bucket);
            }
            bucket.add(entry);
        }

        List<Group> groups = new ArrayList<Group>();
        for (Map.Entry<Integer, List<Entry>> entry : byCard.entrySet()) {
            groups.add(new Group(entry.getKey(), entry.getValue()));
        }
        Collections.sort(groups, NEWEST_GROUP_FIRST);
        return groups;
    }

    /** Newest first; the file name only breaks ties so the order stays stable. */
    protected static final Comparator<Entry> NEWEST_FIRST = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            int byTime = compareTimes(b.getTimestamp(), a.getTimestamp());
            return (byTime != 0) ? byTime : a.getFilename().compareTo(b.getFilename());
        }
    };

    /** Newest dump first; the unreadable group always sorts last. */
    protected static final Comparator<Group> NEWEST_GROUP_FIRST = new Comparator<Group>() {
        @Override
        public int compare(Group a, Group b) {
            if (a.isUnreadableGroup() || b.isUnreadableGroup()) {
                if (a.isUnreadableGroup() && b.isUnreadableGroup()) {
                    return 0;
                }
                return a.isUnreadableGroup() ? 1 : -1;
            }
            int byTime = compareTimes(b.getNewestTimestamp(), a.getNewestTimestamp());
            return (byTime != 0) ? byTime : (a.getCardNumber() - b.getCardNumber());
        }
    };

    protected static int compareTimes(long a, long b) {
        return (a == b) ? 0 : ((a > b) ? 1 : -1);
    }
}
