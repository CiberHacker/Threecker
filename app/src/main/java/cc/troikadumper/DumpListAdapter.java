package cc.troikadumper;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.troikadumper.utils.DumpMetadata;

/**
 * Turns the saved dump files into a list grouped by card number: one header row per card number,
 * followed by that card's dumps (newest first). Every group starts collapsed, so the tab opens as a
 * short list of cards. A dump row shows its optional user name, the date/balance label and its
 * optional description.
 */
public class DumpListAdapter extends BaseAdapter {

    protected static final int VIEW_TYPE_HEADER = 0;
    protected static final int VIEW_TYPE_DUMP = 1;
    protected static final int VIEW_TYPE_COUNT = 2;

    protected static Pattern pattern;

    protected final Context context;
    protected final LayoutInflater inflater;
    protected final DumpMetadata metadata;
    protected final List<Row> rows = new ArrayList<Row>();

    /** The groups of the last {@link #rebuild(String[])}, kept so collapsing does not re-read files. */
    protected final List<DumpGrouping.Group> groups = new ArrayList<DumpGrouping.Group>();

    /**
     * Card numbers whose dumps are shown. Empty by default, so every group starts collapsed; the
     * state survives list refreshes and resets when the app restarts.
     */
    protected final Set<Integer> expandedCards = new HashSet<Integer>();

    /**
     * One list entry: either a card number header or a dump below that header.
     */
    public class Row {
        protected final String headerLabel;
        protected final int cardNumber;
        protected final boolean collapsed;
        protected final String filename;
        protected final String name;
        protected final String description;
        protected final String label;

        protected Row(String headerLabel, int cardNumber, boolean collapsed,
                      String filename, String name, String description, String label) {
            this.headerLabel = headerLabel;
            this.cardNumber = cardNumber;
            this.collapsed = collapsed;
            this.filename = filename;
            this.name = name;
            this.description = description;
            this.label = label;
        }

        public boolean isHeader() {
            return headerLabel != null;
        }

        /** Only meaningful on a header row. */
        public boolean isCollapsed() {
            return collapsed;
        }

        /** Only meaningful on a header row; the group's card number. */
        public int getCardNumber() {
            return cardNumber;
        }

        public String getHeaderLabel() {
            return headerLabel;
        }

        public String getFilename() {
            return filename;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getLabel() {
            return label;
        }
    }

    public DumpListAdapter(Context context, String[] filenames, DumpMetadata metadata) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.metadata = metadata;
        rebuild(filenames);
    }

    /**
     * Replaces the shown files. The annotations are read from {@link DumpMetadata} again, so call
     * {@link DumpMetadata#reload()} before this when they may have changed on disk.
     */
    public void rebuild(String[] filenames) {
        groups.clear();
        groups.addAll(DumpGrouping.group(filenames));
        buildRows();
    }

    /**
     * Rebuilds the visible rows from {@link #groups}, skipping the dumps of collapsed cards. Called
     * by {@link #rebuild(String[])} and on a header tap, so the expanded state survives a list
     * refresh instead of resetting on every resume.
     */
    protected void buildRows() {
        rows.clear();
        for (DumpGrouping.Group group : groups) {
            boolean collapsed = !expandedCards.contains(group.getCardNumber());
            rows.add(new Row(headerLabel(group), group.getCardNumber(), collapsed,
                    null, null, null, null));
            if (!collapsed) {
                for (String filename : group.getFilenames()) {
                    rows.add(dumpRow(filename));
                }
            }
        }
        notifyDataSetChanged();
    }

    /**
     * Collapses or expands the card group whose header is at the given list position.
     *
     * @return true when the position was a header row and its state was flipped.
     */
    public boolean toggleGroup(int position) {
        Row row = rows.get(position);
        if (!row.isHeader()) {
            return false;
        }
        int cardNumber = row.getCardNumber();
        if (!expandedCards.remove(cardNumber)) {
            expandedCards.add(cardNumber);
        }
        buildRows();
        return true;
    }

    /**
     * Expands the group of the given card number, so a freshly duplicated dump is visible without
     * another tap. Unknown card numbers ({@link DumpGrouping#UNKNOWN_CARD_NUMBER}) work too.
     *
     * @return true when the group was collapsed and is now expanded.
     */
    public boolean expandGroup(int cardNumber) {
        if (!expandedCards.add(cardNumber)) {
            return false;
        }
        buildRows();
        return true;
    }

    protected String headerLabel(DumpGrouping.Group group) {
        if (group.isUnknownCard()) {
            return context.getString(R.string.dumplist_unknown_card);
        }
        return context.getString(R.string.dumplist_group_header,
                group.getCardNumberLabel(), group.size());
    }

    protected Row dumpRow(String filename) {
        return new Row(null, DumpGrouping.UNKNOWN_CARD_NUMBER, false, filename,
                metadata.getName(filename),
                metadata.getDescription(filename),
                dumpLabel(filename));
    }

    protected String dumpLabel(String filename) {
        Matcher m = getPattern().matcher(filename);
        if (!m.matches()) {
            return filename;
        }
        Date date = new Date(
                Integer.parseInt(m.group(1)) - 1900,
                Integer.parseInt(m.group(2)) - 1,
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4).substring(0, 2)),
                Integer.parseInt(m.group(4).substring(2, 4)),
                Integer.parseInt(m.group(4).substring(4, 6))
        );
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(date)
                + "\n" + context.getString(R.string.dumplist_balance, m.group(6));
    }

    protected Pattern getPattern() {
        if (pattern == null) {
            pattern = Pattern.compile(Dump.FILENAME_REGEXP);
        }
        return pattern;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return VIEW_TYPE_COUNT;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isHeader() ? VIEW_TYPE_HEADER : VIEW_TYPE_DUMP;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        // Header rows are enabled too: a tap on a header collapses/expands that card.
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);

        if (row.isHeader()) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.dump_list_header, parent, false);
            }
            ((TextView) convertView.findViewById(R.id.dump_list_header))
                    .setText(row.getHeaderLabel());
            ImageView chevron =
                    (ImageView) convertView.findViewById(R.id.dump_list_header_chevron);
            chevron.setRotation(row.isCollapsed() ? -90f : 0f);
            chevron.setContentDescription(context.getString(row.isCollapsed()
                    ? R.string.dumplist_expand
                    : R.string.dumplist_collapse));
            return convertView;
        }

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.dump_list_item, parent, false);
        }
        setOptionalText((TextView) convertView.findViewById(R.id.dump_list_item_name),
                row.getName());
        ((TextView) convertView.findViewById(R.id.dump_list_item_label)).setText(row.getLabel());
        setOptionalText((TextView) convertView.findViewById(R.id.dump_list_item_description),
                row.getDescription());
        return convertView;
    }

    /**
     * Shows the given view only when there is text for it, so unannotated dumps stay compact.
     */
    protected static void setOptionalText(TextView view, String text) {
        if (text == null || text.length() == 0) {
            view.setText("");
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }
}
