# CLAUDE.md

Agent-facing guide to this repository. Read this file before changing anything; it lists the
invariants that are easy to break and expensive to notice afterwards.

`BUILDING.md` is the human-facing build document (requirements, build/install commands, what was
fixed to make the project buildable). Keep both in sync when you change the build setup.

> **Git is off limits unless you are asked.** Never run `git add`, `git commit`, `git push`,
> `git tag`, `git reset`, `git checkout` or any other command that changes the index, the history or
> a remote on your own initiative - see rule 6 in section 2.

---

## 1. What this project is

**Threecker** is a small single-module Android app (Java) for Moscow **Troika** transit cards.
It talks to the card's **MIFARE Classic** chip over NFC, reads **sector 8**, decodes the travel
data (card number, balance, last usage time, last validator), stores the raw sector as a text
"dump" file, and can write a dump back to the same card (restore).

* It is a fork of the original `TroikaDumper` project, which was an Eclipse/IntelliJ "flat"
  layout with no build system (sources in `java/`, `res/`, manifest at repo root). That layout
  was migrated to a standard Gradle layout with `git mv`, so file history is preserved.
* The app brand is **Threecker**; the **package / namespace / applicationId is
  `cc.troikadumper` and must stay that way** (see rule 1).

## 2. Non-negotiable rules

1. **Do not rename the package.** `namespace 'cc.troikadumper'`, `applicationId 'cc.troikadumper'`,
   every `package cc.troikadumper;`, every `import cc.troikadumper.*`, and
   `cc.troikadumper:id/...` resource ids stay untouched. Changing `applicationId` breaks in-place
   app updates (users lose their saved dumps) and every `R` reference in the source.
   The brand name `Threecker` lives only in user-visible text (`@string/app_name`, docs, and the
   `toText()` header - see section 9).
2. **Do not weaken the write path.** Writing a dump to a card is deliberately guarded by three
   steps: (a) a confirmation dialog listing card number / balance / last usage / dump UID,
   (b) `Dump.belongsTo(Tag)` - the target card UID must equal the dump UID, otherwise nothing is
   written, (c) `Dump.verify(Tag)` - sector 8 is read back and compared with the dump. Removing or
   bypassing any of these is a regression, not a simplification.
3. **Keep the data format.** The dump file format, its filename scheme and its regexp are what
   existing users' files on disk look like; `FILENAME_FORMAT` and `FILENAME_REGEXP` in
   `Dump.java` must change together and must keep matching old files.
4. **No new dependencies without a reason.** Only `androidx.appcompat`, Material Components and
   JUnit are declared. No Kotlin, no ViewBinding/DataBinding, no Compose, no DI, no Room - the app
   is a handful of `findViewById` activities by design.
5. **All new user-visible text goes to `res/values/strings.xml`** and is referenced as `R.string.*`
   (use positional format args, e.g. `%1$s`, for anything dynamic).
6. **It is FORBIDDEN to commit or push without an explicit request from the user.** Do not run
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
& $adb shell am start -W -n cc.troikadumper/.MainActivity
& $adb shell am force-stop cc.troikadumper
& $adb logcat -d -b crash -t 20            # must stay empty after a smoke test
& $adb shell uiautomator dump /sdcard/ui.xml   # then: & $adb shell cat /sdcard/ui.xml
# label / package check:
C:\CiberWork\SDK\build-tools\35.0.0\aapt2.exe dump badging app\build\outputs\apk\debug\app-debug.apk
```

Expected from `aapt2`: `package: name='cc.troikadumper'`, `application-label:'Threecker'`.

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
    Dump.java                domain model: card IO, parsing, file IO, text/JSON export
    DumpListSection.java     binds the Dumps tab: list, load, details/duplicate/delete on long press
    DumpListAdapter.java     BaseAdapter: header rows per card + dump rows (name/label/description)
    DumpGrouping.java        groups dump filenames by card number (pure JVM, unit tested)
    DumpDiff.java            field-by-field dump comparison rows (pure JVM, unit tested)
    DiffActivity.java        two spinners over the saved dumps + comparison text
    utils/HexUtils.java      byte[] <-> uppercase hex String
    utils/DumpMetadata.java  sidecar Properties file: name/description per dump
    utils/DumpDetailsDialog  name/description dialog for one dump
    utils/DumpShare.java     ACTION_SEND chooser (plain text or JSON)
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
    DumpTest.java            parsing / filename / export / setBalance / field editors / copies (21 tests)
    DumpGroupingTest.java    grouping and ordering (7 tests)
    DumpDiffTest.java        comparison rows (4 tests)
    utils/DumpMetadataTest.java  sidecar annotations (9 tests)
    utils/HexUtilsTest.java  hex conversion (6 tests)

BUILDING.md                  build/install instructions + what had to be fixed
README.md                    original Russian user guide (UTF-8, Cyrillic - see gotcha 11)
supported_phones_list.txt    NFC-capable phone list linked from README
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
| `Dump` | Raw `uid` + `byte[4][16]` sector data, parsing, `fromTag`/`fromFile`/`save`/`write`/`verify`, `toText`/`toJson` |
| `DumpGrouping` | Groups and orders dump filenames by card number (pure JVM, no Android deps) |
| `DumpDiff` | Builds the comparison rows for `DiffActivity` (pure JVM) |
| `DumpMetadata` | Sidecar `dump_metadata.properties`: name and description per dump file |
| `DumpDetailsDialog` | Name/description dialog, used from the Dumps tab |
| `DumpShare` | Builds the `ACTION_SEND` intent: text or JSON, no files, no `FileProvider` |
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
4. `dump.write(tag)` writes the first `BLOCK_COUNT - 1` blocks of sector 8; the sector trailer that
   holds the keys is never written.
5. `dump.verify(tag)` re-reads those blocks; the result selects `status_written_verified` or
   `status_written_unverified` in the status pill.
6. `IOException` -> `showError(error_write_title, ...)`; the loaded dump is kept so the user can
   retry.

### Read sequence

`Dump.fromTag(tag)` authenticates sector 8, reads its 4 blocks and builds a `Dump` (whose
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
  `/sdcard/Android/data/cc.troikadumper/files/`. App-specific external storage, so **no storage
  permission is involved** and the app uninstalls cleanly. It can be `null` (unmounted storage) -
  the list screens handle that, `save()` throws `IOException`.
* Name: `FILENAME_FORMAT = "%04d-%02d-%02d_%02d%02d%02d_%d_%dRUB.txt"`, i.e.
  `2026-11-03_135522_30050636_430RUB.txt` (date, time, card number, balance).
  Date/time are zero padded, so a plain string sort is chronological - which is exactly what the
  list screens rely on (`Arrays.sort` + reverse = newest first).
* `FILENAME_REGEXP` mirrors the name for filtering the directory and for building labels. It is
  deliberately loose about card number and balance (`[0-9]+`) so old files still match.
  Change `FILENAME_FORMAT` and `FILENAME_REGEXP` together and never orphan existing files.
* Content: exactly 5 ASCII lines, CRLF:
  `UID hex`, then `block 0..3` as 32 uppercase hex chars each. `save()` and `saveCopy()` write that
  layout (`writeTo`), `fromFile()` reads exactly 5 lines with `Scanner(..., "US-ASCII")`. Extra
  trailing lines are ignored, missing lines throw. Do not add a BOM, headers or comments to this
  file - it is the durable user data of the app.
* `saveCopy(dir)` backs "Duplicate": it writes the same bytes under a fresh `FILENAME_FORMAT` name
  of the current time, and when that name is taken (same second) it advances the timestamp by a
  second until free, so an existing dump is never overwritten. The caller copies the name and
  description with `DumpMetadata.put(copyName, ...)` (the name gets `@string/dump_duplicate_name`).

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
  40dp icon in the label row inflated that row and pushed the label ~12dp down. They change the
  **in-memory** dump only; the file and the card are written
  by the regular write flow. Balance/validator use numeric dialogs, usage uses
  `DatePickerDialog` + `TimePickerDialog`, saved-as opens `DumpDetailsDialog`. Usage and validator
  share one 24-bit field (see `Dump.parse`), so editing either changes the other.
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
  (`ListView.setEmptyView`), driven by `DumpListSection`/`DumpListAdapter`. Rows are
  `MaterialCardView`s, so the adapter must be built with the **activity** context - an application
  context carries the platform theme and crashes while inflating `?attr/...`. A tap on a card
  header calls `DumpListAdapter.toggleGroup(position)`; the expanded card numbers live in
  `expandedCards`, which starts **empty**, so every group opens collapsed and the state survives
  `refresh()`. `expandGroup(cardNumber)` is used after a duplicate so the new row is visible. A
  long press offers details / duplicate / delete, and delete asks for confirmation before removing
  the file and calling `DumpMetadata.remove(...)`.
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
* Current state: **47 tests** - `DumpTest` (21), `DumpGroupingTest` (7), `DumpDiffTest` (4),
  `DumpMetadataTest` (9), `HexUtilsTest` (6) - all green.
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
* Anything involving a real card (read, write, verify, UID mismatch) can only be verified by hand
  on hardware - see the manual checklist in section 14.

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
2. **Package vs brand.** `cc.troikadumper` everywhere in code/build; `Threecker` only in
   user-visible text. A "clean rename" of the package is a breaking change (see rule 1). Custom views
   in XML must use the real package: `<cc.troikadumper.CardFlipView>`, not `com.troikadumper...`
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
14. **Dump files are `US-ASCII`.** Never write non-ASCII text (or a BOM) into them - `fromFile`
    reads with `Scanner(fs, "US-ASCII")`.
15. **`intval` builds an `int` via `Math.pow`**, so values above 2^31 overflow. Card fields are far
    smaller; do not reuse it for arbitrary-length values.
16. **`DumpGrouping` sorts a copy**, so the caller's `String[]` is untouched; rows are ordered
    newest first and groups with an unknown card number come last. `DumpListAdapter` is a
    `BaseAdapter` with header rows - disabled items are headers, so taps ignore them.
17. **Never change `applicationId` or bump `versionCode`/`versionName` casually.** Same id = in-place
    update that keeps the user's dumps; a new id looks like a different app and loses them.
18. **No NFC on emulators and no way to simulate a tag over adb.** Card read/write/verify paths can
    only be exercised by a human with a physical Troika card. Review them carefully and state that
    they were not executed.
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
    `saveCopy()` walks the timestamp forward until the name is free. Keep the name inside
    `FILENAME_REGEXP`, otherwise the list (which filters by it) will not show the copy.

## 14. Verification checklist

Before handing work back:

1. Build passes: `.\gradlew.bat assembleDebug` (with `JAVA_HOME` set as in section 4).
2. `.\gradlew.bat testDebugUnitTest` - 40+ tests green (add tests when you add pure logic).
3. If the APK changed: `adb install -r app\build\outputs\apk\debug\app-debug.apk`, then
   `adb shell am start -W -n cc.troikadumper/.MainActivity` and confirm `Status: ok`.
4. `adb logcat -d -b crash -t 20` is empty.
5. `aapt2 dump badging ...` still reports `package: name='cc.troikadumper'` and
   `application-label:'Threecker'`.
6. For UI changes: `adb shell uiautomator dump /sdcard/ui.xml` (and/or screenshots) to confirm the
   expected labels are on screen; check all three tabs, with and without a loaded dump, and in both
   light and dark themes (the toolbar icon toggles them).
7. For NFC/write changes: describe the manual steps (load a dump, **Write to card** -> confirm -> tap
   the same card; tap a *different* card to see the UID-mismatch dialog) and say explicitly that
   they were not executed without hardware.
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
   and without touching the package name, SDK levels, dump format or the write-safety chain.
4. Build, run the unit tests, and install + smoke test on a device when behaviour or UI changed.
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
| Last verified | `assembleDebug` + `assembleRelease` + `clean assembleDebug` succeed; 17/17 unit tests; app installs, launches and renders every screen without crashing |

If the SDK or JDK moves, update `local.properties` (never commit it) and sections 4 and 16 here.
