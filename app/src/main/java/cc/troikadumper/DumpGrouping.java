package cc.troikadumper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups saved dump files by card number for the "All your dumps" screen.
 *
 * The card number is read from the file name (see {@link Dump#FILENAME_REGEXP}), so no dump has to
 * be opened for the list to be built. Kept free of Android APIs so it can be unit tested on the JVM.
 */
public final class DumpGrouping {

    /** Card number reported for file names that do not carry one. */
    public static final int UNKNOWN_CARD_NUMBER = -1;

    private static final Pattern PATTERN = Pattern.compile(Dump.FILENAME_REGEXP);

    private DumpGrouping() {
    }

    /**
     * One card number and its dumps, newest first.
     */
    public static class Group {
        protected final int cardNumber;
        protected final List<String> filenames;

        protected Group(int cardNumber, List<String> filenames) {
            this.cardNumber = cardNumber;
            this.filenames = filenames;
        }

        public int getCardNumber() {
            return cardNumber;
        }

        public boolean isUnknownCard() {
            return cardNumber == UNKNOWN_CARD_NUMBER;
        }

        /**
         * @return the card number as it is printed in the app ("0019 088 743"), or null when unknown.
         */
        public String getCardNumberLabel() {
            return isUnknownCard() ? null : Dump.formatCardNumber(cardNumber);
        }

        public List<String> getFilenames() {
            return Collections.unmodifiableList(filenames);
        }

        public int size() {
            return filenames.size();
        }
    }

    /**
     * @param filenames dump file names, in any order.
     * @return one group per card number, the group holding the newest dump first, files newest first
     * inside a group. Unknown card numbers sort last.
     */
    public static List<Group> group(String[] filenames) {
        List<String> sorted = new ArrayList<String>(Arrays.asList(filenames));
        // Zero padded file names sort chronologically, so this puts the newest dump first.
        Collections.sort(sorted);
        Collections.reverse(sorted);

        Map<Integer, List<String>> byCard = new LinkedHashMap<Integer, List<String>>();
        for (String filename : sorted) {
            int cardNumber = cardNumberOf(filename);
            List<String> bucket = byCard.get(cardNumber);
            if (bucket == null) {
                bucket = new ArrayList<String>();
                byCard.put(cardNumber, bucket);
            }
            bucket.add(filename);
        }

        List<Group> groups = new ArrayList<Group>();
        for (Map.Entry<Integer, List<String>> entry : byCard.entrySet()) {
            groups.add(new Group(entry.getKey(), entry.getValue()));
        }
        Collections.sort(groups, new Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                if (a.isUnknownCard() || b.isUnknownCard()) {
                    if (a.isUnknownCard() && b.isUnknownCard()) {
                        return 0;
                    }
                    return a.isUnknownCard() ? 1 : -1;
                }
                int byNewestFilename = b.filenames.get(0).compareTo(a.filenames.get(0));
                return (byNewestFilename != 0) ? byNewestFilename : (a.cardNumber - b.cardNumber);
            }
        });
        return groups;
    }

    /**
     * @return the card number encoded in a dump file name, or {@link #UNKNOWN_CARD_NUMBER}.
     */
    public static int cardNumberOf(String filename) {
        Matcher m = PATTERN.matcher(filename);
        if (!m.matches()) {
            return UNKNOWN_CARD_NUMBER;
        }
        try {
            return Integer.parseInt(m.group(5));
        } catch (NumberFormatException e) {
            return UNKNOWN_CARD_NUMBER;
        }
    }
}
