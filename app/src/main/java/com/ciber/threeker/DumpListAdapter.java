package com.ciber.threeker;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the dump entries read from disk into a list grouped by the card number stored in the dump
 * bytes: one header row per card number, followed by that card's dumps (newest first). Every group
 * starts collapsed, so the tab opens as a short list of cards. A dump row shows its optional user
 * name, a label (scan time + balance) taken from the file and its bytes, and its optional
 * description.
 */
public class DumpListAdapter extends BaseAdapter {

    protected static final int VIEW_TYPE_HEADER = 0;
    protected static final int VIEW_TYPE_DUMP = 1;
    protected static final int VIEW_TYPE_COUNT = 2;

    protected final Context context;
    protected final LayoutInflater inflater;
    protected final List<Row> rows = new ArrayList<Row>();

    /** The groups of the last {@link #rebuild(List)}, kept so collapsing does not re-read files. */
    protected final List<DumpGrouping.Group> groups = new ArrayList<DumpGrouping.Group>();

    /**
     * Card numbers whose dumps are shown. Empty by default, so every group starts collapsed; the
     * state survives list refreshes and resets when the app restarts.
     */
    protected final Set<Integer> expandedCards = new HashSet<Integer>();

    /**
     * Callbacks for a dump row's tap and long press. They live on the row's MaterialCardView (which
     * draws the rounded ripple), so header taps keep going through the ListView's item click.
     */
    public interface Listener {
        void onDumpSelected(String filename);

        void onDumpActions(Row row);
    }

    protected Listener listener;

    /**
     * One list entry: either a card number header or a dump below that header.
     */
    public class Row {
        protected final String headerLabel;
        protected final int cardNumber;
        protected final boolean collapsed;
        /** Null on header rows. */
        protected final DumpGrouping.Entry entry;

        protected Row(String headerLabel, int cardNumber, boolean collapsed,
                      DumpGrouping.Entry entry) {
            this.headerLabel = headerLabel;
            this.cardNumber = cardNumber;
            this.collapsed = collapsed;
            this.entry = entry;
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
            return (entry == null) ? null : entry.getFilename();
        }

        /** False when the dump bytes could not be parsed; such a row can only be deleted. */
        public boolean isReadable() {
            return entry != null && entry.isReadable();
        }

        public String getName() {
            return (entry == null) ? null : entry.getName();
        }

        public String getDescription() {
            return (entry == null) ? null : entry.getDescription();
        }

        public String getLabel() {
            return (entry == null) ? null : label(entry);
        }
    }

    public DumpListAdapter(Context context, List<DumpGrouping.Entry> entries) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        rebuild(entries);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the shown dumps. The entries come from {@link DumpGrouping#read(File)}, i.e. from the
     * dump files themselves - blocks, name, description and scan time.
     */
    public void rebuild(List<DumpGrouping.Entry> entries) {
        groups.clear();
        groups.addAll(DumpGrouping.group(entries));
        buildRows();
    }

    /**
     * Rebuilds the visible rows from {@link #groups}, skipping the dumps of collapsed cards. Called
     * by {@link #rebuild(List)} and on a header tap, so the expanded state survives a list refresh
     * instead of resetting on every resume.
     */
    protected void buildRows() {
        rows.clear();
        for (DumpGrouping.Group group : groups) {
            boolean collapsed = !expandedCards.contains(group.getCardNumber());
            rows.add(new Row(headerLabel(group), group.getCardNumber(), collapsed, null));
            if (!collapsed) {
                for (DumpGrouping.Entry entry : group.getEntries()) {
                    rows.add(new Row(null, group.getCardNumber(), false, entry));
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
        if (group.isUnreadableGroup()) {
            return context.getString(R.string.dumplist_unreadable_group);
        }
        return context.getString(R.string.dumplist_group_header,
                group.getCardNumberLabel(), group.size());
    }

    /**
     * @return the label under the optional name: when the dump was scanned (stored inside the file,
     * falling back to the file timestamp) and the balance parsed from the blocks. An edited dump is
     * marked, an unreadable file shows why it could not be parsed instead.
     */
    protected String label(DumpGrouping.Entry entry) {
        if (!entry.isReadable()) {
            String error = entry.getError();
            String title = context.getString(R.string.dumplist_unreadable);
            return (error == null) ? title : title + "\n" + error;
        }
        String balance = context.getString(R.string.dumplist_balance, entry.getBalance());
        if (entry.isEdited()) {
            balance = context.getString(R.string.dumplist_edited, balance);
        }
        long timestamp = entry.getTimestamp();
        if (timestamp <= 0) {
            return balance;
        }
        Date savedAt = new Date(timestamp);
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(savedAt)
                + "\n" + balance;
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
        final Row clicked = row;
        View card = convertView.findViewById(R.id.dump_list_item_card);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDumpSelected(clicked.getFilename());
                }
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onDumpActions(clicked);
                }
                return true;
            }
        });
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
