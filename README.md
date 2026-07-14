# Launcher3 12×7 Home — LSPosed module

An LSPosed module for the LineageOS Launcher3/Quickstep package on the Samsung Galaxy Z Fold5 (`q5q`).

Here **12×7 means 12 rows high and 7 columns across**.

## What it changes

- Launcher desktop: **7 columns × 12 rows**.
- Workspace icon size is capped at 42 dp and label size at 10.5 sp so twelve rows fit.
- The unfolded Fold5 uses Launcher3's **single-page foldable workspace**, so the inner display is one full-width home grid instead of two separate panels.
- Widgets report a **1×1 minimum**, **7×12 maximum**, and both horizontal and vertical resize directions.
- The active widget resize frame is also forced to the same 1×1 through 7×12 bounds as a compatibility fallback.
- App drawer is untouched.
- Folder grid, hotseat, search container, Recents, gestures, and taskbar settings are untouched.
- No Nova-style fractional/subgrid positioning is added.

The module does not add a new entry to Launcher3's grid picker. It overrides the currently selected stock grid while Launcher3 starts.

## Widget behavior

Launcher3 normally uses each widget provider's declared minimum and maximum dimensions. This module deliberately relaxes those bounds.

That allows much finer resizing, but a widget can still crop, waste space, or render poorly when made smaller than its developer intended. The module changes the host-side grid limits; it cannot redesign the widget's own layout.

The single-page foldable mode is what allows one widget to span the full width of the unfolded inner display. Without it, Launcher3 treats each half as a separate `CellLayout` and blocks cross-panel widgets.

## Target

- Package: `com.android.launcher3`
- Designed for the LineageOS Launcher3 structure containing:
  - `InvariantDeviceProfile.initGridForDisplayOption(...)`
  - `FeatureFlags.FOLDABLE_SINGLE_PAGE`
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
5. Keep Nova installed until the single-page workspace and existing widget migration have been checked on both displays.

A full reboot is recommended because Quickstep is also the Recents provider and Launcher3 needs to rebuild its workspace state.

## Verification

Open the LSPosed log and search for:

```text
Q5QLauncherGrid: enabled foldable single-page workspace
Q5QLauncherGrid: applied 7 columns x 12 rows; app drawer untouched
Q5QLauncherGrid: widget metadata set to min 1x1, max 7x12, both directions
Q5QLauncherGrid: active widget resize frame set to 1x1 through 7x12
```

You can also check the Launcher package with:

```sh
su -c 'pm path com.android.launcher3'
```

## Recovery

If Launcher3 behaves incorrectly:

1. Open LSPosed and disable this module.
2. Reboot.
3. Select Nova as the default launcher if Launcher3's migrated workspace needs to be repaired.

The hooks catch compatibility errors and leave the corresponding stock Launcher3 behavior in place instead of deliberately crashing it.

## Notes

- Enabling the single-page foldable workspace can rearrange existing icons and widgets during Launcher3's first migration.
- Disabling the module later shrinks the grid and restores the stock foldable page model, so items stored in extra rows or wide widget spans may be moved.
- The module intentionally leaves Launcher3's database filename and app-drawer profile unchanged.
- A future Launcher3 update that renames the targeted classes, methods, or fields will require a compatibility update.
