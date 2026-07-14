# App Drawer Widget branch

This branch adds a second Android application module, `drawer-widget`, alongside the existing LSPosed Launcher3 module.

## Layout

```text
┌────────────────────────────────────────────┐
│  ⌕  Search apps                        ✎   │
├────────────────────────────────────────────┤
│  App     App     App     App     App     App│
│  App     App     App     App     App     App│
│  App     App     App     App     App     App│
│  App     App     App     App     App     App│
│       ALL APPS — vertically scrollable     │
├────────────────────────────────────────────┤
│ ★ FAVORITES             ‹  ● ○ ○  ›        │
│  Fav     Fav     Fav     Fav     Fav     Fav│
│  Fav     Fav     Fav     Fav     Fav     Fav│
└────────────────────────────────────────────┘
```

- Six columns.
- Four visible, vertically scrolling All Apps rows.
- Two fixed Favorites rows.
- Twelve favorites per page.
- Previous/next paging and page dots.
- Search opens a full app-search screen.
- The edit button opens favorite selection, removal, and up/down reordering.
- Package installs, removals, and changes refresh the widget.
- Tapping an icon launches its activity.

## Fold5 behavior

The widget is intended to fill one unfolded Launcher3 panel. Launcher3 still uses its stock two-panel workspace, so this widget cannot cross the center boundary. Placing it only on the inner workspace means it appears only while the phone is open.

## Build

Open **Actions → Build App Drawer Widget**, or run:

```sh
gradle --no-daemon :drawer-widget:assembleDebug
```

The GitHub Actions artifact is named `q5q-app-drawer-widget`.

## Install

1. Install `drawer-widget-debug.apk`.
2. Open the Launcher3 widget picker.
3. Add **Drawer Widget** to the unfolded home workspace.
4. Resize it to fill one panel.
5. Tap the pencil button to add favorite apps.

The LSPosed module APK and the drawer-widget APK are separate applications. The widget does not need LSPosed permission.
