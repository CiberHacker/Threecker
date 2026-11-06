package cc.troikadumper;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.troikadumper.utils.ThemeMode;

/**
 * Compares two saved dumps field by field (balance, card number, usage info and the raw blocks),
 * so the effect of any card operation can be inspected without diffing files by hand.
 */
public class DiffActivity extends AppCompatActivity {

    protected Spinner spinnerA;
    protected Spinner spinnerB;
    protected TextView diffText;

    protected String[] filenames = new String[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diff);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(R.string.diff_title);
            toolbar.setSubtitle(R.string.diff_subtitle);
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
        }

        diffText = (TextView) findViewById(R.id.diff_text);
        spinnerA = (Spinner) findViewById(R.id.spinner_dump_a);
        spinnerB = (Spinner) findViewById(R.id.spinner_dump_b);

        filenames = listDumps();
        if (filenames.length < 2) {
            diffText.setText(R.string.diff_need_two);
            spinnerA.setEnabled(false);
            spinnerB.setEnabled(false);
            return;
        }

        CharSequence[] labels = new CharSequence[filenames.length];
        for (int i = 0; i < filenames.length; i++) {
            labels[i] = labelFor(filenames[i]);
        }

        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerA.setAdapter(adapter);
        spinnerB.setAdapter(adapter);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                renderDiff();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // nothing to do
            }
        };
        spinnerA.setOnItemSelectedListener(listener);
        spinnerB.setOnItemSelectedListener(listener);

        // default: the newest dump on the right, the one before it on the left
        spinnerA.setSelection(1);
        spinnerB.setSelection(0);
        renderDiff();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * @return saved dump filenames, newest first.
     */
    protected String[] listDumps() {
        File dumpsDir = getExternalFilesDir(null);
        String[] names = (dumpsDir == null) ? null : dumpsDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                return filename.matches(Dump.FILENAME_REGEXP);
            }
        });
        if (names == null) {
            return new String[0];
        }
        List<String> list = new ArrayList<String>(Arrays.asList(names));
        Collections.sort(list);
        Collections.reverse(list);
        return list.toArray(new String[list.size()]);
    }

    /**
     * Short label for a dump file: "2026-11-03 13:04  55RUB" instead of the whole file name.
     */
    protected static String labelFor(String filename) {
        Matcher m = Pattern.compile(Dump.FILENAME_REGEXP).matcher(filename);
        if (!m.matches()) {
            return filename;
        }
        String time = m.group(4);
        return m.group(1) + "-" + m.group(2) + "-" + m.group(3)
                + " " + time.substring(0, 2) + ":" + time.substring(2, 4)
                + "  " + m.group(6) + "RUB";
    }

    protected void renderDiff() {
        if (filenames.length < 2) {
            return;
        }
        int positionA = spinnerA.getSelectedItemPosition();
        int positionB = spinnerB.getSelectedItemPosition();
        if (positionA < 0 || positionB < 0) {
            return;
        }
        try {
            Dump a = load(filenames[positionA]);
            Dump b = load(filenames[positionB]);
            diffText.setText(buildDiff(filenames[positionA], a, filenames[positionB], b));
        } catch (IOException e) {
            diffText.setText(getString(R.string.diff_error, e.toString()));
        }
    }

    protected Dump load(String filename) throws IOException {
        return Dump.fromFile(new File(getExternalFilesDir(null), filename));
    }

    protected String buildDiff(String nameA, Dump a, String nameB, Dump b) {
        List<DumpDiff.Row> rows = DumpDiff.compare(a, b);
        int changed = DumpDiff.countChanged(rows);

        StringBuilder body = new StringBuilder();
        for (DumpDiff.Row row : rows) {
            appendField(body, labelFor(row.getId()), row.getValueA(), row.getValueB());
        }

        StringBuilder result = new StringBuilder();
        result.append(changed == 0
                ? getString(R.string.diff_summary_none)
                : getString(R.string.diff_summary_changed, changed, rows.size()));
        result.append("\n\nA: ").append(nameA).append('\n');
        result.append("B: ").append(nameB).append("\n\n");
        return result.append(body).toString();
    }

    /**
     * @return the string resource that labels a {@link DumpDiff} row.
     */
    protected String labelFor(int id) {
        switch (id) {
            case DumpDiff.FIELD_UID:
                return getString(R.string.diff_field_uid);
            case DumpDiff.FIELD_CARD_NUMBER:
                return getString(R.string.diff_field_card_number);
            case DumpDiff.FIELD_BALANCE:
                return getString(R.string.diff_field_balance);
            case DumpDiff.FIELD_LAST_USAGE:
                return getString(R.string.diff_field_last_usage);
            case DumpDiff.FIELD_LAST_VALIDATOR:
                return getString(R.string.diff_field_last_validator);
            default:
                return getString(R.string.diff_field_block, id - DumpDiff.BLOCK_BASE);
        }
    }

    protected static void appendField(StringBuilder sb, String label, String a, String b) {
        boolean same = a.equals(b);
        sb.append(same ? "[=] " : "[!] ").append(label).append('\n');
        sb.append("      A: ").append(a).append('\n');
        if (!same) {
            sb.append("      B: ").append(b).append('\n');
        }
    }
}
