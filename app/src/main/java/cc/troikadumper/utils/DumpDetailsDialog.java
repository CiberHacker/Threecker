package cc.troikadumper.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.IOException;

import cc.troikadumper.R;

/**
 * Dialog that attaches a name and a description to a saved dump. The values are app-local
 * annotations stored by {@link DumpMetadata}; nothing is written to the card.
 */
public final class DumpDetailsDialog {

    public interface OnSavedListener {
        void onDetailsSaved();
    }

    private DumpDetailsDialog() {
    }

    /**
     * @param dumpFilename name of the dump file (not a path), used as the metadata key.
     */
    public static void show(final Activity activity, final DumpMetadata metadata,
                            final String dumpFilename, final OnSavedListener listener) {
        if (activity == null || metadata == null || dumpFilename == null) {
            return;
        }

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_dump_details, null);
        final EditText name = (EditText) view.findViewById(R.id.details_name);
        final EditText description = (EditText) view.findViewById(R.id.details_description);
        name.setText(orEmpty(metadata.getName(dumpFilename)));
        description.setText(orEmpty(metadata.getDescription(dumpFilename)));

        new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_details_title)
                .setView(view)
                .setPositiveButton(R.string.action_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            metadata.put(dumpFilename,
                                    name.getText().toString(),
                                    description.getText().toString());
                            if (listener != null) {
                                listener.onDetailsSaved();
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
