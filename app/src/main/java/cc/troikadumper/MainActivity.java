package cc.troikadumper;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;

import cc.troikadumper.utils.DumpDetailsDialog;
import cc.troikadumper.utils.DumpMetadata;
import cc.troikadumper.utils.DumpShare;
import cc.troikadumper.utils.HexUtils;
import cc.troikadumper.utils.ThemeMode;

/**
 * The single screen of the app: three tabs, no menu and no floating buttons.
 *
 * <ul>
 *   <li><b>Overwrite</b> - NFC reads a card, shows the parsed fields and the raw sector, and
 *       writes a loaded dump back to the card it came from (guarded by the confirmation dialog,
 *       the UID check in {@link Dump#belongsTo(Tag)} and the read-back in {@link Dump#verify(Tag)}).</li>
 *   <li><b>Dumps</b> - the saved dump files grouped by card, handled by {@link DumpListSection}.</li>
 *   <li><b>About</b> - what the app does, the write warnings and the diff tool entry point.</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    protected static final int TAB_OVERWRITE = 0;
    protected static final int TAB_DUMPS = 1;
    protected static final int TAB_ABOUT = 2;

    protected static final String STATE_TAB = "tab";
    protected static final String STATE_STATUS = "status";
    protected static final String STATE_DUMP_UID = "dump_uid";
    protected static final String STATE_DUMP_BLOCKS = "dump_blocks";
    protected static final String STATE_DUMP_FILE = "dump_file";
    protected static final String STATE_CARD_FLIPPED = "card_flipped";

    protected TabLayout tabLayout;
    protected View panelOverwrite;
    protected View panelDumps;
    protected View panelAbout;
    protected View emptyState;
    protected View dumpDetails;
    protected TextView statusText;
    protected TextView valueBalance;
    protected TextView valueCardNumber;
    protected TextView valueLastUsage;
    protected TextView valueLastValidator;
    protected TextView valueUid;
    protected TextView valueSavedAs;
    protected TextView valueBlocks;
    protected TextView aboutVersion;
    protected MaterialButton btnWrite;
    protected MaterialButton btnShare;
    protected ImageButton btnCloseDump;
    protected ImageButton btnEditSavedAs;
    protected View statusPill;
    protected ListView dumpListView;

    protected NfcAdapter nfcAdapter;
    protected Dump dump;
    /** File the loaded dump was read from or saved to; null while no dump is loaded. */
    protected File dumpFile;
    protected boolean writeMode = false;
    protected ProgressDialog pendingWriteDialog;
    protected View nfcOffCard;
    protected CardFlipView cardFlip;
    protected TroikaCardBackView cardBack;

    /** Follows the system NFC on/off switch while this screen is in front. */
    protected final BroadcastReceiver nfcStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
                updateNfcState();
            }
        }
    };

    protected boolean nfcPromptShown = false;

    protected DumpListSection dumpListSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.setSubtitle(R.string.app_subtitle);
        setSupportActionBar(toolbar);

        bindViews();
        setupTabs();
        setupWriteDialog();
        setupActions();
        setupDumpList();
        showVersion();

        ((TextView) findViewById(R.id.raw_title))
                .setText(getString(R.string.section_raw, Dump.SECTOR_INDEX));

        setupNfc();
        renderDump();
        restoreState(savedInstanceState);
        if (savedInstanceState == null) {
            maybePromptForNfc();
        }

        Intent startIntent = getIntent();
        // getAction() is null when the activity is started without an action (e.g. "am start -n
        // pkg/.MainActivity"), so compare against the constant, never against the intent's action.
        String startAction = (startIntent == null) ? null : startIntent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(startAction)) {
            handleIntent(startIntent);
        }
    }

    protected void bindViews() {
        tabLayout = (TabLayout) findViewById(R.id.tab_layout);
        panelOverwrite = findViewById(R.id.panel_overwrite);
        panelDumps = findViewById(R.id.panel_dumps);
        panelAbout = findViewById(R.id.panel_info);
        emptyState = findViewById(R.id.empty_state);
        dumpDetails = findViewById(R.id.dump_details);
        statusText = (TextView) findViewById(R.id.status_text);
        valueBalance = (TextView) findViewById(R.id.value_balance);
        valueCardNumber = (TextView) findViewById(R.id.value_card_number);
        valueLastUsage = (TextView) findViewById(R.id.value_last_usage);
        valueLastValidator = (TextView) findViewById(R.id.value_last_validator);
        valueUid = (TextView) findViewById(R.id.value_uid);
        valueSavedAs = (TextView) findViewById(R.id.value_saved_as);
        valueBlocks = (TextView) findViewById(R.id.value_blocks);
        aboutVersion = (TextView) findViewById(R.id.about_version);
        btnWrite = (MaterialButton) findViewById(R.id.btn_write);
        btnShare = (MaterialButton) findViewById(R.id.btn_share);
        btnCloseDump = (ImageButton) findViewById(R.id.btn_close_dump);
        btnEditSavedAs = (ImageButton) findViewById(R.id.btn_edit_saved_as);
        statusPill = findViewById(R.id.status_pill);
        dumpListView = (ListView) findViewById(R.id.dump_list_view);
        nfcOffCard = findViewById(R.id.nfc_off_card);
        cardFlip = (CardFlipView) findViewById(R.id.card_flip);
        cardBack = (TroikaCardBackView) findViewById(R.id.card_back);
    }

    protected void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_overwrite));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_dumps));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_about));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // nothing to do
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // nothing to do
            }
        });
        showTab(TAB_OVERWRITE);
    }

    /**
     * Shows exactly one panel. All three stay inflated, so list contents survive a tab switch.
     */
    protected void showTab(int position) {
        panelOverwrite.setVisibility((position == TAB_OVERWRITE) ? View.VISIBLE : View.GONE);
        panelDumps.setVisibility((position == TAB_DUMPS) ? View.VISIBLE : View.GONE);
        panelAbout.setVisibility((position == TAB_ABOUT) ? View.VISIBLE : View.GONE);
        if (position == TAB_DUMPS && dumpListSection != null) {
            dumpListSection.refresh();
        }
    }

    /**
     * Switches tabs programmatically (e.g. after a card was tapped on another tab).
     */
    protected void selectTab(int position) {
        if (tabLayout == null) {
            return;
        }
        TabLayout.Tab tab = tabLayout.getTabAt(position);
        if (tab == null) {
            return;
        }
        if (tab.getPosition() == tabLayout.getSelectedTabPosition()) {
            showTab(position);
        } else {
            tab.select();
        }
    }

    protected void setupWriteDialog() {
        pendingWriteDialog = new ProgressDialog(this);
        pendingWriteDialog.setIndeterminate(true);
        pendingWriteDialog.setMessage(getString(R.string.waiting_for_card));
        pendingWriteDialog.setCancelable(true);
        pendingWriteDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                writeMode = false;
                setStatus(R.string.status_tap_card);
            }
        });
    }

    protected void setupActions() {
        btnWrite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmWrite();
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareDump();
            }
        });
        MaterialButton btnDiffTool = (MaterialButton) findViewById(R.id.btn_diff_tool);
        btnDiffTool.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiffActivity.class));
            }
        });
        MaterialButton btnEnableNfc = (MaterialButton) findViewById(R.id.btn_enable_nfc);
        btnEnableNfc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNfcSettings();
            }
        });
        cardFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cardFlip.toggle();
            }
        });
        ImageButton btnEditBalance = (ImageButton) findViewById(R.id.btn_edit_balance);
        btnEditBalance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editBalance();
            }
        });
        ImageButton btnEditUsage = (ImageButton) findViewById(R.id.btn_edit_usage);
        btnEditUsage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editLastUsage();
            }
        });
        ImageButton btnEditValidator = (ImageButton) findViewById(R.id.btn_edit_validator);
        btnEditValidator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editLastValidator();
            }
        });
        btnEditSavedAs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editSavedAs();
            }
        });
        btnCloseDump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDump();
            }
        });
    }

    /**
     * Unloads the current dump. Nothing on disk or on the card is changed; the screen simply
     * returns to the empty state so another card or dump can be opened.
     */
    protected void closeDump() {
        dump = null;
        dumpFile = null;
        cardFlip.setFlipped(false, false);
        renderDump();
        setStatus(R.string.status_tap_card);
        updateNfcState();
    }

    protected void setupDumpList() {
        dumpListView.setEmptyView(findViewById(R.id.dump_list_empty));
        dumpListSection = new DumpListSection(this, dumpListView,
                new DumpListSection.Listener() {
                    @Override
                    public void onDumpSelected(String filename) {
                        openDump(filename);
                    }

                    @Override
                    public void onDumpDeleted(String filename) {
                        handleDeletedDump(filename);
                    }
                });
    }

    /**
     * Drops the loaded dump when its file was deleted from the Dumps tab, so the screen never
     * offers to write data whose file is gone.
     */
    protected void handleDeletedDump(String filename) {
        if (dumpFile != null && filename.equals(dumpFile.getName())) {
            dump = null;
            dumpFile = null;
            renderDump();
        }
        setStatus(getString(R.string.status_dump_deleted, filename));
    }

    protected void showVersion() {
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            aboutVersion.setText(getString(R.string.about_version, version));
        } catch (PackageManager.NameNotFoundException e) {
            aboutVersion.setVisibility(View.GONE);
        }
    }

    protected void setupNfc() {
        NfcManager nfcManager = (NfcManager) getSystemService(Context.NFC_SERVICE);
        nfcAdapter = (nfcManager == null) ? null : nfcManager.getDefaultAdapter();
        updateNfcState();
    }

    /**
     * Mirrors the current NFC state in the UI. Called at startup, on every resume (i.e. when the
     * user comes back from the system settings) and from {@link #nfcStateReceiver} when the switch
     * is flipped while the screen is visible - so a restart is never needed.
     */
    protected void updateNfcState() {
        boolean hasAdapter = (nfcAdapter != null);
        boolean enabled = hasAdapter && nfcAdapter.isEnabled();
        boolean problem = !hasAdapter || !enabled;
        nfcOffCard.setVisibility(problem ? View.VISIBLE : View.GONE);

        if (problem) {
            // The red panel at the top carries the message; hide the neutral pill unless it shows
            // an operation result (e.g. a dump loaded while NFC is off).
            if (isStateStatus(statusText.getText())) {
                setStatus(hasAdapter ? R.string.error_nfc_is_disabled : R.string.error_no_nfc);
                statusPill.setVisibility(View.GONE);
            }
            return;
        }

        if (isStateStatus(statusText.getText())) {
            setStatus(R.string.status_tap_card);
        }
        statusPill.setVisibility(View.VISIBLE);
    }

    /**
     * @return true when the status pill shows a generic state rather than an operation result.
     */
    protected boolean isStateStatus(CharSequence status) {
        return getString(R.string.status_tap_card).contentEquals(status)
                || getString(R.string.error_nfc_is_disabled).contentEquals(status)
                || getString(R.string.error_no_nfc).contentEquals(status);
    }

    /**
     * Asks once per launch whether to open the NFC settings when NFC is off. Never blocks the UI:
     * the inline card in the Overwrite tab stays available either way.
     */
    protected void maybePromptForNfc() {
        if (nfcPromptShown || nfcAdapter == null || nfcAdapter.isEnabled()) {
            return;
        }
        nfcPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_nfc_title)
                .setMessage(R.string.dialog_nfc_message)
                .setPositiveButton(R.string.dialog_nfc_open,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                openNfcSettings();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    protected void openNfcSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    /**
     * Renders the loaded dump (or the empty state) into the Overwrite tab. The status line is not
     * touched here - it belongs to the action that produced the current state.
     */
    protected void renderDump() {
        boolean hasDump = (dump != null);
        emptyState.setVisibility(hasDump ? View.GONE : View.VISIBLE);
        dumpDetails.setVisibility(hasDump ? View.VISIBLE : View.GONE);
        btnWrite.setEnabled(hasDump);
        btnShare.setEnabled(hasDump);
        btnCloseDump.setVisibility(hasDump ? View.VISIBLE : View.GONE);
        btnEditSavedAs.setVisibility((hasDump && dumpFile != null) ? View.VISIBLE : View.GONE);
        if (!hasDump) {
            return;
        }
        valueBalance.setText(dump.getBalanceAsString());
        valueCardNumber.setText(dump.getCardNumberAsString());
        valueLastUsage.setText(dump.getLastUsageDateAsString());
        valueLastValidator.setText(dump.getLastValidatorIdAsString());
        valueUid.setText(dump.getUidAsString());
        valueSavedAs.setText(savedDumpName());
        valueBlocks.setText(formatBlocks(dump));
        cardBack.setCardData(dump.getCardNumberAsString(),
                getString(R.string.card_balance_amount, dump.getBalance()));
    }

    /**
     * @return the user given name of the dump file, or its file name, or "Not saved".
     */
    protected String savedDumpName() {
        if (dumpFile == null) {
            return getString(R.string.value_not_saved);
        }
        String name = new DumpMetadata(getApplicationContext().getExternalFilesDir(null))
                .getName(dumpFile.getName());
        return (name != null) ? name : dumpFile.getName();
    }

    protected static String formatBlocks(Dump d) {
        StringBuilder sb = new StringBuilder();
        String[] blocks = d.getDataAsStrings();
        for (int i = 0; i < blocks.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i).append("] ").append(blocks[i]);
        }
        return sb.toString();
    }

    protected void setStatus(int resId) {
        statusText.setText(resId);
        statusPill.setVisibility(View.VISIBLE);
    }

    protected void setStatus(CharSequence text) {
        statusText.setText(text);
        statusPill.setVisibility(View.VISIBLE);
    }

    private void handleIntent(Intent intent) {
        String action = (intent == null) ? null : intent.getAction();

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (writeMode && dump != null) {
                writeDumpToCard(tag);
            } else {
                readFromCard(tag);
            }
        }
    }

    protected void readFromCard(Tag tag) {
        selectTab(TAB_OVERWRITE);
        setStatus(R.string.read_in_progress);
        try {
            dump = Dump.fromTag(tag);
            dumpFile = null;
            renderDump();
        } catch (IOException e) {
            dump = null;
            dumpFile = null;
            renderDump();
            setStatus(R.string.status_read_failed);
            showError(getString(R.string.error_read_title), e.getMessage());
            return;
        }
        saveDump();
    }

    /**
     * Stores the freshly read dump next to the others. A storage failure is reported, but the
     * dump stays loaded so it can still be written back to the card.
     */
    protected void saveDump() {
        try {
            dumpFile = dump.save(getApplicationContext().getExternalFilesDir(null));
            renderDump();
            setStatus(getString(R.string.status_read_ok, dumpFile.getName()));
            if (dumpListSection != null) {
                dumpListSection.refresh();
            }
        } catch (IOException e) {
            dumpFile = null;
            renderDump();
            setStatus(getString(R.string.status_read_ok_not_saved, e.getMessage()));
            showError(getString(R.string.error_save_title), e.getMessage());
        }
    }

    /**
     * Loads a dump from the Dumps tab into the Overwrite tab.
     *
     * @param filename dump file name (not a path).
     */
    protected void openDump(String filename) {
        File dumpsDir = getApplicationContext().getExternalFilesDir(null);
        if (dumpsDir == null) {
            setStatus(R.string.status_file_failed);
            showError(getString(R.string.error_read_file_title), null);
            return;
        }
        setStatus(R.string.read_from_file_in_progress);
        try {
            File file = new File(dumpsDir, filename);
            dump = Dump.fromFile(file);
            dumpFile = file;
            renderDump();
            selectTab(TAB_OVERWRITE);
            setStatus(getString(R.string.status_file_loaded, filename));
        } catch (IOException e) {
            dump = null;
            dumpFile = null;
            renderDump();
            setStatus(R.string.status_file_failed);
            showError(getString(R.string.error_read_file_title), e.getMessage());
        }
    }

    /**
     * Writes the loaded dump back to the card. The card UID is checked first, so a dump can only
     * ever be written to the card it was taken from, and the data is read back afterwards.
     */
    protected void writeDumpToCard(Tag tag) {
        if (pendingWriteDialog.isShowing()) {
            pendingWriteDialog.dismiss();
        }
        writeMode = false;

        if (dump == null) {
            renderDump();
            return;
        }

        try {
            if (!dump.belongsTo(tag)) {
                setStatus(R.string.status_wrong_card);
                showError(getString(R.string.error_uid_mismatch_title),
                        getString(R.string.error_uid_mismatch_message,
                                dump.getUidAsString(), HexUtils.toString(tag.getId())));
                return;
            }

            setStatus(R.string.write_in_progress);
            dump.write(tag);
            boolean verified = dump.verify(tag);
            renderDump();
            setStatus(verified
                    ? R.string.status_written_verified
                    : R.string.status_written_unverified);
        } catch (IOException e) {
            setStatus(R.string.status_write_failed);
            showError(getString(R.string.error_write_title), e.getMessage());
        }
    }

    protected void confirmWrite() {
        if (dump == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_write_title)
                .setMessage(getString(R.string.dialog_write_message,
                        dump.getCardNumberAsString(),
                        dump.getBalanceAsString(),
                        dump.getLastUsageDateAsString(),
                        dump.getUidAsString()))
                .setPositiveButton(R.string.dialog_write_confirm,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                writeMode = true;
                                setStatus(R.string.waiting_for_card);
                                pendingWriteDialog.show();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Lets the user change the balance of the loaded dump. The new value is encoded into the raw
     * block data of the dump, so it can be written back to the card with the regular write flow.
     */
    protected void editBalance() {
        if (dump == null) {
            return;
        }

        final EditText input = new EditText(this);
        input.setText(String.valueOf(dump.getBalance()));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_edit_balance_title)
                .setMessage(R.string.dialog_edit_balance_message)
                .setView(input)
                .setPositiveButton(R.string.action_set,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String text = input.getText().toString().trim();
                                if (text.isEmpty()) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.error_invalid_balance,
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                try {
                                    dump.setBalance(Integer.parseInt(text));
                                    renderDump();
                                    setStatus(getString(R.string.status_balance_updated,
                                            dump.getBalanceAsString()));
                                } catch (NumberFormatException e) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.error_invalid_balance,
                                            Toast.LENGTH_SHORT).show();
                                } catch (IllegalArgumentException e) {
                                    Toast.makeText(MainActivity.this,
                                            e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Edits the last usage time (date first, then time). See
     * {@link Dump#setLastUsageDate(Date)} for how the value is encoded.
     */
    protected void editLastUsage() {
        if (dump == null) {
            return;
        }
        final Calendar picked = Calendar.getInstance();
        Date usage = dump.getLastUsageDate();
        if (usage != null) {
            picked.setTime(usage);
        }

        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                picked.set(year, month, dayOfMonth);
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                        picked.set(Calendar.HOUR_OF_DAY, hourOfDay);
                        picked.set(Calendar.MINUTE, minute);
                        picked.set(Calendar.SECOND, 0);
                        picked.set(Calendar.MILLISECOND, 0);
                        try {
                            dump.setLastUsageDate(picked.getTime());
                            renderDump();
                            setStatus(getString(R.string.status_usage_updated,
                                    dump.getLastUsageDateAsString()));
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(MainActivity.this, e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }, picked.get(Calendar.HOUR_OF_DAY), picked.get(Calendar.MINUTE), true).show();
            }
        }, picked.get(Calendar.YEAR), picked.get(Calendar.MONTH),
                picked.get(Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * Edits the last validator ID. It is stored in the same 24-bit field as the usage time, so the
     * decoded time changes too; see {@link Dump#setLastValidatorId(int)}.
     */
    protected void editLastValidator() {
        if (dump == null) {
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(dump.getLastValidatorId()));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_edit_validator_title)
                .setMessage(R.string.dialog_edit_validator_message)
                .setView(input)
                .setPositiveButton(R.string.action_set,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    dump.setLastValidatorId(Integer.parseInt(
                                            input.getText().toString().trim()));
                                    renderDump();
                                    setStatus(getString(R.string.status_validator_updated,
                                            dump.getLastValidatorIdAsString()));
                                } catch (NumberFormatException e) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.error_invalid_validator,
                                            Toast.LENGTH_SHORT).show();
                                } catch (IllegalArgumentException e) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.error_invalid_validator,
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Edits the user name and description of the loaded dump. Only possible once it is saved,
     * because the annotations are keyed by the file name.
     */
    protected void editSavedAs() {
        if (dumpFile == null) {
            return;
        }
        DumpMetadata metadata =
                new DumpMetadata(getApplicationContext().getExternalFilesDir(null));
        DumpDetailsDialog.show(this, metadata, dumpFile.getName(),
                new DumpDetailsDialog.OnSavedListener() {
                    @Override
                    public void onDetailsSaved() {
                        renderDump();
                        setStatus(R.string.details_saved);
                    }
                });
    }

    protected void shareDump() {
        DumpShare.showChooser(this, dump);
    }

    protected void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage((message == null) ? getString(R.string.error_unknown) : message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.action_toggle_theme);
        if (item != null) {
            boolean dark = ThemeMode.isDark(this);
            item.setIcon(dark ? R.drawable.ic_theme_light : R.drawable.ic_theme_dark);
            item.setTitle(dark
                    ? R.string.action_switch_to_light
                    : R.string.action_switch_to_dark);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_theme) {
            // setDefaultNightMode recreates the activity; the state is preserved below.
            ThemeMode.toggle(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (tabLayout != null) {
            outState.putInt(STATE_TAB, tabLayout.getSelectedTabPosition());
        }
        if (statusText != null) {
            outState.putCharSequence(STATE_STATUS, statusText.getText());
        }
        if (dump != null) {
            outState.putString(STATE_DUMP_UID, dump.getUidAsString());
            outState.putStringArray(STATE_DUMP_BLOCKS, dump.getDataAsStrings());
            outState.putString(STATE_DUMP_FILE, (dumpFile == null) ? null : dumpFile.getName());
        }
        if (cardFlip != null) {
            outState.putBoolean(STATE_CARD_FLIPPED, cardFlip.isFlipped());
        }
    }

    /**
     * Restores the tab, the status line and the loaded dump after a configuration change. The
     * theme toggle recreates the activity, and the card data must not disappear there.
     */
    protected void restoreState(Bundle state) {
        if (state == null) {
            return;
        }

        String uid = state.getString(STATE_DUMP_UID);
        String[] blocks = state.getStringArray(STATE_DUMP_BLOCKS);
        if (uid != null && blocks != null && blocks.length == Dump.BLOCK_COUNT) {
            try {
                byte[][] data = new byte[blocks.length][Dump.BLOCK_SIZE];
                for (int i = 0; i < blocks.length; i++) {
                    data[i] = HexUtils.fromString(blocks[i]);
                }
                dump = new Dump(HexUtils.fromString(uid), data);
                File dir = getApplicationContext().getExternalFilesDir(null);
                String name = state.getString(STATE_DUMP_FILE);
                dumpFile = (dir == null || name == null) ? null : new File(dir, name);
                renderDump();
            } catch (IllegalArgumentException e) {
                dump = null;
                dumpFile = null;
                renderDump();
            }
        }

        CharSequence status = state.getCharSequence(STATE_STATUS);
        if (status != null) {
            setStatus(status);
        }
        cardFlip.setFlipped(state.getBoolean(STATE_CARD_FLIPPED, false), false);
        selectTab(state.getInt(STATE_TAB, TAB_OVERWRITE));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // It is important that the activity is in the foreground (resumed); otherwise an
        // IllegalStateException is thrown.
        if (nfcAdapter != null) {
            setupForegroundDispatch(this, nfcAdapter);
            ContextCompat.registerReceiver(this, nfcStateReceiver,
                    new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        if (dumpListSection != null) {
            dumpListSection.refresh();
        }
        // Coming back from the system NFC settings lands here, so the state is re-read.
        updateNfcState();
    }

    @Override
    protected void onPause() {
        // Call this before onPause, otherwise an IllegalArgumentException is thrown as well.
        if (nfcAdapter != null) {
            stopForegroundDispatch(this, nfcAdapter);
            try {
                unregisterReceiver(nfcStateReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was not registered (e.g. onPause without onResume).
            }
        }

        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Called when a tag is attached while the activity is already running.
        handleIntent(intent);
    }

    /**
     * @param activity The corresponding {@link Activity} requesting the foreground dispatch.
     * @param adapter The {@link NfcAdapter} used for the foreground dispatch.
     */
    public static void setupForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        final Intent intent = new Intent(activity.getApplicationContext(), activity.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Android 12 (API 31) and newer require an explicit mutability flag. The NFC stack has to
        // fill in the Tag extras when the tag is discovered, so this PendingIntent must be mutable.
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntentFlags |= PendingIntent.FLAG_MUTABLE;
        }

        final PendingIntent pendingIntent = PendingIntent.getActivity(
                activity.getApplicationContext(), 0, intent, pendingIntentFlags);

        IntentFilter[] filters = new IntentFilter[1];
        String[][] techList = new String[][]{
                new String[]{MifareClassic.class.getName()}
        };

        // Notice that this is the same filter as in our manifest.
        filters[0] = new IntentFilter();
        filters[0].addAction(NfcAdapter.ACTION_TECH_DISCOVERED);
        filters[0].addCategory(Intent.CATEGORY_DEFAULT);
        try {
            filters[0].addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("Check your mime type.");
        }

        adapter.enableForegroundDispatch(activity, pendingIntent, filters, techList);
    }

    /**
     * @param activity The corresponding {@link Activity} requesting to stop the foreground dispatch.
     * @param adapter The {@link NfcAdapter} used for the foreground dispatch.
     */
    public static void stopForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        adapter.disableForegroundDispatch(activity);
    }
}
