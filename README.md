# Launcher3 12×7 Home — LSPosed module

An LSPosed module for the LineageOS Launcher3/Quickstep package on the Samsung Galaxy Z Fold5 (`q5q`).

Here **12×7 means 12 rows high and 7 columns across**.

## What it changes

- Launcher desktop: **7 columns × 12 rows**.
- Workspace icon size is capped at 42 dp and label size at 10.5 sp so twelve rows fit.
- App drawer is untouched.
- Folder grid, hotseat, search container, Recents, gestures, and taskbar settings are untouched.
- No Nova-style subgrid positioning is added.

The module does not add a new entry to Launcher3's grid picker. It overrides the currently selected stock grid while Launcher3 starts.

## Target

- Package: `com.android.launcher3`
- Designed for the LineageOS Launcher3 structure where `InvariantDeviceProfile.initGridForDisplayOption(...)` copies values from `DisplayOption.grid` into the active profile.

## Build with GitHub Actions

1. Open **Actions → Build LSPosed APK → Run workflow**.
2. Download the `q5q-launcher3-grid-7x12-lsposed` artifact.
3. Install `app-debug.apk` from the artifact.

The project includes a tiny compile-only Xposed API stub JAR. Those classes are not bundled into the APK.

## Install and enable

1. Install the APK.
2. Open LSPosed → Modules → **Q5Q Launcher Grid 7×12**.
3. Enable it and scope it only to **Launcher3** (`com.android.launcher3`).
4. Reboot the phone, or force-stop Launcher3 and start it again.
5. Select Launcher3 as the default Home app before removing Nova.

A reboot is recommended for the first test because Quickstep is also the Recents provider.

## Verification

Open the LSPosed log and search for:

```text
Q5QLauncherGrid: applied 7 columns x 12 rows; app drawer untouched
```

You can also check the Launcher package with:

```sh
su -c 'pm path com.android.launcher3'
```

## Recovery

If Launcher3 behaves incorrectly:

1. Open LSPosed and disable this module.
2. Reboot.
3. Keep Nova installed until the 7×12 layout has been tested on both the cover and inner displays.

The hook catches compatibility errors and leaves Launcher3's stock profile in place instead of deliberately crashing it.

## Notes

- Existing icons should migrate into the larger grid. Disabling the module later shrinks the grid again, so icons stored in the extra rows or columns may be moved by Launcher3.
- The module intentionally leaves Launcher3's database filename and app-drawer profile unchanged.
- A future Launcher3 update that renames `initGridForDisplayOption`, `grid`, `numRows`, `numColumns`, `iconSizes`, or `textSizes` will require a compatibility update.
