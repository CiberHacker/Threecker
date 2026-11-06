package cc.troikadumper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DumpGroupingTest {

    private static final String CARD_111_NEW = "2026-11-03_130000_111_55RUB.txt";
    private static final String CARD_111_OLD = "2026-11-02_090000_111_40RUB.txt";
    private static final String CARD_222_NEW = "2026-11-03_120000_222_99RUB.txt";
    private static final String CARD_222_OLD = "2026-10-01_080000_222_10RUB.txt";

    @Test
    public void cardNumberOf_readsTheNumberFromTheFilename() {
        assertEquals(111, DumpGrouping.cardNumberOf(CARD_111_NEW));
        assertEquals(DumpGrouping.UNKNOWN_CARD_NUMBER, DumpGrouping.cardNumberOf("notes.txt"));
    }

    @Test
    public void group_splitsByCardNumber() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(new String[]{
                CARD_111_OLD, CARD_222_OLD, CARD_111_NEW, CARD_222_NEW
        });

        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).size());
        assertEquals(2, groups.get(1).size());
    }

    @Test
    public void group_ordersGroupsAndFilesNewestFirst() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(new String[]{
                CARD_222_OLD, CARD_111_OLD, CARD_222_NEW, CARD_111_NEW
        });

        // card 111 holds the newest dump of all, so its group comes first
        assertEquals(111, groups.get(0).getCardNumber());
        assertEquals(Arrays.asList(CARD_111_NEW, CARD_111_OLD), groups.get(0).getFilenames());
        assertEquals(222, groups.get(1).getCardNumber());
        assertEquals(Arrays.asList(CARD_222_NEW, CARD_222_OLD), groups.get(1).getFilenames());
    }

    @Test
    public void group_formatsTheCardNumberOfTheHeader() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(new String[]{CARD_111_NEW});

        assertEquals("0000 000 111", groups.get(0).getCardNumberLabel());
    }

    @Test
    public void group_putsUnreadableNamesLast() {
        List<DumpGrouping.Group> groups = DumpGrouping.group(new String[]{
                "notes.txt", CARD_111_NEW
        });

        assertEquals(2, groups.size());
        assertEquals(111, groups.get(0).getCardNumber());
        assertTrue(groups.get(1).isUnknownCard());
        assertNull(groups.get(1).getCardNumberLabel());
    }

    @Test
    public void group_doesNotChangeTheGivenOrder() {
        String[] filenames = new String[]{CARD_222_OLD, CARD_111_NEW};

        DumpGrouping.group(filenames);

        assertEquals(CARD_222_OLD, filenames[0]);
        assertEquals(CARD_111_NEW, filenames[1]);
    }

    @Test
    public void group_returnsNothingForNoFiles() {
        assertTrue(DumpGrouping.group(new String[0]).isEmpty());
    }
}
