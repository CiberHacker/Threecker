package com.ciber.threeker;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import com.ciber.threeker.utils.HexUtils;

public class Dump {
    public static final String FILENAME_FORMAT = "%04d-%02d-%02d_%02d%02d%02d_%d_%dRUB.txt";
    public static final String FILENAME_REGEXP = "([0-9]{4})-([0-9]{2})-([0-9]{2})_([0-9]{6})_([0-9]+)_([0-9]+)RUB.txt";

    public static final int BLOCK_COUNT = 4;
    public static final int BLOCK_SIZE = MifareClassic.BLOCK_SIZE;
    public static final int SECTOR_INDEX = 8;

    public static final byte[] KEY_B =
            {(byte) 0xE3, (byte) 0x51, (byte) 0x73, (byte) 0x49, (byte) 0x4A, (byte) 0x81};

    public static final byte[] KEY_A =
            {(byte) 0xA7, (byte) 0x3F, (byte) 0x5D, (byte) 0xC1, (byte) 0xD3, (byte) 0x33};

    public static final byte[] KEY_0 =
            {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};

    public static final int SECTOR_9_INDEX = 9;

    /** Data blocks of sector 9; the trailer with the keys and access bits is never read or written. */
    public static final int SECTOR_9_BLOCK_COUNT = 3;

    /** KeyB of sector 9 (key id 12 of the key catalog the official app uses). */
    public static final byte[] KEY_9 =
            {(byte) 0x6B, (byte) 0x8B, (byte) 0xD9, (byte) 0x86, (byte) 0x07, (byte) 0x63};

    // raw
    protected byte[] uid;
    protected byte[][] data;

    /** Sector 9 data blocks, or null when the card did not allow reading sector 9. */
    protected byte[][] sector9;

    /** Blocks exactly as they were scanned; set once the data is edited, null otherwise. */
    protected byte[][] original;

    // annotations, stored in the dump file itself (format v2)
    protected String name;
    protected String description;
    protected Date scannedAt;

    // parsed
    protected int cardNumber;
    protected int balance;
    protected Date lastUsageDate;
    protected int lastValidatorId;

    public Dump(byte[] uid, byte[][] sector8) {
        this.uid = uid;
        this.data = sector8;
        this.scannedAt = new Date();
        parse();
    }

    public static Dump fromTag(Tag tag) throws IOException {
        MifareClassic mfc = getMifareClassic(tag);
        try {
            int blockCount = mfc.getBlockCountInSector(SECTOR_INDEX);
            if (blockCount < BLOCK_COUNT) {
                throw new IOException("Wtf? Not enough blocks on this card");
            }

            byte[][] data = new byte[BLOCK_COUNT][BLOCK_SIZE];

            for (int i = 0; i < BLOCK_COUNT; i++) {
                data[i] = mfc.readBlock(mfc.sectorToBlock(SECTOR_INDEX) + i);
            }

            Dump dump = new Dump(tag.getId(), data);
            dump.sector9 = readSector9(mfc);
            return dump;
        } finally {
            closeQuietly(mfc);
        }
    }

    /**
     * Reads a dump from disk. Both the versioned format written by {@link DumpFile} and the
     * five-line format of older builds are understood; a truncated or corrupt file throws
     * {@link IOException}, which the callers turn into an error dialog instead of a crash.
     */
    public static Dump fromFile(File file) throws IOException {
        return DumpFile.read(file);
    }

    /**
     * Writes name and description into an existing dump file, keeping its blocks and its name on
     * disk. This is how the "Edit details" dialog works: the annotations live in the dump itself, so
     * they survive a rename and are shared together with the data.
     */
    public static void updateDetails(File file, String name, String description) throws IOException {
        Dump dump = fromFile(file);
        dump.setName(name);
        dump.setDescription(description);
        DumpFile.write(dump, file);
    }

    /**
     * @return the timestamp of the scan; when the dump was never scanned in this session (e.g. it
     * was loaded from a legacy file) it is the time the dump object was created.
     */
    public Date getScannedAt() {
        return scannedAt;
    }

    /** Only {@link DumpFile} restores the stored timestamp; the UI never changes it. */
    public void setScannedAt(Date scannedAt) {
        this.scannedAt = scannedAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = trimToNull(name);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = trimToNull(description);
    }

    /**
     * @return true when the blocks differ from the ones that were scanned, i.e. when the pristine
     * card data is still available through {@link #getOriginalData()}.
     */
    public boolean isEdited() {
        return original != null && !Arrays.deepEquals(original, data);
    }

    public byte[][] getOriginalData() {
        return original;
    }

    /** @return true when the balance differs from the scanned one. */
    public boolean isBalanceChanged() {
        return differs(1, 5, 6);
    }

    /**
     * @return true when the packed last-pass field differs from the scanned one. The usage time and
     * the validator ID are two views of the same 24-bit field, so they always change together.
     */
    public boolean isLastPassChanged() {
        return differs(1, 0, 1, 2);
    }

    protected boolean differs(int block, int... indexes) {
        if (original == null) {
            return false;
        }
        for (int index : indexes) {
            if (original[block][index] != data[block][index]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts the scanned balance back and leaves the rest of the dump alone.
     *
     * @return true when the balance had been changed.
     */
    public boolean restoreBalance() {
        if (!isBalanceChanged()) {
            return false;
        }
        data[1][5] = original[1][5];
        data[1][6] = original[1][6];
        parse();
        return true;
    }

    /**
     * Puts the scanned usage/validator bytes back. Both values share the 24-bit field, so restoring
     * either one restores the other as well.
     *
     * @return true when the field had been changed.
     */
    public boolean restoreLastPass() {
        if (!isLastPassChanged()) {
            return false;
        }
        data[1][0] = original[1][0];
        data[1][1] = original[1][1];
        data[1][2] = original[1][2];
        parse();
        return true;
    }

    public String[] getOriginalAsStrings() {
        if (original == null) {
            return new String[0];
        }
        String[] blocks = new String[original.length];
        for (int i = 0; i < original.length; i++) {
            blocks[i] = HexUtils.toString(original[i]);
        }
        return blocks;
    }

    /**
     * Puts the scanned blocks back (the name and the description are kept). The caller decides
     * whether the restored state is also written to disk.
     */
    public void revertToOriginal() {
        if (original == null) {
            return;
        }
        data = deepCopy(original);
        parse();
    }

    /**
     * Restores the pristine blocks stored in a dump file. Package-private: only {@link DumpFile}
     * needs it, the UI goes through {@link #isEdited()} and {@link #revertToOriginal()}.
     */
    void setOriginalData(byte[][] original) {
        this.original = (original == null) ? null : deepCopy(original);
    }

    /** Keeps the scanned blocks before the first edit, so they can be restored and stored. */
    protected void rememberOriginal() {
        if (original == null) {
            original = deepCopy(data);
        }
    }

    protected static byte[][] deepCopy(byte[][] blocks) {
        byte[][] copy = new byte[blocks.length][];
        for (int i = 0; i < blocks.length; i++) {
            copy[i] = Arrays.copyOf(blocks[i], blocks[i].length);
        }
        return copy;
    }

    protected static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return (trimmed.length() == 0) ? null : trimmed;
    }

    protected static MifareClassic getMifareClassic(Tag tag) throws IOException {
        MifareClassic mfc = MifareClassic.get(tag);
        mfc.connect();

        if (!authenticateSector8(mfc)) {
            closeQuietly(mfc);
            throw new IOException("No permissions");
        }

        return mfc;
    }

    /**
     * Authenticates sector 8, trying the same keys as always: both sides with zero (blank card),
     * then the regular Troika keys for the A and B side.
     *
     * @return true when one of the key pairs was accepted.
     */
    protected static boolean authenticateSector8(MifareClassic mfc) throws IOException {
        // fucked up card
        if (mfc.authenticateSectorWithKeyA(SECTOR_INDEX, KEY_0) && mfc.authenticateSectorWithKeyB(SECTOR_INDEX, KEY_0)) {
            return true;
        }

        // good card
        return mfc.authenticateSectorWithKeyA(SECTOR_INDEX, KEY_A)
                && mfc.authenticateSectorWithKeyB(SECTOR_INDEX, KEY_B);
    }

    /**
     * Authenticates sector 9. Tries the sector 8 key pairs first and falls back to the sector 9
     * KeyB, which is how the official app unlocks that sector.
     *
     * @return true when one of the keys was accepted.
     */
    protected static boolean authenticateSector9(MifareClassic mfc) throws IOException {
        if (mfc.authenticateSectorWithKeyA(SECTOR_9_INDEX, KEY_0)
                && mfc.authenticateSectorWithKeyB(SECTOR_9_INDEX, KEY_0)) {
            return true;
        }
        if (mfc.authenticateSectorWithKeyA(SECTOR_9_INDEX, KEY_A)
                && mfc.authenticateSectorWithKeyB(SECTOR_9_INDEX, KEY_B)) {
            return true;
        }
        return mfc.authenticateSectorWithKeyB(SECTOR_9_INDEX, KEY_9);
    }

    /**
     * Reads the three data blocks of sector 9. The trailer holds the keys and is never touched.
     *
     * @return the blocks, or null when sector 9 cannot be read - the dump then covers sector 8
     *         exactly like the dumps of older builds.
     */
    protected static byte[][] readSector9(MifareClassic mfc) {
        try {
            if (!authenticateSector9(mfc)) {
                return null;
            }
            byte[][] blocks = new byte[SECTOR_9_BLOCK_COUNT][BLOCK_SIZE];
            int start = mfc.sectorToBlock(SECTOR_9_INDEX);
            for (int i = 0; i < SECTOR_9_BLOCK_COUNT; i++) {
                blocks[i] = mfc.readBlock(start + i);
            }
            return blocks;
        } catch (IOException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    protected void parse() {
        // block#0 bytes#3-6
        cardNumber = intval(data[0][3], data[0][4], data[0][5], data[0][6]) >> 4;

        // The validator ID is the high 16 bits of the same 24-bit field that carries the last usage
        // time (minutes since 2018-12-31 00:00 GMT+3, shifted left by one). Editing either value
        // changes the other one; keep the setters below in sync with this layout.
        lastValidatorId = intval(data[1][0], data[1][1]);

        int minutesDelta = intval((byte) (data[1][0]), (byte) (data[1][1]), (byte) (data[1][2])) >> 1;
        if (minutesDelta > 0) {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
            c.set(2018, 11, 31, 0, 0);
            c.add(Calendar.MINUTE, minutesDelta);
            lastUsageDate = c.getTime();
        } else {
            lastUsageDate = null;
        }

        balance = intval((byte) (data[1][5]), (byte) data[1][6]) / 25;
    }

    /** The card epoch of the 24-bit last-usage field: 2018-12-31 00:00 GMT+3. */
    protected static Calendar cardEpoch() {
        Calendar epoch = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        epoch.set(2018, 11, 31, 0, 0, 0);
        epoch.set(Calendar.MILLISECOND, 0);
        return epoch;
    }

    /**
     * Sets the last usage time. The value is stored as minutes since {@link #cardEpoch()} in the low
     * bits of the 24-bit last-pass field; the high 16 bits (shown as the validator ID) change with
     * it, because both are views of the same field.
     *
     * @param date the new last usage time; must be after the card epoch
     */
    public void setLastUsageDate(Date date) {
        if (date == null) {
            throw new IllegalArgumentException("Last usage date is null");
        }
        long minutes = (date.getTime() - cardEpoch().getTimeInMillis()) / 60000L;
        if (minutes <= 0 || minutes > 0x7FFFFFL) {
            throw new IllegalArgumentException("Last usage date is out of range: " + date);
        }
        int value = intval((byte) data[1][0], (byte) data[1][1], (byte) data[1][2]);
        writeLastPassValue(((int) minutes << 1) | (value & 0x1));
        parse();
    }

    /**
     * Sets the last validator ID, the high 16 bits of the same field that carries the last usage
     * time. The decoded time therefore changes as well.
     *
     * @param validatorId the new validator ID, 0..65535
     */
    public void setLastValidatorId(int validatorId) {
        if (validatorId < 0 || validatorId > 0xFFFF) {
            throw new IllegalArgumentException("Validator ID out of range: " + validatorId);
        }
        int value = intval((byte) data[1][0], (byte) data[1][1], (byte) data[1][2]);
        writeLastPassValue(((validatorId & 0xFFFF) << 8) | (value & 0xFF));
        parse();
    }

    /** Writes the 24-bit last-pass field (block #1, bytes 0..2) and keeps the low bit. */
    protected void writeLastPassValue(int value) {
        rememberOriginal();
        data[1][0] = (byte) ((value >> 16) & 0xFF);
        data[1][1] = (byte) ((value >> 8) & 0xFF);
        data[1][2] = (byte) (value & 0xFF);
    }

    public void write(Tag tag) throws IOException {
        MifareClassic mfc = getMifareClassic(tag);
        try {
            if (!belongsTo(tag)) {
                throw new IOException("Card UID mismatch: \n"
                        + HexUtils.toString(tag.getId()) + " (card) != "
                        + getUidAsString() + " (dump)");
            }

            // Sector 9 is written first: if it cannot be authenticated, nothing has been touched.
            if (sector9 != null) {
                if (!authenticateSector9(mfc)) {
                    throw new IOException("Sector 9 authentication failed");
                }
                int sector9Start = mfc.sectorToBlock(SECTOR_9_INDEX);
                for (int i = 0; i < sector9.length; i++) {
                    mfc.writeBlock(sector9Start + i, sector9[i]);
                }
                // Writing sector 9 moved the active authentication; sector 8 needs it again.
                if (!authenticateSector8(mfc)) {
                    throw new IOException("No permissions");
                }
            }

            int numBlocksToWrite = BLOCK_COUNT - 1; // do not overwrite last block (keys)
            int startBlockIndex = mfc.sectorToBlock(SECTOR_INDEX);
            for (int i = 0; i < numBlocksToWrite; i++) {
                mfc.writeBlock(startBlockIndex + i, data[i]);
            }
        } finally {
            closeQuietly(mfc);
        }
    }

    /**
     * @return true when the given tag carries the same UID as this dump, i.e. when this dump
     * belongs to that exact card.
     */
    public boolean belongsTo(Tag tag) {
        if (tag == null) {
            return false;
        }
        return Arrays.equals(tag.getId(), uid);
    }

    /**
     * Reads sectors 8 and 9 back from the card and compares them with the data of this dump, so a
     * write can be verified instead of trusted.
     *
     * @return true when every block written by {@link #write(Tag)} is present on the card.
     */
    public boolean verify(Tag tag) throws IOException {
        MifareClassic mfc = getMifareClassic(tag);
        try {
            int startBlockIndex = mfc.sectorToBlock(SECTOR_INDEX);
            for (int i = 0; i < BLOCK_COUNT - 1; i++) {
                if (!Arrays.equals(mfc.readBlock(startBlockIndex + i), data[i])) {
                    return false;
                }
            }
            if (sector9 != null) {
                if (!authenticateSector9(mfc)) {
                    return false;
                }
                int sector9Start = mfc.sectorToBlock(SECTOR_9_INDEX);
                for (int i = 0; i < sector9.length; i++) {
                    if (!Arrays.equals(mfc.readBlock(sector9Start + i), sector9[i])) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            closeQuietly(mfc);
        }
    }

    protected static void closeQuietly(MifareClassic mfc) {
        if (mfc == null) {
            return;
        }
        try {
            mfc.close();
        } catch (IOException ignored) {
            // nothing sensible to do here
        }
    }

    /**
     * Human readable export of everything this dump knows, used for sharing.
     */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Threecker dump\n");
        if (name != null) {
            sb.append("Name:           ").append(name).append('\n');
        }
        if (description != null) {
            sb.append("Description:    ").append(description).append('\n');
        }
        if (scannedAt != null) {
            sb.append("Scanned:        ")
                    .append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(scannedAt))
                    .append('\n');
        }
        sb.append("Card UID:       ").append(getUidAsString()).append('\n');
        sb.append("Card number:    ").append(getCardNumberAsString()).append('\n');
        sb.append("Balance:        ").append(getBalanceAsString()).append('\n');
        sb.append("Last usage:     ").append(getLastUsageDateAsString()).append('\n');
        sb.append("Last validator: ").append(getLastValidatorIdAsString()).append('\n');
        sb.append("\nSector #").append(SECTOR_INDEX).append(":\n");
        String[] blocks = getDataAsStrings();
        for (int i = 0; i < blocks.length; i++) {
            sb.append(i).append("] ").append(blocks[i]).append('\n');
        }
        String[] sector9Blocks = getSector9AsStrings();
        if (sector9Blocks != null) {
            sb.append("\nSector #").append(SECTOR_9_INDEX).append(":\n");
            for (int i = 0; i < sector9Blocks.length; i++) {
                sb.append(i).append("] ").append(sector9Blocks[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Machine readable export of everything this dump knows, used for sharing.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": \"troika-dump-v1\",\n");
        sb.append("  \"uid\": \"").append(getUidAsString()).append("\",\n");
        sb.append("  \"cardNumber\": \"").append(getCardNumberAsString()).append("\",\n");
        sb.append("  \"balance\": ").append(getBalance()).append(",\n");
        Date usage = getLastUsageDate();
        sb.append("  \"lastUsageDate\": ");
        if (usage == null) {
            sb.append("null");
        } else {
            sb.append('"').append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                    .format(usage)).append('"');
        }
        sb.append(",\n");
        sb.append("  \"lastValidatorId\": ").append(getLastValidatorId()).append(",\n");
        sb.append("  \"sector\": ").append(SECTOR_INDEX).append(",\n");
        sb.append("  \"blocks\": [");
        String[] blocks = getDataAsStrings();
        for (int i = 0; i < blocks.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(blocks[i]).append('"');
        }
        sb.append("]");
        String[] sector9Blocks = getSector9AsStrings();
        if (sector9Blocks != null) {
            sb.append(",\n  \"sector9blocks\": [");
            for (int i = 0; i < sector9Blocks.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(sector9Blocks[i]).append('"');
            }
            sb.append("]");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    public File save(File dir) throws IOException {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            throw new IOException("Can not write to external storage");
        }

        if (!dir.isDirectory()) {
            throw new IOException("Not a dir");
        }

        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Can not make save dir");
        }

        File file = new File(dir, makeFilename());
        DumpFile.write(this, file);

        return file;
    }

    /**
     * Writes the current state back into its own file, e.g. after a field was edited, and keeps the
     * scanned blocks next to it so the change can be reviewed or reverted later.
     */
    public void saveChanges(File file) throws IOException {
        DumpFile.write(this, file);
    }

    /**
     * Writes a copy of this dump into {@code dir}, used by "Duplicate" in the dump list. The copy
     * gets a fresh date/time based name, so no existing dump is overwritten: when the name is
     * already taken (duplicating a dump saved in the same second) the timestamp is advanced by a
     * second until it is free. Name, description, scan time and the original blocks travel with it,
     * so the copy needs no sidecar entries.
     *
     * @return the new file, holding the same UID and sector 8 blocks as this dump.
     */
    public File saveCopy(File dir) throws IOException {
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("Not a dir");
        }

        File file = new File(dir, uniqueFilename(dir));
        DumpFile.write(this, file);

        return file;
    }

    protected String uniqueFilename(File dir) {
        Date date = new Date();
        String filename = filenameFor(date);
        while (new File(dir, filename).exists()) {
            // Shifting by a second keeps the name valid and chronologically sortable.
            date = new Date(date.getTime() + 1000L);
            filename = filenameFor(date);
        }
        return filename;
    }

    protected String makeFilename() {
        return filenameFor(new Date());
    }

    protected String filenameFor(Date date) {
        return String.format(
                FILENAME_FORMAT,
                date.getYear() + 1900, date.getMonth() + 1, date.getDate(),
                date.getHours(), date.getMinutes(), date.getSeconds(),
                getCardNumber(), getBalance()
        );
    }

    public byte[] getUid() {
        return uid;
    }

    public String getUidAsString() {
        return HexUtils.toString(getUid());
    }

    public byte[][] getData() {
        return data;
    }

    public String[] getDataAsStrings() {
        String blocks[] = new String[data.length];
        for (int i = 0; i < data.length; i++) {
            blocks[i] = HexUtils.toString(data[i]);
        }
        return blocks;
    }

    public byte[][] getSector9Data() {
        return sector9;
    }

    public boolean hasSector9() {
        return sector9 != null;
    }

    public String[] getSector9AsStrings() {
        if (sector9 == null) {
            return null;
        }
        String blocks[] = new String[sector9.length];
        for (int i = 0; i < sector9.length; i++) {
            blocks[i] = HexUtils.toString(sector9[i]);
        }
        return blocks;
    }

    /**
     * Stores the raw sector 9 data blocks (three blocks of 16 bytes, trailer excluded). Used when
     * loading a dump file - sector 9 is restored verbatim and never exposed to the editing UI.
     */
    public void setSector9Data(byte[][] sector9) {
        this.sector9 = sector9;
    }

    public Date getLastUsageDate() {
        return lastUsageDate;
    }

    public String getLastUsageDateAsString() {
        if (lastUsageDate == null) {
            return "<NEVER USED>";
        }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(lastUsageDate);
    }

    public int getLastValidatorId() {
        return lastValidatorId;
    }

    public String getLastValidatorIdAsString() {
        return "ID# " + getLastValidatorId();
    }

    public int getBalance() {
        return balance;
    }

    /**
     * Sets a new balance and re-encodes it into the raw block data
     * ({@code data[1][5]} high byte, {@code data[1][6]} low byte, big-endian,
     * stored as balance * 25).
     *
     * @param newBalance the new balance in rubles (must be >= 0)
     */
    public void setBalance(int newBalance) {
        if (newBalance < 0) {
            throw new IllegalArgumentException("Balance cannot be negative: " + newBalance);
        }
        rememberOriginal();
        this.balance = newBalance;
        int value = newBalance * 25;
        data[1][5] = (byte) ((value >> 8) & 0xFF);
        data[1][6] = (byte) (value & 0xFF);
    }

    public String getBalanceAsString() {
        return "" + getBalance() + " RUB";
    }

    public int getCardNumber() {
        return cardNumber;
    }

    public String getCardNumberAsString() {
        return formatCardNumber(cardNumber);
    }

    public static String formatCardNumber(int cardNumber) {
        int cardNum3 = cardNumber % 1000;
        int cardNum2 = (int) Math.floor(cardNumber / 1000) % 1000;
        int cardNum1 = (int) Math.floor(cardNumber / 1000000) % 1000;
        return String.format("%04d %03d %03d", cardNum1, cardNum2, cardNum3);
    }

    public String toString() {
        return "[Card UID=" + getUidAsString() + " " + getBalanceAsString() + "RUR]";
    }

    protected static int intval(byte... bytes) {
        int value = 0;
        for (int i = 0; i < bytes.length; i++) {
            int x = (int) bytes[bytes.length - i - 1];
            while (x < 0) x = 256 + x;
            value += x * Math.pow(0x100, i);
        }
        return value;
    }

}
