# Launcher3 12×7 Home — LSPosed module

An LSPosed module for the LineageOS Launcher3/Quickstep package on the Samsung Galaxy Z Fold5 (`q5q`).

Here **12×7 means 12 rows high and 7 columns across**.

## What it changes

- Launcher desktop: **7 columns × 12 rows**.
- Workspace icon size is capped at 42 dp and label size at 10.5 sp so twelve rows fit.
- Widgets can resize from **1×1 up to 7×12** within their current Launcher3 workspace panel.
- Horizontal and vertical widget resizing are enabled.
- The Fold5 keeps Launcher3's stock **two-panel unfolded workspace**.
- App drawer, folders, hotseat, search container, Recents, gestures, and taskbar are untouched.
- No Nova-style fractional/subgrid positioning is added.

The module does not add a new entry to Launcher3's grid picker. It overrides the selected workspace grid while Launcher3 starts.

## Foldable limitation

The module does **not** enable `FOLDABLE_SINGLE_PAGE`. That experiment did not create a usable full-width workspace on this LineageOS Launcher3 build and made Launcher3 unstable.

Widgets therefore remain attached to one of Launcher3's two unfolded `CellLayout` panels and cannot cross the center boundary. The relaxed 1×1 through 7×12 sizing applies inside the panel containing the widget.

## Widget behavior

Launcher3 normally follows each widget provider's declared minimum, maximum, and resize directions. This module relaxes those host-side limits after Launcher3 calculates them and applies the same limits to the active resize frame.

A widget can still crop, waste space, or render poorly when made smaller than its developer intended. The module changes Launcher3's grid limits; it cannot redesign a widget's internal layout.

## Target

- Package: `com.android.launcher3`
- Hooks:
  - `InvariantDeviceProfile.initGridForDisplayOption(...)`
  - `LauncherAppWidgetProviderInfo.initSpans(...)`
  - `AppWidgetResizeFrame.setupForWidget(...)`

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

Do not clear Launcher3 data when updating; that would erase the current home-screen arrangement.

## Verification

Open the LSPosed log and search for:

```text
Q5QLauncherGrid: applied 7 columns x 12 rows; app drawer untouched
Q5QLauncherGrid: widget metadata set to min 1x1, max 7x12, both directions
Q5QLauncherGrid: active widget resize frame set to 1x1 through 7x12
```

There should be no log line saying that foldable single-page mode was enabled.

## Recovery

If Launcher3 becomes unstable:

1. Disable the module in LSPosed.
2. Reboot.
3. Select Nova temporarily while Launcher3 recovers.

Version **2.1.0** has a higher version code than both v2.0.0 and the v2.0.1 rollback, so it installs directly over either build.
