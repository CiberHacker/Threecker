package cc.troikadumper;

import android.content.DialogInterface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

import cc.troikadumper.utils.DumpDetailsDialog;
import cc.troikadumper.utils.DumpMetadata;

/**
 * Binds the "Dumps" tab: it lists the saved dump files grouped by card number (all groups start
 * collapsed), lets the user load one into the "Overwrite" tab and offers details, duplication and
 * deletion on a long press.
 *
 * The dumps directory may be unavailable (external storage unmounted); every operation then falls
 * back to an empty list or an error toast instead of crashing.
 */
public class DumpListSection {

    public interface Listener {
        /**
         * @param filename name of the selected dump file, not a path.
         */
        void onDumpSelected(String filename);

        /**
         * Called after a dump file was deleted, so the screen can drop it if it is currently loaded.
         *
         * @param filename name of the deleted dump file, not a path.
         */
        void onDumpDeleted(String filename);
    }

    protected final AppCompatActivity activity;
    protected final ListView listView;
    protected final DumpMetadata metadata;
    protected final Listener listener;

    /** Null when the external dump directory is not available. */
    protected final File dumpsDir;

    protected DumpListAdapter adapter;

    public DumpListSection(AppCompatActivity activity, ListView listView, Listener listener) {
        this.activity = activity;
        this.listView = listView;
        this.listener = listener;
        this.dumpsDir = activity.getApplicationContext().getExternalFilesDir(null);
        this.metadata = new DumpMetadata(dumpsDir);

        listView.setClickable(true);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                DumpListAdapter.Row row =
                        (DumpListAdapter.Row) parent.getItemAtPosition(position);
                if (row == null) {
                    return;
                }
                if (row.isHeader()) {
                    // Tapping a card header hides/shows that card's dumps.
                    if (adapter != null) {
                        adapter.toggleGroup(position);
                    }
                    return;
                }
                DumpListSection.this.listener.onDumpSelected(row.getFilename());
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                DumpListAdapter.Row row =
                        (DumpListAdapter.Row) parent.getItemAtPosition(position);
                if (row == null || row.isHeader()) {
                    return false;
                }
                showDumpActions(row.getFilename());
                return true;
            }
        });
    }

    /**
     * Re-reads the dumps directory and rebuilds the grouped list.
     */
    public void refresh() {
        String[] filenames = (dumpsDir == null) ? null : dumpsDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                return filename.matches(Dump.FILENAME_REGEXP);
            }
        });
        if (filenames == null) {
            filenames = new String[0];
        }

        metadata.reload();
        if (adapter == null) {
            // The activity (not the application context) is required: the rows are MaterialCardViews
            // and resolve ?attr/... against the activity theme.
            adapter = new DumpListAdapter(activity, filenames, metadata);
            listView.setAdapter(adapter);
        } else {
            adapter.rebuild(filenames);
        }
    }

    /**
     * Offers to name/describe the dump, duplicate it, or delete it.
     */
    protected void showDumpActions(final String filename) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dump_actions_title)
                .setItems(new CharSequence[]{
                        activity.getString(R.string.action_edit_details),
                        activity.getString(R.string.action_duplicate),
                        activity.getString(R.string.action_delete)
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            editDetails(filename);
                        } else if (which == 1) {
                            duplicateDump(filename);
                        } else {
                            confirmDelete(filename);
                        }
                    }
                })
                .show();
    }

    /**
     * Lets the user attach a name and a description to the dump; the list refreshes on save.
     */
    protected void editDetails(String filename) {
        DumpDetailsDialog.show(activity, metadata, filename,
                new DumpDetailsDialog.OnSavedListener() {
                    @Override
                    public void onDetailsSaved() {
                        Toast.makeText(activity, R.string.details_saved, Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                });
    }

    /**
     * Writes a copy of the dump next to it, so the original file is never touched. The copy gets a
     * fresh date/time based name and keeps the name/description of the original (with " (copy)"
     * appended to the name). Its card group is expanded, so the new row is visible right away.
     */
    protected void duplicateDump(String filename) {
        if (dumpsDir == null) {
            Toast.makeText(activity, R.string.error_read_file_title, Toast.LENGTH_LONG).show();
            return;
        }

        Dump dump;
        File copy;
        try {
            dump = Dump.fromFile(new File(dumpsDir, filename));
            copy = dump.saveCopy(dumpsDir);
        } catch (IOException e) {
            Toast.makeText(activity, activity.getString(R.string.error_duplicate)
                    + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        boolean detailsCopied = true;
        try {
            metadata.put(copy.getName(), duplicateName(filename),
                    metadata.getDescription(filename));
        } catch (IOException e) {
            // The copy itself is valid, only the annotations are missing.
            detailsCopied = false;
            Toast.makeText(activity, activity.getString(R.string.error_save_details)
                    + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        if (adapter != null) {
            adapter.expandGroup(dump.getCardNumber());
        }
        if (detailsCopied) {
            Toast.makeText(activity, R.string.dump_duplicated, Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    /**
     * @return the name of a duplicated dump, marked as a copy; null when the original had no name.
     */
    protected String duplicateName(String filename) {
        String name = metadata.getName(filename);
        return (name == null) ? null : activity.getString(R.string.dump_duplicate_name, name);
    }

    /**
     * Deletes a dump only after the user confirms it, because the file cannot be restored.
     */
    protected void confirmDelete(final String filename) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(activity.getString(R.string.dialog_delete_message, filename))
                .setPositiveButton(R.string.dialog_delete_confirm,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                deleteDump(filename);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    protected void deleteDump(String filename) {
        if (dumpsDir == null) {
            Toast.makeText(activity, R.string.error_delete, Toast.LENGTH_LONG).show();
            return;
        }

        File file = new File(dumpsDir, filename);
        boolean deleted = file.delete();

        // Drop the name/description too, so a future dump with the same file name starts clean.
        try {
            metadata.remove(filename);
        } catch (IOException e) {
            Toast.makeText(activity, activity.getString(R.string.error_save_details)
                    + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        if (!deleted) {
            Toast.makeText(activity, R.string.error_delete, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }

        listener.onDumpDeleted(filename);
        refresh();
    }
}
