package cc.troikadumper;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.TimeZone;

import cc.troikadumper.utils.HexUtils;

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

    // raw
    protected byte[] uid;
    protected byte[][] data;

    // parsed
    protected int cardNumber;
    protected int balance;
    protected Date lastUsageDate;
    protected int lastValidatorId;

    public Dump(byte[] uid, byte[][] sector8) {
        this.uid = uid;
        this.data = sector8;
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

            return new Dump(tag.getId(), data);
        } finally {
            closeQuietly(mfc);
        }
    }

    public static Dump fromFile(File file) throws IOException {
        // The scanner (and with it the file stream) has to be closed, otherwise every opened dump
        // leaks a file descriptor. A truncated or malformed file is reported as an IOException, so
        // the callers' error dialogs handle it instead of the activity crashing.
        FileInputStream fs = new FileInputStream(file);
        Scanner scanner = null;
        try {
            scanner = new Scanner(fs, "US-ASCII");
            byte[] uid = HexUtils.fromString(scanner.nextLine());

            byte[][] data = new byte[BLOCK_COUNT][BLOCK_SIZE];
            for (int i = 0; i < BLOCK_COUNT; i++) {
                data[i] = HexUtils.fromString(scanner.nextLine());
            }

            return new Dump(uid, data);
        } catch (NoSuchElementException e) {
            throw new IOException("Truncated dump file: " + file.getName(), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed dump file: " + file.getName(), e);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
            fs.close();
        }
    }

    protected static MifareClassic getMifareClassic(Tag tag) throws IOException {
        MifareClassic mfc = MifareClassic.get(tag);
        mfc.connect();

        // fucked up card
        if (mfc.authenticateSectorWithKeyA(SECTOR_INDEX, KEY_0) && mfc.authenticateSectorWithKeyB(SECTOR_INDEX, KEY_0)) {
            return mfc;
        }

        // good card
        if (mfc.authenticateSectorWithKeyA(SECTOR_INDEX, KEY_A) && mfc.authenticateSectorWithKeyB(SECTOR_INDEX, KEY_B)
        ) {
            return mfc;
        }

        throw new IOException("No permissions");
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
     * Reads sector #8 back from the card and compares it with the data of this dump, so a write
     * can be verified instead of trusted.
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
        sb.append("]\n}\n");
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
        writeTo(file);

        return file;
    }

    /**
     * Writes a copy of this dump into {@code dir}, used by "Duplicate" in the dump list. The copy
     * gets a fresh date/time based name, so no existing dump is overwritten: when the name is
     * already taken (duplicating a dump saved in the same second) the timestamp is advanced by a
     * second until it is free.
     *
     * @return the new file, holding the same UID and sector 8 blocks as this dump.
     */
    public File saveCopy(File dir) throws IOException {
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("Not a dir");
        }

        File file = new File(dir, uniqueFilename(dir));
        writeTo(file);

        return file;
    }

    /**
     * Writes the dump format: the UID, then the four sector blocks, one hex line each.
     */
    protected void writeTo(File file) throws IOException {
        FileOutputStream stream = new FileOutputStream(file);
        OutputStreamWriter out = new OutputStreamWriter(stream);
        out.write(getUidAsString() + "\r\n");
        for (String block : getDataAsStrings()) {
            out.write(block + "\r\n");
        }
        out.close();
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
