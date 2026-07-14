# Launcher3 12×7 Home — LSPosed module

An LSPosed module for the LineageOS Launcher3/Quickstep package on the Samsung Galaxy Z Fold5 (`q5q`).

Here **12×7 means 12 rows high and 7 columns across**.

## Stable behavior

- Launcher desktop: **7 columns × 12 rows**.
- Workspace icon size is capped at 42 dp and label size at 10.5 sp so twelve rows fit.
- App drawer is untouched.
- Widgets use Launcher3's stock sizing and resize rules.
- The Fold5 keeps Launcher3's stock two-panel unfolded workspace.
- Folders, hotseat, search container, Recents, gestures, and taskbar are untouched.
- No Nova-style fractional/subgrid positioning is added.

The module does not add a new entry to Launcher3's grid picker. It overrides the currently selected stock workspace grid while Launcher3 starts.

## v2.0.1 stability rollback

Version 2.0.0 experimentally enabled Launcher3's `FOLDABLE_SINGLE_PAGE` flag and rewrote widget resize metadata. On this LineageOS Launcher3 build, the single-page mode did not produce a usable full-width workspace and the combined hooks made Launcher3 unstable.

Version **2.0.1 removes both experimental features** and restores the previously stable grid-only implementation. Its higher version code allows it to install directly over v2.0.0.

A widget cannot span both halves of the unfolded home screen while Launcher3 uses two separate `CellLayout` panels. Implementing that reliably requires a Launcher3 source modification rather than a small LSPosed field override.

## Target

- Package: `com.android.launcher3`
- Hook: `InvariantDeviceProfile.initGridForDisplayOption(...)`

## Build with GitHub Actions

1. Open **Actions → Build LSPosed APK → Run workflow**.
2. Download the `q5q-launcher3-grid-7x12-lsposed` artifact.
3. Install `app-debug.apk` from the artifact.

The project compiles against a local source-only Xposed API stub module. Those stub classes are compile-only and are not packaged into the APK.

## Install and enable

1. Install the APK over the previous version.
2. Open LSPosed → Modules → **Q5Q Launcher Grid 7×12**.
3. Enable it and scope it only to **Launcher3** (`com.android.launcher3`).
4. Reboot the phone.

A full reboot is recommended because Quickstep is also the Recents provider.

## Verification

Open the LSPosed log and search for:

```text
Q5QLauncherGrid: stable grid-only hook installed for com.android.launcher3
Q5QLauncherGrid: applied stable 7 columns x 12 rows; app drawer and widgets untouched
```

## Recovery

If Launcher3 remains unstable after installing v2.0.1:

1. Disable the module in LSPosed.
2. Reboot.
3. Select Nova temporarily if Launcher3's workspace database needs to settle after the v2.0.0 experiment.

The hook catches compatibility errors and leaves Launcher3's stock grid in place instead of deliberately crashing it.
