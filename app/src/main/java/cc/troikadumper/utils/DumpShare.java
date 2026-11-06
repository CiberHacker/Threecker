package cc.troikadumper.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;

import cc.troikadumper.Dump;
import cc.troikadumper.R;

/**
 * Exports a dump through any app that handles plain text (messenger, mail, notes, ...).
 * No storage permission and no FileProvider are involved: the dump is sent as text.
 */
public final class DumpShare {

    private DumpShare() {
    }

    /**
     * Asks whether the dump should be shared as human readable text or as JSON.
     */
    public static void showChooser(final Activity activity, final Dump dump) {
        if (activity == null || dump == null) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.share_dump)
                .setItems(new CharSequence[]{
                        activity.getString(R.string.share_as_text),
                        activity.getString(R.string.share_as_json)
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        share(activity, dump, which == 1);
                    }
                })
                .show();
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
