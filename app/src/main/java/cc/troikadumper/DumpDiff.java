package cc.troikadumper;

import java.util.ArrayList;
import java.util.List;

/**
 * Field-by-field comparison of two dumps, split out of {@link DiffActivity} so that it can be
 * unit tested on the JVM: no Android APIs are involved here.
 */
public final class DumpDiff {

    /** Identifiers of the compared fields. Block rows use {@code BLOCK_BASE + blockIndex}. */
    public static final int FIELD_UID = 0;
    public static final int FIELD_CARD_NUMBER = 1;
    public static final int FIELD_BALANCE = 2;
    public static final int FIELD_LAST_USAGE = 3;
    public static final int FIELD_LAST_VALIDATOR = 4;
    public static final int BLOCK_BASE = 100;

    /**
     * One compared row. Labels are not part of this class - {@link DiffActivity} maps {@link #getId()}
     * to a string resource, so the comparison stays free of Android resources.
     */
    public static final class Row {
        protected final int id;
        protected final String valueA;
        protected final String valueB;

        protected Row(int id, String valueA, String valueB) {
            this.id = id;
            this.valueA = valueA;
            this.valueB = valueB;
        }

        public int getId() {
            return id;
        }

        public String getValueA() {
            return valueA;
        }

        public String getValueB() {
            return valueB;
        }

        public boolean isSame() {
            return valueA.equals(valueB);
        }
    }

    private DumpDiff() {
    }

    /**
     * @return one row per compared field (UID, card number, balance, last usage, last validator),
     * followed by one row per sector block.
     */
    public static List<Row> compare(Dump a, Dump b) {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row(FIELD_UID, a.getUidAsString(), b.getUidAsString()));
        rows.add(new Row(FIELD_CARD_NUMBER, a.getCardNumberAsString(), b.getCardNumberAsString()));
        rows.add(new Row(FIELD_BALANCE, a.getBalanceAsString(), b.getBalanceAsString()));
        rows.add(new Row(FIELD_LAST_USAGE, a.getLastUsageDateAsString(), b.getLastUsageDateAsString()));
        rows.add(new Row(FIELD_LAST_VALIDATOR, a.getLastValidatorIdAsString(), b.getLastValidatorIdAsString()));

        String[] blocksA = a.getDataAsStrings();
        String[] blocksB = b.getDataAsStrings();
        int blockCount = Math.max(blocksA.length, blocksB.length);
        for (int i = 0; i < blockCount; i++) {
            rows.add(new Row(BLOCK_BASE + i,
                    (i < blocksA.length) ? blocksA[i] : "",
                    (i < blocksB.length) ? blocksB[i] : ""));
        }
        return rows;
    }

    /**
     * @return how many of the given rows hold different values.
     */
    public static int countChanged(List<Row> rows) {
        int changed = 0;
        for (Row row : rows) {
            if (!row.isSame()) {
                changed++;
            }
        }
        return changed;
    }
}
