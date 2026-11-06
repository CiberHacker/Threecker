# Building Threecker

This project was originally an Eclipse/IntelliJ "flat" Android project (sources in `java/`, resources in
`res/`, `AndroidManifest.xml` at the repository root) with no build system, which is why it could not be
turned into an APK. It now uses a standard Gradle/Android Studio layout.

## Requirements

| Tool | Version used here |
|------|-------------------|
| JDK | 17 (anything 17+ that Gradle 8.11 supports; AGP requires 17+) |
| Gradle | 8.11.1 (via the checked-in wrapper, no local install needed) |
| Android Gradle Plugin | 8.9.0 |
| Android SDK Platform | 35 (`compileSdk 35`) |
| Android SDK Build-Tools | 35.0.0 (installed automatically if missing) |

`local.properties` (not committed) must point at your SDK:

```properties
sdk.dir=C\:\\CiberWork\\SDK
```

Change that path if your SDK lives elsewhere (`File > Sync Project with Gradle Files` in
Android Studio writes it for you).

## Build

From the repository root:

```powershell
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat assembleDebug

# Release APK (unsigned) -> app/build/outputs/apk/release/app-release-unsigned.apk
.\gradlew.bat assembleRelease

# Clean everything
.\gradlew.bat clean

# JVM unit tests (no device or card needed) -> app/build/reports/tests/testDebugUnitTest
.\gradlew.bat testDebugUnitTest
```

On Linux/macOS use `./gradlew` instead of `.\gradlew.bat`.

If your default `java` on `PATH` is newer than Gradle 8.11 supports (e.g. JDK 25), point Gradle at a
supported JDK for the command:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.1'
.\gradlew.bat assembleDebug
```

Android Studio does this for you: it always runs Gradle with its bundled JBR (21).

## Install

```powershell
# with adb from the SDK platform-tools
C:\CiberWork\SDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

The debug APK is signed with the automatic debug keystore, so it installs as-is. The release APK is
**unsigned**: to install it, either add a `signingConfigs` block to `app/build.gradle` (see
[the AGP docs](https://developer.android.com/studio/publish/app-signing)) or keep using debug builds.

## App features added on top of the original code

* **Tabbed single screen** – one activity with three tabs: *Overwrite* (status, balance hero, card
  fields, raw sector 8 and the write / share actions), *Dumps* (saved dumps grouped by card number,
  every card collapsed by default, tap to expand/load, long press for details, duplicate or delete)
  and *About* (description, write warnings, diff tool). The old overflow menu and the two floating
  action buttons are gone, so there is no longer a second, duplicate route to the dump list.
* **Write safety** – the write action asks for confirmation first (card number, balance, last
  usage, dump UID), then refuses any card whose UID differs from the dump
  (`Dump.belongsTo`), and finally re-reads sector #8 and compares it with the dump
  (`Dump.verify`). Failures are reported in an `AlertDialog` instead of only appending to a status
  text, and a failed write no longer discards the loaded dump.
* **Diff tool** (`DiffActivity`) – compares two saved dumps field by field (UID, card number,
  balance, last usage, validator, and the raw blocks) and reports how many fields differ.
* **Dump annotations** (`utils/DumpMetadata`) – a name and a description per dump, stored in a
  sidecar `dump_metadata.properties` so the 5-line dump format is untouched.
* **Card view** – the Overwrite tab shows the Troika card artwork with real rounded corners and a
  small "Tap the card to flip" pill in its top-right corner; tapping flips the card in 3D to the
  back, where the dump's card number and balance are printed into the white fields (the square field
  between them is left empty). The images live in `res/drawable-nodpi/`.
* **Closing a dump** – the × in the green “Dump loaded …” bar unloads the current dump without
  touching the file or the card.
* **Field editors** – an edit icon next to the balance, last usage, last validator and saved name
  opens the matching editor (numeric dialogs, date+time pickers, or the details dialog). The changes
  apply to the loaded dump and are written to the card by the regular write flow.
* **Collapsible cards** – every card-number group in the *Dumps* tab starts collapsed, so the tab
  opens as a short list of cards; a tap on a header hides/shows that card's dumps (animated
  chevron). The expanded state survives list refreshes.
* **Duplicating dumps** – long press a dump and choose *Duplicate*: a copy is written under a fresh
  date/time name (the original is never overwritten), keeping the name (marked " (copy)") and the
  description, and its card group is expanded so the copy is visible.
* **Deleting dumps** – long press a dump, choose *Delete* and confirm; the file and its annotations
  are removed, and the loaded dump is dropped if it was that file.
* **Light/dark theme** – a toolbar icon (moon/sun) switches between the light and dark palettes
  (`values-night/colors.xml`); the choice is stored in preferences and applied to every screen.
* **NFC without a restart** – when NFC is off, the app asks once per launch and shows an inline
  card with a deep link to the NFC settings. It listens for `ACTION_ADAPTER_STATE_CHANGED` and
  re-checks on resume, so toggling NFC is picked up immediately.
* **Export** (`utils/DumpShare`) – shares the loaded dump as plain text or as JSON through any app
  that handles `text/plain` (no storage permission, no `FileProvider`). Reachable from the
  *Overwrite* tab.
* **Null safety** – the dump list handles an unavailable/empty dumps directory instead of
  `NullPointerException`-ing on `list()`.

## Unit tests

`app/src/test/java/...` covers the pure logic that does not need NFC:

* `HexUtilsTest` – hex conversion, odd-length input, round trip.
* `DumpTest` – `intval`, `formatCardNumber`, balance/card-number/validator/usage parsing,
  `FILENAME_REGEXP`, and the `toText`/`toJson` exports.

## Project layout

```
settings.gradle            repositories + module list
build.gradle               AGP version declaration
gradle.properties          AndroidX flags, daemon JVM args
local.properties           machine specific SDK path (git-ignored)
gradle/wrapper/            Gradle 8.11.1 wrapper (checked in)
app/build.gradle           android { } block + dependencies
app/src/main/AndroidManifest.xml
app/src/main/java/cc/troikadumper/...
app/src/main/res/...
```

## Configuration notes / what changed to make it buildable

1. **Standard source layout** – `java/`, `res/` and `AndroidManifest.xml` were moved under
   `app/src/main/` (moved with `git mv`, so history is preserved).
2. **Namespace** – the manifest `package="cc.troikadumper"` attribute was replaced by
   `namespace 'cc.troikadumper'` in `app/build.gradle`; AGP 8 no longer accepts it in the manifest.
3. **AndroidX migration** – AGP 8 cannot consume the abandoned `com.android.support` libraries, so:
   * `android.support.v7.app.AppCompatActivity` → `androidx.appcompat.app.AppCompatActivity`
   * `android.support.v7.widget.Toolbar` → `androidx.appcompat.widget.Toolbar`
   * `android.support.design.widget.FloatingActionButton` → `com.google.android.material.floatingactionbutton.FloatingActionButton`
   * `android.support.design.widget.CoordinatorLayout` / `AppBarLayout` → `androidx.coordinatorlayout.widget.CoordinatorLayout` / `com.google.android.material.appbar.AppBarLayout`
   * the base theme is now `Theme.MaterialComponents.Light.NoActionBar` (required by the Material FAB)
4. **`android:exported`** – `targetSdk 34` requires an explicit `android:exported` on every activity
   with an intent-filter, otherwise the manifest merger fails. `MainActivity` is `true` (launcher + NFC),
   `DumpListActivity` is `false`.
5. **`PendingIntent` flags** – the NFC foreground-dispatch `PendingIntent` in `MainActivity` now uses
   `FLAG_MUTABLE` on API 31+; without it the app crashes with `IllegalArgumentException` on Android 12+.
   The NFC stack needs a mutable intent because it adds the `Tag` extras.
6. **Broken XML** – `res/values-v21/styles.xml` started with `<resources>>` (stray `>`), which fails
   resource compilation. Fixed.
7. **SDK levels** – `compileSdk 35`, `targetSdk 34`, `minSdk 21`. The original README mentions
   Android 4.4 (API 19); current AndroidX AppCompat/Material releases require API 21, and `targetSdk`
   is kept at 34 instead of 35 so that Android 15's forced edge-to-edge layout does not change the
   existing screen layout. Bump both if you need them.
8. **Null intent action** – `MainActivity.onCreate()` did `getIntent().getAction().equals(...)`, which
   throws `NullPointerException` when the activity starts without an action (e.g.
   `adb shell am start -n cc.troikadumper/.MainActivity`, or some launcher/recents paths). It now
   compares the constant against the action, so a null action is handled.
