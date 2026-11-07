# CLAUDE.md

Agent-facing guide to this repository. Read this file before changing anything; it lists the
invariants that are easy to break and expensive to notice afterwards.

`BUILDING.md` is the human-facing build document (requirements, build/install commands, what was
fixed to make the project buildable). Keep both in sync when you change the build setup.

> **Git is off limits unless you are asked.** Never run `git add`, `git commit`, `git push`,
> `git tag`, `git reset`, `git checkout` or any other command that changes the index, the history or
> a remote on your own initiative - see rule 5 in section 2.

---

## 1. What this project is

**Threecker** is a small single-module Android app (Java) for Moscow **Troika** transit cards.
It talks to the card's **MIFARE Classic** chip over NFC, reads **sector 8** (plus sector 9 when
the card allows it), decodes the travel data (card number, balance, last usage time, last
validator), stores the raw sectors as a text "dump" file, and can write a dump back to the same
card (restore).

* It is a fork of the original `TroikaDumper` project, which was an Eclipse/IntelliJ "flat"
  layout with no build system (sources in `java/`, `res/`, manifest at repo root). That layout
  was migrated to a standard Gradle layout with `git mv`, so file history is preserved.
* The app brand is **Threecker**; the package / namespace / applicationId is now
  `com.ciber.threeker` (renamed from the original `cc.troikadumper`). The rename touched
  `namespace`/`applicationId` in `app/build.gradle`, every `package`/`import`, the source tree
  under `app/src/{main,test}/java/com/ciber/threeker/`, and the custom view class names in the XML
  layouts. A new applicationId is a new app to Android, so stored dumps now live under
  `Android/data/com.ciber.threeker/files/` (existing files were moved there by hand).

## 2. Non-negotiable rules

1. **Do not weaken the write path.** Writing a dump to a card is deliberately guarded by three
   steps: (a) a confirmation dialog listing card number / balance / last usage / dump UID,
   (b) `Dump.belongsTo(Tag)` - the target card UID must equal the dump UID, otherwise nothing is
   written, (c) `Dump.verify(Tag)` - the written sectors are read back and compared with the dump.
   Removing or bypassing any of these is a regression, not a simplification.
2. **Keep the data format.** The dump file format, its filename scheme and its regexp are what
   existing users' files on disk look like; `FILENAME_FORMAT` and `FILENAME_REGEXP` in
   `Dump.java` must change together and must keep matching old files.
3. **No new dependencies without a reason.** Only `androidx.appcompat`, Material Components and
   JUnit are declared. No Kotlin, no ViewBinding/DataBinding, no Compose, no DI, no Room - the app
   is a handful of `findViewById` activities by design.
4. **All new user-visible text goes to `res/values/strings.xml`** and is referenced as `R.string.*`
   (use positional format args, e.g. `%1$s`, for anything dynamic).
5. **It is FORBIDDEN to commit or push without an explicit request from the user.** Do not run
   `git add`, `git commit`, `git push`, `git tag`, `git reset`, `git stash`, `git checkout` or any
   other command that changes the index, the history or a remote unless the user asked for it in
   that conversation. Finishing a task, passing tests or a tidy `git status` are **not** a request.
   Leave the working tree as it is, report what changed, and wait: the user decides when the work
   is committed and where it is pushed.

## 3. Toolchain

| Item | Value |
|---|---|
| Language | Java 17 (`sourceCompatibility`/`targetCompatibility` 17), no Kotlin |
| Gradle | 8.11.1, via the checked-in wrapper (`gradlew`, `gradlew.bat`) |
| Android Gradle Plugin | 8.9.0 (declared in root `build.gradle`) |
| SDK levels | `compileSdk 35`, `minSdk 21`, `targetSdk 34` |
| Libraries | `androidx.appcompat:appcompat:1.7.0`, `com.google.android.material:material:1.12.0` |
| Test | `junit:junit:4.13.2`, JVM unit tests only (no instrumented tests) |
| SDK location | `local.properties` -> `sdk.dir=C:\CiberWork\SDK` (git-ignored, machine specific) |

Deliberate choices, do not "modernise" them casually:

* `minSdk 21` - AppCompat 1.7 / Material 1.12 require API 21, while the README still mentions
  Android 4.4.
* `targetSdk 34` (not 35) - Android 15's forced edge-to-edge layout would change the existing
  screens; bumping it requires re-validating every layout.
* `minifyEnabled false` for release; `app/proguard-rules.pro` is intentionally empty.

## 4. Commands (Windows PowerShell, repo root)

```powershell
# Gradle 8.11 does not support the JDK 25 that may be first on PATH; point it at a JDK 17:
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.1'

.\gradlew.bat assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk (~6.6 MB, debug-signed)
.\gradlew.bat assembleRelease      # -> app/build/outputs/apk/release/app-release-unsigned.apk (UNSIGNED)
.\gradlew.bat testDebugUnitTest    # JVM tests -> app/build/reports/tests/testDebugUnitTest/index.html
.\gradlew.bat clean assembleDebug  # full rebuild
```

```powershell
$adb = 'C:\CiberWork\SDK\platform-tools\adb.exe'
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -W -n com.ciber.threeker/.MainActivity
& $adb shell am force-stop com.ciber.threeker
& $adb logcat -d -b crash -t 20            # must stay empty after a smoke test
& $adb shell uiautomator dump /sdcard/ui.xml   # then: & $adb shell cat /sdcard/ui.xml
# label / package check:
C:\CiberWork\SDK\build-tools\35.0.0\aapt2.exe dump badging app\build\outputs\apk\debug\app-debug.apk
```

Expected from `aapt2`: `package: name='com.ciber.threeker'`, `application-label:'Threecker'`.

On Linux/macOS use `./gradlew` and `/path/to/adb`. On Windows use PowerShell semantics (`;` between
commands, backtick escapes), not `cmd`/`bash` syntax.

## 5. Repository layout

```
settings.gradle              repos + `rootProject.name = 'Threecker'` + `include ':app'`
build.gradle                 AGP 8.9.0 plugin declaration (apply false)
gradle.properties            android.useAndroidX, nonTransitiveRClass, daemon JVM args, parallel/caching
local.properties             sdk.dir - machine specific, git-ignored
gradle/wrapper/              Gradle 8.11.1 wrapper (checked in)
gradlew, gradlew.bat         wrapper launchers (checked in)

app/build.gradle             android { } block: namespace, SDK levels, buildTypes, deps
app/proguard-rules.pro       empty on purpose (minifyEnabled false)
app/src/main/AndroidManifest.xml
app/src/main/java/cc/troikadumper/
    MainActivity.java        single screen with 3 tabs: NFC dispatch, read/write, rendering
    CardFlipView.java        3D two-sided card container (front/back children, card aspect ratio)
    TroikaCardBackView.java  paints the card back artwork and the number/balance into its fields
    Dump.java                domain model: card IO, parsing, annotations, edits, file IO, text/JSON export
    DumpFile.java            the dump file format: v2 read/write (atomic) + legacy five-line reader
    DumpListSection.java     binds the Dumps tab: list, load, details/duplicate/delete on long press
    DumpListAdapter.java     BaseAdapter: header rows per card + dump rows (name/label/description)
    DumpGrouping.java        reads every dump file, groups it by the card number in its bytes (pure JVM)
    DumpDiff.java            field-by-field dump comparison rows (pure JVM, unit tested)
    DiffActivity.java        two spinners over the saved dumps + comparison text
    utils/HexUtils.java      byte[] <-> uppercase hex String
    utils/DumpMetadata.java  legacy sidecar (pre-v2 names), read-only migration helper
    utils/DumpDetailsDialog  name/description dialog; writes them into the dump file
    utils/DumpShare.java     ACTION_SEND chooser: the dump file (FileProvider), text or JSON
    utils/ThemeMode.java     persisted light/dark choice (AppCompatDelegate night mode)
app/src/main/res/
    layout/activity_main.xml       AppBarLayout + Toolbar + TabLayout + the 3 panels
    layout/panel_overwrite.xml     status pill, balance card, fields, raw sector block, buttons
    layout/panel_dumps.xml         hint + grouped ListView (@id/dump_list_view) + empty state
    layout/panel_info.xml          About / write warnings / Diff tool button
    layout/dump_list_item.xml      dump row: optional name, date+balance label, optional description
    layout/dump_list_header.xml    card-number group header
    layout/activity_diff.xml       2 labeled Spinners + terminal-style diff text
    layout/dialog_dump_details.xml name/description dialog
    values/strings.xml             all user-visible text
    values/{colors,dimens,styles}.xml
    values-v21/styles.xml          status bar color on API 21+
    values-w820dp/dimens.xml       tablet margins
    xml/nfc_tech_filter.xml        NfcA + MifareClassic tech list
    drawable/bg_status.xml         rounded status pill background
    drawable/bg_terminal.xml       dark rounded "raw sector" background
    drawable/ic_theme_dark.xml     moon icon (switch to dark)
    drawable/ic_theme_light.xml    sun icon (switch to light)
    drawable/ic_chevron.xml        collapse/expand indicator of a card header
    drawable/ic_close.xml          "close the loaded dump" icon
    drawable/ic_edit.xml           per-field edit icon
    drawable-nodpi/troika_card_front.png, troika_card_back.png  card artwork (857x540, no scaling)
    menu/menu_main.xml             one action: the light/dark theme toggle
    values-night/colors.xml        full dark palette, same color names as values/
    mipmap-*/                      launcher icons
app/src/test/java/cc/troikadumper/
    DumpTest.java            parsing / filename / export / file format v2 / sector 9 / edits / restores (40 tests)
    DumpGroupingTest.java    reading, grouping, ordering, annotations, migration (15 tests)
    DumpDiffTest.java        comparison rows (4 tests)
    utils/DumpMetadataTest.java  sidecar annotations (9 tests)
    utils/HexUtilsTest.java  hex conversion (6 tests)

BUILDING.md                  build/install instructions + what had to be fixed
README.md                    original Russian user guide (UTF-8, Cyrillic - see gotcha 11)
CLAUDE.md                    this file
```

Generated and git-ignored: `app/build/`, `build/`, `.gradle/`, `local.properties`, `.idea/`,
`*.iml`. Never commit them; `.idea/` is recreated by Android Studio on every sync.

The Gradle restructure was done with `git mv`, so `git status` may show entries like
`RM java/cc/troikadumper/Dump.java -> app/src/main/java/cc/troikadumper/Dump.java`. Commit those
renames as-is (do not delete + re-add files) so blame/history survives.

## 6. Architecture and runtime flow

Two activities, no services, no fragments, no database: dumps are plain files in the
app-specific external directory, and the only model object is `Dump`. `MainActivity` hosts the
whole tabbed UI; `DiffActivity` is the only other screen.

| Type | Responsibility |
|---|---|
| `MainActivity` | Single screen: tabs (Overwrite/Dumps/About), NFC foreground dispatch, read from card/file, guarded write, renders the dump |
| `DumpListSection` | Binds the Dumps tab: refresh from disk, tap = load, long press = details/duplicate/delete |
| `DumpListAdapter` | `BaseAdapter`: one header row per card number + dump rows (name / date+balance / description) |
| `DiffActivity` | Two spinners over the dump files + field-by-field comparison |
| `Dump` | Raw `uid` + `byte[4][16]` sector 8 data (+ optional sector 9 blocks), parsing, annotations, edits (`original` blocks), `fromTag`/`fromFile`/`save`/`saveChanges`/`write`/`verify`, `toText`/`toJson` |
| `DumpFile` | The dump file format: `read` (v2 + legacy), `serialize`, atomic `write` (pure JVM, no Android deps) |
| `DumpGrouping` | Reads every `*.txt` dump (`read`), parses its bytes, groups by the parsed card number (pure JVM, no Android deps) |
| `DumpDiff` | Builds the comparison rows for `DiffActivity` (pure JVM) |
| `DumpMetadata` | Legacy sidecar `dump_metadata.properties`; read-only, used to migrate pre-v2 names |
| `DumpDetailsDialog` | Name/description dialog; writes them into the dump file via `Dump.updateDetails` |
| `DumpShare` | `ACTION_SEND`: the dump file through `FileProvider`, or text/JSON |
| `HexUtils` | Hex encoding used by the dump format and all on-screen output |

### NFC entry points

`MainActivity` enables `NfcAdapter.enableForegroundDispatch` with a `MifareClassic` tech list in
`onResume` (see `setupForegroundDispatch`) and disables it in `onPause`. A tapped card therefore
arrives as `ACTION_TECH_DISCOVERED` at `onNewIntent` (activity already running) or at `onCreate`
(first launch). Both funnel into `handleIntent(Intent)`.

`handleIntent` branches only on `NfcAdapter.ACTION_TECH_DISCOVERED`: it takes the `Tag` from
`EXTRA_TAG`, then calls `writeMode && dump != null` ? `writeDumpToCard(tag)` : `readFromCard(tag)`.
Whatever tab is open, a read switches the UI to the Overwrite tab.

### Loading a dump from the Dumps tab

`DumpListSection` owns the `ListView` and calls back through its `Listener` with a file **name**
(not a path); `MainActivity.openDump(filename)` reads `Dump.fromFile`, renders it and switches to
the Overwrite tab. There is no activity result contract any more - `INTENT_READ_DUMP`,
`REQUEST_OPEN_DUMP` and `onActivityResult` were removed with `DumpListActivity`.

### Write sequence (order matters)

1. The primary **Write to card** button (`@id/btn_write`) in the Overwrite panel -> `confirmWrite()`.
2. Confirmation `AlertDialog` (`dialog_write_title` / `dialog_write_message`). Confirming sets
   `writeMode = true` and shows an indeterminate `ProgressDialog` ("Waiting for card...").
   Cancelling either reset `writeMode`.
3. Next card tap: `writeDumpToCard(tag)` -> UID pre-check `dump.belongsTo(tag)`; on mismatch an
   `AlertDialog` (`error_uid_mismatch_*`) is shown and nothing is written.
4. `dump.write(tag)` first writes sector 9 when the dump has it (aborting before touching
   anything if that sector cannot be authenticated), then the first `BLOCK_COUNT - 1` blocks of
   sector 8; the trailers that hold the keys are never written.
5. `dump.verify(tag)` re-reads those blocks; the result selects `status_written_verified` or
   `status_written_unverified` in the status pill.
6. `IOException` -> `showError(error_write_title, ...)`; the loaded dump is kept so the user can
   retry.

### Read sequence

`Dump.fromTag(tag)` authenticates sector 8, reads its 4 blocks, then reads sector 9's three data
blocks (null when the card refuses) and builds a `Dump` (whose
constructor parses the fields), then `saveDump()` stores the raw text file and `renderDump()`
fills the Overwrite tab. A save failure is reported separately and **keeps the dump loaded**, so
the card data can still be inspected or written back. Reading from a file (`Dump.fromFile`) skips
saving and renders directly.

## 7. Card model: MIFARE Classic, sector 8

Constants in `Dump.java` (all of them are load-bearing):

| Constant | Value | Meaning |
|---|---|---|
| `SECTOR_INDEX` | `8` | the only sector the app touches; it carries the Troika travel data |
| `BLOCK_COUNT` | `4` | blocks read from / written to that sector |
| `BLOCK_SIZE` | `MifareClassic.BLOCK_SIZE` | 16 bytes |
| `KEY_A` / `KEY_B` | app keys | sector 8 keys of a normal Troika card |
| `KEY_0` | all zero | factory key of a blank/"fucked up" card |

`getMifareClassic(tag)` connects and authenticates sector 8, trying `KEY_0` for both A and B first
(blank card), then `KEY_A` + `KEY_B`; anything else throws `IOException("No permissions")`. Every
read, write and verify goes through it, and the `MifareClassic` handle is always released in a
`finally` via `closeQuietly`.

Field layout, from `parse()` (bytes are indexed `data[block][byte]`):

| Field | Source | Formula |
|---|---|---|
| `cardNumber` | `data[0][3..6]` | `intval(...) >> 4`, printed as `"%04d %03d %03d"` by `formatCardNumber` |
| `lastValidatorId` | `data[1][0..1]` | `intval(...)` - **known to be the wrong field**, see the `TODO` in `parse()` |
| `lastUsageDate` | `data[1][0..2]` | `minutesDelta = intval(...) >> 1`; `0` -> `null` ("<NEVER USED>"), else `2018-12-31 00:00 GMT+3 + minutesDelta` |
| `balance` | `data[1][5..6]` | `intval(data[1][5], data[1][6]) / 25`, integer RUB |

`intval(byte...)` is a big-endian accumulation (`Math.pow(0x100, i)`) that normalises negative
bytes first. `parse()` runs in the constructor, so a `Dump` never holds half-parsed state - that is
also why the unit tests can construct one directly from synthetic bytes without NFC.

Persisted facts about writing:

* `write(tag)` writes blocks 0..2 only; block 3 is the sector trailer (keys + access bits).
* `write(tag)` repeats the `belongsTo` check and throws `IOException` on a UID mismatch, even if
  `MainActivity` already checked - keep both.
* `verify(tag)` compares the same three blocks against `data` and returns a boolean; it never
  throws for a mere mismatch.

## 8. Dump files on disk

* Directory: `getApplicationContext().getExternalFilesDir(null)`, i.e.
  `/sdcard/Android/data/com.ciber.threeker/files/`. App-specific external storage, so **no storage
  permission is involved** and the app uninstalls cleanly. It can be `null` (unmounted storage) -
  the list screens handle that, `save()` throws `IOException`.
* Name: `FILENAME_FORMAT = "%04d-%02d-%02d_%02d%02d%02d_%d_%dRUB.txt"`, i.e.
  `2026-11-03_135522_30050636_430RUB.txt` (date, time, card number, balance). The name is a
  convenience for the user (`adb pull`, file managers) - **nothing in the app reads it**, with one
  exception: the scan time of a pre-v2 file, which has no timestamp of its own
  (`DumpFile.legacyScanTime`). Card number and balance always come from the dump bytes.
* `FILENAME_REGEXP` matches that written name; it is no longer used to filter the directory or to
  build labels. The list scans the directory for `*.txt` (`DumpGrouping.EXTENSION`), which keeps
  externally renamed dumps visible. Change `FILENAME_FORMAT` and `FILENAME_REGEXP` together and
  never orphan existing files.
* Content (format **v2**, written and read by `DumpFile`): one self-contained UTF-8 text file per
  dump, `key: value` per line, text values escaped (`\\`, `\n`, `\r`):

  ```
  threecker-dump v2
  uid: 0446855A594D80
  sector: 8
  scanned: 2026-11-06T12:38:32+0300
  name: School card                        <- optional
  description: line one\nline two          <- optional
  block0: 45DB100A0F51FE196BA2A40000000000
  block1: ... (block2, block3)
  original0: ... (original1..3, only while the dump is edited)
   sector9_block0: ... (sector9_block1..2, optional - sector 9 when readable)
  ```

  Unknown keys are ignored, so the format can grow. `blockN` is the only source of truth: `name`,
  `description` and `scanned` are annotations, and the card number/balance are always re-parsed from
  the blocks. A line-based format (not JSON) keeps the file readable, diffable and hand-editable,
  needs no dependency and still runs in plain JVM tests - `org.json` is only a stub there.
* Old files (five bare lines: UID, then blocks 0..3) are still read by `DumpFile.parseLegacy`; their
  scan time comes from the (app generated) name, and their name/description from the legacy sidecar
  (`DumpMetadata`, merged by `DumpGrouping.read`). The first save folds all of it into the file;
  **nothing is rewritten at startup**.
* Writing is atomic (`DumpFile.write`): the content goes to `<name>.tmp`, is read back and compared,
  and only then renamed over the target - an interrupted annotation edit can never destroy a dump.
  Never write a dump file another way (the old in-place `FileOutputStream` is gone on purpose).
* `saveCopy(dir)` backs "Duplicate": same content (blocks, name, description, scan time, original
  blocks) under a fresh `FILENAME_FORMAT` name of the current time; when that name is taken (same
  second) the timestamp advances by a second, so an existing dump is never overwritten. No sidecar
  entry is needed any more.

## 9. Export formats and sharing

`Dump.toText()` - human readable, starts with `Threecker dump` and lists UID, card number, balance,
last usage, last validator, then `Sector #8:` and `N] <hex>` per block. This header string is the
brand name; if the app is ever renamed again, update it here too.

`Dump.toJson()` - hand-written JSON (no JSON library in the project), keys:

```json
{
  "format": "troika-dump-v1",
  "uid": "04AA0102",
  "cardNumber": "0019 088 743",
  "balance": 20,
  "lastUsageDate": "2026-11-03T13:55:22+0300" | null,
  "lastValidatorId": 258,
  "sector": 8,
  "blocks": ["...", "...", "...", "..."]
}
```

`format` is a stable format id for external consumers, not the app name - do not rename it to
"threecker-dump-v1" without a migration story. `cardNumber` is the formatted string (with spaces),
while `balance`, `lastValidatorId` and `sector` are raw numbers; `lastUsageDate` is
`yyyy-MM-dd'T'HH:mm:ssZ` in `Locale.US` or `null` for a never-used card.

The dump **file** (section 8) is the richest export and the only one that can come back into the
app: `Share -> As file` sends it through `androidx.core.content.FileProvider`
(authority `${applicationId}.dumps`, paths in `res/xml/file_paths.xml`), so the receiver gets the
blocks, the name, the description, the scan time and - for an edited dump - the scanned blocks.
`toJson()`/`toText()` are exports for humans and tools; they are not the file format.

Sharing is text only: `DumpShare.share(activity, dump, asJson)` builds an `ACTION_SEND` intent with
type `text/plain`, subject `@string/share_subject` and the body from `toText()`/`toJson()`. There is
no `FileProvider` and no storage permission by design - do not switch to `ACTION_SEND` with a file
unless you also add the provider and its `res/xml` path config.

`share_subject` says "Troika dump, card %1$s" (the *card* brand, plus the app was renamed to
Threecker while the card stayed Troika) - that is intentional.

## 10. UI layer

One screen, three top tabs. All three panels are inflated at once and toggled with
`View.VISIBLE`/`GONE` (no ViewPager, no fragments):

```
OVERWRITE  red NFC card, status pill with close X, card flip, hero balance + field editors,
           raw sector 8, Write to card / Share
DUMPS      hint + grouped ListView (all groups start collapsed; tap header = expand, tap dump =
           load into Overwrite, long press = details/duplicate/delete)
ABOUT      app description, write warnings, Diff tool -> DiffActivity
```

* `MainActivity` is the single NFC host, so a card tap works from every tab; a read always selects
  the Overwrite tab (`selectTab(TAB_OVERWRITE)`).
* The Toolbar shows `@string/app_name` + `@string/app_subtitle` and one action icon (moon/sun) from
  `res/menu/menu_main.xml`, which flips the light/dark theme through `utils/ThemeMode`. There is no
  overflow menu - every other action lives in the tabs.
* Overwrite panel (`panel_overwrite.xml`): `@id/nfc_off_card` (red error card, **first** element of
  the panel), `@id/status_pill` (pill with `@id/status_text` and the `@id/btn_close_dump` X),
  `@id/empty_state` (shown when `dump == null`), `@id/dump_details` (card, balance hero, last usage,
  validator, UID, saved name, `@id/value_blocks` raw hex), `@id/btn_write`,
  `@id/btn_share`. `renderDump()` is the only place that fills those views and toggles
  `btn_close_dump` / `btn_edit_saved_as`; the pill text is set separately by `setStatus(...)`.
* The red NFC panel is the authoritative "NFC is off" message: `updateNfcState()` shows it and hides
  the neutral pill while the pill holds a state message; a real result ("Dump loaded: ...") keeps
  the pill (and its X) visible even with NFC off.
* Per-field editors: the balance hero, last usage, last validator and saved-as rows each have an
  `ic_edit` button (`btn_edit_balance` / `btn_edit_usage` / `btn_edit_validator` /
  `btn_edit_saved_as`). The balance icon sits next to the big value, not next to the 11sp label: a
  40dp icon in the label row inflated that row and pushed the label ~12dp down. Balance/validator use
  numeric dialogs, usage uses `DatePickerDialog` + `TimePickerDialog` (pass
  `R.style.Theme_Threecker_Picker` to both - the platform pickers ignore the theme attribute and
  would render black OK/Cancel), saved-as opens `DumpDetailsDialog`. Every edit is written to the
  dump file immediately (`persistEdits()` -> `Dump.saveChanges`), so the file never lags behind the
  screen. Editing does **not** touch the status pill: it keeps showing "Dump loaded: ...".
* Per-field restore: while a value differs from the scanned one, an `ic_restore` button appears next
  to that field's edit icon (`btn_restore_balance` / `btn_restore_usage` / `btn_restore_validator`,
  shown from `renderDump()` via `Dump.isBalanceChanged()` / `isLastPassChanged()`). It rolls back
  only that value (`Dump.restoreBalance()` / `restoreLastPass()`) and rewrites the file. Usage and
  validator share one 24-bit field, so restoring either restores both - that is why both icons show
  up together. `Dump.isEdited()` (whole blocks) still drives the "· edited" marker in the list and
  the `originalN` lines in the file.
* Card: `CardFlipView` expects exactly two children - the front face and the back
  `TroikaCardBackView` - and derives its height from the 85.6x54 mm ratio. The front face is a
  `FrameLayout` holding the artwork plus the `@id/card_flip_hint` pill (`bg_card_hint`,
  `card_hint_text`), so the hint sits on the card's top-right corner and disappears together with
  the face when the card is flipped. It clips to a rounded card outline (3.7% of the width,
  matching a real card) and adds a small elevation shadow. A tap runs a `rotationY` animation
  (450 ms) and swaps the faces at 90/270 degrees.
  `TroikaCardBackView` draws `drawable-nodpi/troika_card_back.png` and prints the card number /
  balance into the normalized white fields (number: x 0.078-0.457, y 0.133-0.248; balance:
  x 0.078-0.431, y 0.641-0.904); the square field between them is intentionally left empty. The
  X in the status pill calls `closeDump()` (unloads, never deletes), and the flipped state is saved
  in `onSaveInstanceState`.
* Dumps panel (`panel_dumps.xml`): `@id/dump_list_view` + `@id/dump_list_empty`
  (`ListView.setEmptyView`), driven by `DumpListSection`/`DumpListAdapter`. `refresh()` calls
  `DumpGrouping.read(dir)`, i.e. **every dump file is parsed on every refresh** (app start, tab
  switch, after delete/duplicate); the file name is never used for card number, balance or time
  (the time comes from `File.lastModified()`). Files that fail to parse become unreadable entries in
  a trailing "Unreadable files" group: tapping one shows the parse error, the long-press menu offers
  only *Delete*. Rows are `MaterialCardView`s, so the adapter must be built with the **activity**
  context - an application context carries the platform theme and crashes while inflating
  `?attr/...`. A tap on a card header calls `DumpListAdapter.toggleGroup(position)`; the expanded
  card numbers live in `expandedCards`, which starts **empty**, so every group opens collapsed and
  the state survives `refresh()`. `expandGroup(cardNumber)` is used after a duplicate so the new row
  is visible. A long press on a readable dump offers details / duplicate / delete, and delete asks
  for confirmation before removing the file and calling `DumpMetadata.remove(...)`.
* About panel (`panel_info.xml`): version from `PackageManager`, the write warnings,
  `@id/btn_diff_tool` -> `DiffActivity`.
* `DiffActivity`: two spinners over the newest-first filename list; fewer than two dumps disables
  both and shows `diff_need_two`. Defaults are `spinnerA = 1`, `spinnerB = 0`, i.e. A is the second
  newest dump and B the newest. Output: `diff_summary_*`, `A:`/`B:` filenames, then
  `[=]`/`[!] <field>` lines with the A value and, when different, the B value.
* Dialogs: write confirmation (`dialog_write_*`), wrong card (`error_uid_mismatch_*`), balance edit
  (`dialog_edit_balance_*`), dump details (`dialog_details_*`), share chooser, and an indeterminate
  `ProgressDialog` (`waiting_for_card`). Errors go through `showError(title, message)`.
* Theme: `AppTheme` is `Theme.MaterialComponents.DayNight.NoActionBar`; the palette is in
  `values/colors.xml` with a **full** override in `values-night/colors.xml` (same color names).
  `ThemeMode` stores the explicit light/dark choice in the `settings` preferences and defaults to
  the system setting. `AppCompatDelegate.setDefaultNightMode` recreates the activity, so
  `MainActivity.onSaveInstanceState` keeps the tab, the status line and the loaded dump
  (UID + block hex + file name) and `restoreState(...)` rebuilds it - never drop that.
  `alertDialogTheme` / `materialAlertDialogTheme` point at `Theme.Threecker.Dialog`, which sets
  `colorPrimary`/`colorAccent` to `accent_text`; without it dialog buttons inherit the dark night
  `colorPrimary` and become unreadable.
* NFC state: when the adapter is off, `nfc_off_card` appears in the Overwrite panel with
  `@id/btn_enable_nfc` (deep link to `Settings.ACTION_NFC_SETTINGS`), and `maybePromptForNfc()` asks
  once per launch. `nfcStateReceiver` (`ACTION_ADAPTER_STATE_CHANGED`, registered in `onResume`)
  plus `updateNfcState()` keep the pill in sync without any restart.
* Cards use `Widget.Threecker.Card`, field typography uses `TextAppearance.Threecker.Label/Value`.
  Outlined/text buttons use `Widget.Threecker.Button.Outlined` / `.Text`, whose text and stroke come
  from `@color/accent_text` - `colorPrimary` is too dark for text in the night palette. The filled
  action (`btn_write`) uses `Widget.Threecker.Button.Primary` with an explicit
  `android:textColor=@color/on_primary`, because the inherited `colorOnPrimary` is black and made
  the button look disabled.
* Text: every user-visible string lives in `values/strings.xml` - do not add hardcoded strings.

## 11. Testing

* Location: `app/src/test/java/...` (JVM tests only; there are no instrumented/androidTest tests).
* Current state: **74 tests** - `DumpTest` (40), `DumpGroupingTest` (15), `DumpMetadataTest` (9),
  `HexUtilsTest` (6), `DumpDiffTest` (4) - all green.
* Run: `.\gradlew.bat testDebugUnitTest`, HTML report in
  `app/build/reports/tests/testDebugUnitTest/index.html`.
* Covered: `Dump.intval`, `Dump.formatCardNumber`, parsing of card number / balance / validator /
  usage date (including the never-used `null` case), `FILENAME_REGEXP` (positive and negative
  cases), `toText`/`toJson` content, hex encoding/decoding and its odd-length rejection.
* What can be tested: everything that does not need NFC. `new Dump(uid, blocks)` runs `parse()` and
  touches no Android API, so synthetic `byte[4][16]` fixtures are the normal test style (see the
  `block(int...)` helper in `DumpTest`). `Tag`, `MifareClassic` and `Environment` require a device
  or a mocking library that the project does not have - do not add tests that instantiate them.
* `testOptions.unitTests.includeAndroidResources = true` is set, so resource access from JVM tests
  is possible if ever needed.
* Anything involving a real card (read, write, verify, UID mismatch) is verified by hand on the
  physical cards: the spare card for destructive tests (restorable from a dump), the working card
  for real-balance checks - see the manual checklist in section 14.

## 12. Conventions

* Java, 4-space indent, K&R braces, one top-level class per file. Match the surrounding style:
  anonymous `new View.OnClickListener() { ... }` classes are used instead of lambdas, `protected`
  fields/methods on activities, `findViewById` + `(Type)` casts (no ViewBinding).
* New user-visible text -> `res/values/strings.xml`, referenced as `R.string.*`, with positional
  args for dynamic values. Do not hardcode English strings in new code.
* Errors: catch `IOException` (or the specific exception), show an `AlertDialog` through
  `showError(...)`, append the technical detail to the status `TextView`, and keep the loaded
  `Dump` intact so the user can retry.
* Card handles: always `getMifareClassic(tag)` inside the method, always `closeQuietly(mfc)` in a
  `finally` block. Never leak a connected `MifareClassic`.
* Nullability: `getExternalFilesDir(null)`, `getSystemService`, `getSupportActionBar`,
  `intent.getAction()` are all nullable here and are already guarded - keep new code equally
  defensive instead of adding `@NonNull` assumptions.
* Comments and identifiers in English. Javadoc on new non-trivial public methods (`Dump.write`,
  `belongsTo`, `verify` are documented examples).
* Resource names: `snake_case` ids/strings/colors, layout files `activity_*` / `*_item`.

## 13. Gotchas

1. **JDK on PATH may be too new.** Gradle 8.11 rejects JDK 25. Set
   `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.1'` before `gradlew`. Android Studio uses its
   bundled JBR and does not need this.
2. **Package vs brand.** `com.ciber.threeker` everywhere in code/build (renamed from
   `cc.troikadumper`); `Threecker` only in user-visible text. Custom views in XML must use the
   real package: `<com.ciber.threeker.CardFlipView>`, not `com.troikadumper...`
   (the wrong prefix fails at runtime with `ClassNotFoundException`, not at build time).
3. **The manifest has no `package` attribute.** AGP 8 rejects it; the package name comes from
   `namespace` in `app/build.gradle`. Adding it back breaks the build.
4. **`android:exported` is mandatory** on activities with intent filters (targetSdk 31+), otherwise
   the manifest merger fails. `MainActivity` is `true`; `DiffActivity` is `false`.
5. **`PendingIntent.FLAG_MUTABLE` on API 31+** in `setupForegroundDispatch`. Without it, tapping a
   card crashes the app: the NFC stack has to add the `Tag` extras to the intent.
6. **`targetSdk` is pinned to 34.** Bumping it to 35 enables Android 15's forced edge-to-edge
   layout, which changes how every existing screen looks. Only bump together with a layout pass
   and a device check.
7. **`minSdk 21`** is a hard floor for AppCompat 1.7 / Material 1.12. Do not lower it back to 19.
8. **The release APK is unsigned** (`app-release-unsigned.apk`) - it cannot be installed as-is.
   Debug builds are signed with the debug keystore. Add `signingConfigs` if a release must be
   installed, and never commit a keystore or password.
9. **`getExternalFilesDir(null)` can return `null`** (storage unmounted). Listing code must handle
   it; `Dump.save()` throws `IOException` instead of crashing.
10. **`Dump.fromFile` closes its stream** (`FileInputStream`/`Scanner` in a `finally`) - keep it that
    way, and do not change the 5-line file layout while editing it.
11. **`README.md` is UTF-8 with Cyrillic text.** Tools that default to the Windows ANSI codepage
    rewrite it into mojibake. Edit it with UTF-8-preserving tooling and keep the diff to the
    intended lines; the file intentionally stays in Russian.
12. **Broken XML breaks resource compilation fast.** `values-v21/styles.xml` once started with
    `<resources>>` (stray `>`); a similar typo fails the build with a cryptic AAPT2 error. Always
    build after touching XML.
13. **Deprecated APIs are used deliberately**: `Date.getYear()/getMonth()/getDate()`,
    `new Date(y,m,d,h,min,s)`, `ProgressDialog`, and the AppCompat `AlertDialog`. They match the
    legacy behaviour; modernise them only in a dedicated change with a device check.
14. **Dump files are UTF-8 text (since format v2)** and may contain non-ASCII names/descriptions;
    `DumpFile.escape` turns `\\`, `\r` and `\n` into escapes, so a value never breaks the line
    layout. Do not write a BOM and do not put a raw newline into a value. Legacy five-line files are
    still read.
15. **`intval` builds an `int` via `Math.pow`**, so values above 2^31 overflow. Card fields are far
    smaller; do not reuse it for arbitrary-length values.
16. **`DumpGrouping.read` parses every dump file, `group` sorts a copy**, so the caller's list is
    untouched; entries are newest first by `Entry.getTimestamp()` (the scan time stored in the file,
    falling back to the file timestamp) and the unreadable group comes last. Never derive the card
    number or balance from the file name - a renamed or hand-edited file must still show its real
    content. `DumpListAdapter` is a `BaseAdapter` with header rows - disabled items are headers, so
    taps ignore them.
17. **Changing `applicationId` has consequences; casual version bumps too.** Same id = in-place
    update that keeps the user's dumps; a new id looks like a different app and loses them - the
    planned package rename has to account for that. `versionCode`/`versionName` should not be
    bumped without a reason.
18. **No NFC on emulators and no way to simulate a tag over adb.** Card read/write/verify paths are
    exercised by hand with physical Troika cards: a spare card covers read/write/verify and the
    UID-mismatch dialog (restore it from a dump if anything goes wrong), the working card covers
    checks that need a real balance change. Run the test and report the observed result - do not
    skip it by claiming hardware is unavailable.
19. **The app does not log.** There is no `Log.*` usage; failures are shown in the UI (dialog +
    status text). After a smoke test, `adb logcat -d -b crash` must be empty.
20. **Every color used by a layout must exist in `values-night/colors.xml` too.** A missing night
    color silently falls back to the light value (dark-on-dark text). `dump_header_text` exists for
    exactly that reason. Test both themes after touching the palette.
21. **`setDefaultNightMode` recreates activities.** Anything that must survive the theme toggle has
    to be in `onSaveInstanceState` (see `MainActivity.restoreState`).
22. **Last usage and validator ID share one 24-bit field** (`data[1][0..2]`): the value is
    `(minutes << 1) | flag`, the validator is its high 16 bits. `setLastUsageDate` and
    `setLastValidatorId` therefore change both decoded values; do not "fix" one of them to be
    independent without new evidence from a real card.
23. **A duplicate must never overwrite the original.** The dump name only has one-second
    resolution, so duplicating twice within a second yields the same `FILENAME_FORMAT` string;
    `saveCopy()` walks the timestamp forward until the name is free. Keep the name ending in
    `.txt` (`DumpGrouping.EXTENSION`), otherwise the list will not show the copy.
24. **A dump file is replaced only through `DumpFile.write`** (tmp file, read back, rename). Name,
    description, field edits and per-field restores all rewrite the dump itself, so a non-atomic
    write would risk the user's data. `originalN` lines must only exist while the blocks differ from
    them.
25. **The FileProvider authority is `${applicationId}.dumps`** (manifest) and must match
    `DumpShare.AUTHORITY_SUFFIX`; its paths (`res/xml/file_paths.xml`, `external-files-path` = the
    dumps directory) must cover the dumps, or `getUriForFile` throws and "As file" silently fails.

## 14. Verification checklist

Before handing work back:

1. Build passes: `.\gradlew.bat assembleDebug` (with `JAVA_HOME` set as in section 4).
2. `.\gradlew.bat testDebugUnitTest` - 40+ tests green (add tests when you add pure logic).
3. If the APK changed: `adb install -r app\build\outputs\apk\debug\app-debug.apk`, then
   `adb shell am start -W -n com.ciber.threeker/.MainActivity` and confirm `Status: ok`.
4. `adb logcat -d -b crash -t 20` is empty.
5. `aapt2 dump badging ...` still reports `package: name='com.ciber.threeker'` and
   `application-label:'Threecker'`.
6. For UI changes: `adb shell uiautomator dump /sdcard/ui.xml` (and/or screenshots) to confirm the
   expected labels are on screen; check all three tabs, with and without a loaded dump, and in both
   light and dark themes (the toolbar icon toggles them).
7. For NFC/write changes: run them on the physical cards - spare card first (load a dump,
   **Write to card** -> confirm -> tap the same card; tap a *different* card to see the
   UID-mismatch dialog; restore the spare from a dump if needed), working card for real balance
   changes. Describe the steps and the observed result.
8. Delete artefacts you created: screenshots (`_*.png`), redirected logs (`_r.txt`, `_re.txt`),
   `/sdcard/ui.xml`, and any synthetic dumps pushed to the device or left in the repo.
9. `git status` / `git diff` review: no `build/`, no `local.properties`, no `.idea/`, no temp files;
   renames still show as renames.

## 15. Workflow for an agent working here

1. Read this file and `BUILDING.md` first; then `Dump.java` and `MainActivity.java` - they hold most
   of the behaviour.
2. Search before you add: look for the string/id/method you intend to create, so you reuse
   `HexUtils`, `DumpShare`, `showError`, `renderDump`, `FILENAME_REGEXP`, etc.
3. Make the smallest change that solves the task, in the existing style, with no new dependencies
   and without touching SDK levels, dump format or the write-safety chain (the package rename is
   a separate, explicit task).
4. Build, run the unit tests, and install + smoke test on a device when behaviour or UI changed;
   NFC changes are additionally tested on the physical cards (spare card first).
5. Update the docs you invalidated: `BUILDING.md` (build/config/toolchain), `README.md`
   (user-facing behaviour, keep UTF-8), `res/values/strings.xml` (text), and this file when the
   structure, commands, invariants or gotchas change.
6. Commit only when asked. When committing, keep the `git mv` renames intact, write a message that
   says what changed and why, and never commit generated output.

## 16. Local environment (as of the last change)

| Item | Value |
|---|---|
| OS / shell | Windows, PowerShell (`powershell.exe`) |
| JDK used for Gradle | `C:\Program Files\Java\jdk-17.0.1` |
| Android SDK | `C:\CiberWork\SDK` (`local.properties` -> `sdk.dir`) |
| Build tools | `C:\CiberWork\SDK\build-tools\35.0.0` |
| adb | `C:\CiberWork\SDK\platform-tools\adb.exe` |
| Test device | realme RMX3823, Android 16 (API 36), NFC present |
| Last verified | `assembleDebug` + `assembleRelease` + `clean assembleDebug` succeed; 74/74 unit tests; app installs, launches and renders every screen without crashing |

If the SDK or JDK moves, update `local.properties` (never commit it) and sections 4 and 16 here.

## 17. mt-apk MCP - patching the official Metro app

An **mt-apk MCP server** (tools prefixed `mt_apk_`) is available for when we need to know what the
official **MoskowMetro** app (`ru.mosmetro.metro`) does with a card: it opens, searches, edits and
rebuilds APKs in place.

| Item | Value |
|---|---|
| APK folder | `/storage/emulated/0/MT2/mcp/` (MT Manager's folder on the test device) |
| Pristine source | `MoskowMetro_4.5.10_apks_kill.apk` - keep untouched |
| Instrumented build | `MoskowMetro_4.5.10_nfclog2.apk` - currently installed |

The instrumented build routes every patch log through `PatchLog`, which writes to **logcat and**
the file `Android/data/ru.mosmetro.metro/files/nfcpatch.log` (the file survives logcat rollover):

| Tag | Captures |
|---|---|
| `NfcPatch` | app start, log-file path, init/write errors |
| `NfcReadLog` | read sessions (requested sectors), per-block payloads `READ ... b64=`, failed attempts |
| `NfcWriteLog` | write sessions (which sectors the official flow writes), per-block payloads, failures |
| `NfcKeyLog` | every authentication: sector, key type, Base64 key value |

Capture with `adb logcat -d -s NfcPatch NfcKeyLog NfcReadLog NfcWriteLog`, or simply read the
file. A free balance check already yields the READ payloads and all key values; WRITE payloads
require a real top-up. mt-apk edit workspaces do not survive a restart - build and install
promptly; the built APKs stay on the device.

Findings baked into Threecker from this instrumentation: the official top-up writes **sectors 9
and 8** (sector 9 first, six blocks, trailers untouched), the server key catalog matches the
constants in `Dump.java` (sector 8 KeyB == `KEY_B`), and sector 9 KeyB == `KEY_9`.
