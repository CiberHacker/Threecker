package com.ciber.threeker.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.IOException;

import com.ciber.threeker.Dump;
import com.ciber.threeker.R;

/**
 * Dialog that attaches a name and a description to a saved dump. The values are app-local
 * annotations stored by {@link DumpMetadata}; nothing is written to the card.
 */
public final class DumpDetailsDialog {

    public interface OnSavedListener {
        /**
         * @param name        the saved name, or null when it was cleared.
         * @param description the saved description, or null when it was cleared.
         */
        void onDetailsSaved(String name, String description);
    }

    private DumpDetailsDialog() {
    }

    /**
     * Edits the name and the description of one dump file. Both are stored inside the dump file
     * itself (see {@link Dump#updateDetails(File, String, String)}), so they survive a rename, are
     * copied together with the dump and are shared with it.
     *
     * @param dumpFile           the saved dump file, not a directory.
     * @param initialName        current name, may be null.
     * @param initialDescription current description, may be null.
     */
    public static void show(final Activity activity, final File dumpFile,
                            String initialName, String initialDescription,
                            final OnSavedListener listener) {
        if (activity == null || dumpFile == null) {
            return;
        }

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_dump_details, null);
        final EditText name = (EditText) view.findViewById(R.id.details_name);
        final EditText description = (EditText) view.findViewById(R.id.details_description);
        name.setText(orEmpty(initialName));
        description.setText(orEmpty(initialDescription));

        new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_details_title)
                .setView(view)
                .setPositiveButton(R.string.action_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newName = name.getText().toString();
                        String newDescription = description.getText().toString();
                        try {
                            Dump.updateDetails(dumpFile, newName, newDescription);
                            if (listener != null) {
                                // The setters normalize: blank means "no name/description".
                                listener.onDetailsSaved(newName, newDescription);
                            }
                        } catch (IOException e) {
                            Toast.makeText(activity,
                                    activity.getString(R.string.error_save_details) + ": " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    protected static String orEmpty(String value) {
        return (value == null) ? "" : value;
    }
}
