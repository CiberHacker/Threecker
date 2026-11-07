package com.ciber.threeker.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;

import com.ciber.threeker.Dump;
import com.ciber.threeker.R;

/**
 * Exports a dump: either the dump file itself (the receiver gets the blocks together with the name,
 * the description and, for an edited dump, the scanned blocks), the human readable text or JSON.
 *
 * The file travels as a {@code content://} URI from the app's own {@link FileProvider}, so no storage
 * permission is involved; the text/JSON variants stay plain {@code ACTION_SEND} extra text.
 */
public final class DumpShare {

    /** Must match {@code android:authorities} of the provider in the manifest. */
    public static final String AUTHORITY_SUFFIX = ".dumps";

    private DumpShare() {
    }

    /**
     * Asks how the dump should leave the app.
     *
     * @param dumpFile the saved dump file, or null when the dump was not written to disk (then only
     *                 the text and JSON options are offered).
     */
    public static void showChooser(final Activity activity, final Dump dump, final File dumpFile) {
        if (activity == null || dump == null) {
            return;
        }
        final boolean asFile = dumpFile != null && dumpFile.isFile();
        CharSequence[] items = asFile
                ? new CharSequence[]{
                        activity.getString(R.string.share_as_file),
                        activity.getString(R.string.share_as_text),
                        activity.getString(R.string.share_as_json)}
                : new CharSequence[]{
                        activity.getString(R.string.share_as_text),
                        activity.getString(R.string.share_as_json)};

        new AlertDialog.Builder(activity)
                .setTitle(R.string.share_dump)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (asFile && which == 0) {
                            shareFile(activity, dump, dumpFile);
                        } else {
                            // The text option is always the one right after the file option.
                            share(activity, dump, which == (asFile ? 2 : 1));
                        }
                    }
                })
                .show();
    }

    /**
     * Sends the dump file itself through any app that accepts an attachment. Everything the app
     * knows about the dump is inside that one file.
     */
    public static void shareFile(Activity activity, Dump dump, File dumpFile) {
        if (activity == null || dump == null || dumpFile == null || !dumpFile.isFile()) {
            return;
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + AUTHORITY_SUFFIX, dumpFile);
        } catch (IllegalArgumentException e) {
            // The file is outside the paths declared in res/xml/file_paths.xml.
            Toast.makeText(activity, R.string.error_share, Toast.LENGTH_LONG).show();
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT,
                activity.getString(R.string.share_subject, dump.getCardNumberAsString()));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_dump)));
    }

    /**
     * @param asJson true to share JSON, false to share human readable text.
     */
    public static void share(Activity activity, Dump dump, boolean asJson) {
        if (activity == null || dump == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,
                activity.getString(R.string.share_subject, dump.getCardNumberAsString()));
        send.putExtra(Intent.EXTRA_TEXT, asJson ? dump.toJson() : dump.toText());
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_dump)));
    }
}
