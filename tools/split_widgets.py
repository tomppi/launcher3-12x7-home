from pathlib import Path
import re


def sub_one(text, pattern, replacement, label):
    updated, count = re.subn(pattern, lambda _: replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return updated


def replace_one(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


java_path = Path("drawer-widget/src/main/java/io/github/tomppi/drawerwidget/DrawerWidget.java")
java = java_path.read_text()

providers = '''    /** Original widget: search plus the vertically scrolling All Apps grid only. */
    public static final class Provider extends AppWidgetProvider {
        @Override
        public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
            for (int appWidgetId : appWidgetIds) {
                updateAllAppsWidget(context, manager, appWidgetId);
            }
        }
    }

    /** Separate widget that shares the same Favorites, order, paging, and Root Freeze settings. */
    public static final class FavoritesProvider extends AppWidgetProvider {
        @Override
        public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
            List<AppEntry> favorites = loadFavoriteEntries(context);
            for (int appWidgetId : appWidgetIds) {
                updateFavoritesWidget(context, manager, appWidgetId, favorites);
            }
        }

        @Override
        public void onDeleted(Context context, int[] appWidgetIds) {
            SharedPreferences.Editor editor = prefs(context).edit();
            for (int appWidgetId : appWidgetIds) {
                editor.remove(PREF_PAGE_PREFIX + appWidgetId);
            }
            editor.apply();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            super.onReceive(context, intent);
            String action = intent.getAction();
            if (!ACTION_FAVORITES_NEXT.equals(action)
                    && !ACTION_FAVORITES_PREVIOUS.equals(action)) {
                return;
            }

            int appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return;
            }

            List<AppEntry> favorites = loadFavoriteEntries(context);
            int pageCount = pageCount(favorites.size());
            if (pageCount <= 1) {
                setFavoritePage(context, appWidgetId, 0);
                refreshFavoritePage(context, appWidgetId, favorites);
                return;
            }

            int page = currentFavoritePage(context, appWidgetId, pageCount);
            page = ACTION_FAVORITES_NEXT.equals(action)
                    ? Math.floorMod(page + 1, pageCount)
                    : Math.floorMod(page - 1, pageCount);
            setFavoritePage(context, appWidgetId, page);
            refreshFavoritePage(context, appWidgetId, favorites);
        }
    }

    public static final class PackageReceiver'''
java = sub_one(
    java,
    r'    public static final class Provider extends AppWidgetProvider \{.*?\n    \}\n\n    public static final class PackageReceiver',
    providers,
    "providers")

updates = '''    private static void updateAllAppsWidget(
            Context context,
            AppWidgetManager manager,
            int appWidgetId) {
        RemoteViews views = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_drawer);
        bindAllApps(context, views, appWidgetId);
        views.setOnClickPendingIntent(
                R.id.search_bar,
                activityPendingIntent(
                        context,
                        SearchActivity.class,
                        appWidgetId * 10 + 1));
        manager.updateAppWidget(appWidgetId, views);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.all_apps_grid);
    }

    private static void updateFavoritesWidget(
            Context context,
            AppWidgetManager manager,
            int appWidgetId,
            List<AppEntry> favorites) {
        RemoteViews views = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_favorites_only);
        bindFavoritesPage(context, views, appWidgetId, favorites);
        views.setOnClickPendingIntent(
                R.id.edit_favorites,
                activityPendingIntent(
                        context,
                        FavoritesActivity.class,
                        appWidgetId * 10 + 2));
        manager.updateAppWidget(appWidgetId, views);
    }

    private static void bindAllApps'''
java = sub_one(
    java,
    r'    private static void updateWidget\(.*?\n    \}\n\n    private static void bindAllApps',
    updates,
    "update methods")

refresh_all = '''    private static void refreshAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] allAppsIds = manager.getAppWidgetIds(new ComponentName(context, Provider.class));
        for (int id : allAppsIds) {
            updateAllAppsWidget(context, manager, id);
        }

        int[] favoriteIds = manager.getAppWidgetIds(
                new ComponentName(context, FavoritesProvider.class));
        if (favoriteIds.length > 0) {
            List<AppEntry> favorites = loadFavoriteEntries(context);
            for (int id : favoriteIds) {
                updateFavoritesWidget(context, manager, id, favorites);
            }
        }
    }

    private static void refreshFavoritePage'''
java = sub_one(
    java,
    r'    private static void refreshAllWidgets\(Context context\) \{.*?\n    \}\n\n    private static void refreshFavoritePage',
    refresh_all,
    "refresh all")

java = replace_one(
    java,
    '''        RemoteViews partial = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_drawer);
        bindFavoritesPage(context, partial, appWidgetId, favorites);''',
    '''        RemoteViews partial = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_favorites_only);
        bindFavoritesPage(context, partial, appWidgetId, favorites);''',
    "favorite page layout")

refresh_favorites = '''    private static void refreshFavoritesWidgets(Context context, boolean resetPages) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, FavoritesProvider.class));
        List<AppEntry> favorites = loadFavoriteEntries(context);
        for (int id : ids) {
            if (resetPages) {
                setFavoritePage(context, id, 0);
            }
            RemoteViews partial = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_favorites_only);
            bindFavoritesPage(context, partial, id, favorites);
            manager.partiallyUpdateAppWidget(id, partial);
        }
    }

    private static int pageCount'''
java = sub_one(
    java,
    r'    private static void refreshFavoritesWidgets\(Context context, boolean resetPages\) \{.*?\n    \}\n\n    private static int pageCount',
    refresh_favorites,
    "refresh favorites")
java_path.write_text(java)

hook_path = Path("app/src/main/java/io/github/tomppi/launchergrid712/LauncherGridHook.java")
hook = hook_path.read_text()
hook = replace_one(
    hook,
    '''            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(
                    DRAWER_WIDGET_PACKAGE,
                    DRAWER_WIDGET_PACKAGE + ".DrawerWidget$Provider"));
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);''',
    '''            AppWidgetProviderInfo info = host.getAppWidgetInfo();
            ComponentName provider = info == null ? null : info.provider;
            if (provider == null
                    || !DRAWER_WIDGET_PACKAGE.equals(provider.getPackageName())) {
                return;
            }

            Intent intent = new Intent(action);
            intent.setComponent(provider);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);''',
    "swipe receiver")
hook_path.write_text(hook)

manifest_path = Path("drawer-widget/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text()
manifest = replace_one(
    manifest,
    '''        <receiver
            android:name=".DrawerWidget$Provider"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/drawer_widget_info" />
        </receiver>
''',
    '''        <receiver
            android:name=".DrawerWidget$Provider"
            android:exported="true"
            android:label="All Apps Widget">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/drawer_widget_info" />
        </receiver>

        <receiver
            android:name=".DrawerWidget$FavoritesProvider"
            android:exported="true"
            android:label="Favorites Widget">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/favorites_widget_info" />
        </receiver>
''',
    "manifest")
manifest_path.write_text(manifest)

Path("drawer-widget/src/main/res/layout/widget_drawer.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:clipChildren="true"
    android:clipToPadding="true"
    android:orientation="vertical"
    android:padding="12dp">
    <TextView
        android:id="@+id/search_bar"
        android:layout_width="match_parent"
        android:layout_height="46dp"
        android:background="@drawable/search_background"
        android:gravity="center_vertical"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:text="⌕  Search apps"
        android:textColor="#CFCFCF"
        android:textSize="16sp" />
    <FrameLayout
        android:id="@+id/all_apps_clip_container"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:layout_weight="1"
        android:background="#303030"
        android:clipChildren="true"
        android:clipToPadding="true">
        <GridView
            android:id="@+id/all_apps_grid"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipChildren="true"
            android:clipToPadding="true"
            android:columnWidth="52dp"
            android:gravity="center"
            android:horizontalSpacing="0dp"
            android:numColumns="6"
            android:overScrollMode="never"
            android:scrollbarStyle="insideOverlay"
            android:stretchMode="columnWidth"
            android:verticalSpacing="0dp" />
    </FrameLayout>
</LinearLayout>
''')

Path("drawer-widget/src/main/res/layout/widget_drawer_preview.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:orientation="vertical"
    android:padding="12dp">
    <TextView
        android:layout_width="match_parent"
        android:layout_height="46dp"
        android:background="@drawable/search_background"
        android:gravity="center_vertical"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:text="⌕  Search apps"
        android:textColor="#CFCFCF"
        android:textSize="16sp" />
    <TextView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:layout_weight="1"
        android:background="#303030"
        android:gravity="center"
        android:text="ALL APPS\n\n6 columns\nFills all available space\nVertically scrollable"
        android:textAlignment="center"
        android:textColor="#F2F2F2"
        android:textSize="15sp" />
</LinearLayout>
''')

Path("drawer-widget/src/main/res/layout/widget_favorites_only.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:clipChildren="true"
    android:clipToPadding="true"
    android:orientation="vertical"
    android:padding="10dp">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="38dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">
        <TextView
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:text="★  FAVORITES"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:textStyle="bold" />
        <TextView
            android:id="@+id/favorites_page_indicator"
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
            android:gravity="center_vertical"
            android:text="Swipe ↔"
            android:textColor="#9E9E9E"
            android:textSize="11sp" />
        <TextView
            android:id="@+id/edit_favorites"
            android:layout_width="42dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="✎"
            android:textColor="#FFFFFF"
            android:textSize="20sp" />
    </LinearLayout>
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="228dp"
        android:background="#303030"
        android:clipChildren="true"
        android:clipToPadding="true">
        <include layout="@layout/widget_favorites_page" />
        <TextView
            android:id="@+id/empty_favorites"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="#303030"
            android:gravity="center"
            android:text="Tap ✎ to add favorite apps"
            android:textColor="#BDBDBD"
            android:textSize="14sp" />
    </FrameLayout>
</LinearLayout>
''')

Path("drawer-widget/src/main/res/layout/widget_favorites_preview.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:orientation="vertical"
    android:padding="10dp">
    <TextView
        android:layout_width="match_parent"
        android:layout_height="38dp"
        android:gravity="center_vertical"
        android:text="★  FAVORITES                         Swipe ↔   ✎"
        android:textColor="#FFFFFF"
        android:textSize="13sp"
        android:textStyle="bold" />
    <TextView
        android:layout_width="match_parent"
        android:layout_height="228dp"
        android:background="#303030"
        android:gravity="center"
        android:text="18 favorites per page\n6 columns × 3 rows\nContinuous horizontal paging"
        android:textAlignment="center"
        android:textColor="#F2F2F2"
        android:textSize="15sp" />
</LinearLayout>
''')

Path("drawer-widget/src/main/res/xml/favorites_widget_info.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_description"
    android:initialLayout="@layout/widget_favorites_only"
    android:minWidth="300dp"
    android:minHeight="260dp"
    android:minResizeWidth="180dp"
    android:minResizeHeight="160dp"
    android:previewLayout="@layout/widget_favorites_preview"
    android:resizeMode="horizontal|vertical"
    android:targetCellWidth="7"
    android:targetCellHeight="5"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
''')

Path(".github/workflows/build-drawer-widget.yml").write_text('''name: Build App Drawer Widget

on:
  workflow_dispatch:
  push:
    branches: [ feature/app-drawer-widget ]
    paths:
      - 'drawer-widget/**'
      - 'settings.gradle.kts'
      - 'build.gradle.kts'
      - '.github/workflows/build-drawer-widget.yml'
  pull_request:
    paths:
      - 'drawer-widget/**'
      - 'settings.gradle.kts'
      - 'build.gradle.kts'
      - '.github/workflows/build-drawer-widget.yml'

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read

    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.10.2'
      - name: Build drawer widget APK
        run: gradle --no-daemon :drawer-widget:assembleDebug
      - name: Upload drawer widget APK
        uses: actions/upload-artifact@v4
        with:
          name: q5q-app-drawer-widget
          path: drawer-widget/build/outputs/apk/debug/drawer-widget-debug.apk
          if-no-files-found: error
''')

Path("split-widgets.trigger").unlink(missing_ok=True)
Path(__file__).unlink(missing_ok=True)
