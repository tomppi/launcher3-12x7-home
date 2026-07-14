package io.github.tomppi.drawerwidget;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DrawerWidget {
    private static final String PREFS = "drawer_widget";
    private static final String PREF_FAVORITES = "favorites";
    private static final String PREF_FREEZE_PACKAGES = "freeze_packages";
    private static final String PREF_FAVORITES_REVISION = "favorites_revision";
    private static final String PREF_APPS_REVISION = "apps_revision";

    private static final String EXTRA_COMPONENT = "component";
    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_LABEL = "label";

    private static final int FAVORITES_PER_PAGE = 12;
    private static final int FAVORITE_PAGE_CYCLES = 2000;

    private static final String ACTION_LAUNCH =
            "io.github.tomppi.drawerwidget.action.LAUNCH";

    private static final String FREEZE_PACKAGE = "com.tomppi.freezeshortcuts";
    private static final String FREEZE_PROXY =
            "com.tomppi.freezeshortcuts.ProxyActivity";
    private static final String FREEZE_EXTRA_PACKAGE = "target_package";
    private static final String FREEZE_EXTRA_LABEL = "target_label";

    private DrawerWidget() {}

    public static final class Provider extends AppWidgetProvider {
        @Override
        public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
            for (int appWidgetId : appWidgetIds) {
                updateWidget(context, manager, appWidgetId);
            }
        }
    }

    public static final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            prefs(context).edit()
                    .putLong(PREF_APPS_REVISION, System.currentTimeMillis())
                    .putLong(PREF_FAVORITES_REVISION, System.currentTimeMillis())
                    .apply();
            refreshAllWidgets(context);
        }
    }

    /** Collection-item clicks never create a task owned by Drawer Widget. */
    public static final class LaunchReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_LAUNCH.equals(intent.getAction())) {
                return;
            }

            String packageName = intent.getStringExtra(EXTRA_PACKAGE);
            String label = intent.getStringExtra(EXTRA_LABEL);
            String flattened = intent.getStringExtra(EXTRA_COMPONENT);
            ComponentName component = flattened == null
                    ? null
                    : ComponentName.unflattenFromString(flattened);

            if (!isValidPackageName(packageName)) {
                return;
            }

            if (usesRootFreeze(context, packageName)) {
                launchWithRootFreeze(context, packageName, label);
            } else {
                launchNormally(context, packageName, component);
            }
        }
    }

    public static final class AllAppsService extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            return new AllAppsFactory(getApplicationContext());
        }
    }

    public static final class FavoritesPagesService extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            return new FavoritesPagesFactory(getApplicationContext());
        }
    }

    private static final class AllAppsFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private List<AppEntry> entries = new ArrayList<>();

        AllAppsFactory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            reload();
        }

        @Override
        public void onDataSetChanged() {
            reload();
        }

        private void reload() {
            entries = loadApps(context);
        }

        @Override
        public void onDestroy() {
            entries.clear();
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= entries.size()) {
                return null;
            }
            return appItemViews(context, entries.get(position), R.layout.widget_app_item);
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }

    /**
     * Each StackView item is one complete 6x2 favorites page. The real page set is repeated
     * through a large virtual range and starts in the middle, providing continuous swipe paging
     * without previous/next buttons or a practical end point.
     */
    private static final class FavoritesPagesFactory
            implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private List<AppEntry> favorites = new ArrayList<>();
        private int realPageCount;

        FavoritesPagesFactory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            reload();
        }

        @Override
        public void onDataSetChanged() {
            reload();
        }

        private void reload() {
            Map<String, AppEntry> byComponent = appMap(context);
            List<AppEntry> result = new ArrayList<>();
            for (String component : loadFavoriteComponents(context)) {
                AppEntry entry = byComponent.get(component);
                if (entry != null) {
                    result.add(entry);
                }
            }
            favorites = result;
            realPageCount = favorites.isEmpty()
                    ? 0
                    : (favorites.size() + FAVORITES_PER_PAGE - 1) / FAVORITES_PER_PAGE;
        }

        @Override
        public void onDestroy() {
            favorites.clear();
        }

        @Override
        public int getCount() {
            return realPageCount == 0
                    ? 0
                    : realPageCount * FAVORITE_PAGE_CYCLES;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (realPageCount == 0) {
                return null;
            }

            int page = Math.floorMod(position, realPageCount);
            int start = page * FAVORITES_PER_PAGE;
            RemoteViews pageViews = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_favorites_page);

            for (int slot = 0; slot < FAVORITES_PER_PAGE; slot++) {
                RemoteViews child;
                int index = start + slot;
                if (index < favorites.size()) {
                    child = appItemViews(
                            context,
                            favorites.get(index),
                            R.layout.widget_favorite_item);
                } else {
                    child = new RemoteViews(
                            context.getPackageName(),
                            R.layout.widget_favorite_item);
                    child.setViewVisibility(R.id.app_item_root, View.INVISIBLE);
                }
                pageViews.addView(
                        slot < 6 ? R.id.favorites_row_one : R.id.favorites_row_two,
                        child);
            }
            return pageViews;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }

    private static RemoteViews appItemViews(Context context, AppEntry entry, int layoutId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutId);
        boolean rootFreeze = usesRootFreeze(context, entry.packageName);
        views.setTextViewText(
                R.id.app_label,
                rootFreeze ? "❄ " + entry.label : entry.label);
        views.setImageViewBitmap(R.id.app_icon, iconBitmap(context, entry.icon));

        Intent fillIn = new Intent();
        fillIn.setAction(ACTION_LAUNCH);
        fillIn.putExtra(EXTRA_PACKAGE, entry.packageName);
        fillIn.putExtra(EXTRA_LABEL, entry.label);
        fillIn.putExtra(EXTRA_COMPONENT, entry.component.flattenToString());
        views.setOnClickFillInIntent(R.id.app_item_root, fillIn);
        return views;
    }

    public static final class SearchActivity extends Activity {
        private final List<AppEntry> allApps = new ArrayList<>();
        private final List<AppEntry> shownApps = new ArrayList<>();
        private AppGridAdapter adapter;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout root = verticalRoot(this);

            EditText search = new EditText(this);
            search.setHint("Search apps");
            search.setSingleLine(true);
            search.setTextColor(0xFFFFFFFF);
            search.setHintTextColor(0xFFBDBDBD);
            search.setBackgroundColor(0xFF303030);
            search.setPadding(dp(this, 16), 0, dp(this, 16), 0);
            root.addView(search, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(this, 52)));

            GridView grid = new GridView(this);
            grid.setNumColumns(6);
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setVerticalSpacing(dp(this, 4));
            root.addView(grid, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f));
            setContentView(root);

            allApps.addAll(loadApps(this));
            shownApps.addAll(allApps);
            adapter = new AppGridAdapter(this, shownApps);
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((parent, view, position, id) -> {
                AppEntry entry = shownApps.get(position);
                launchEntry(this, entry);
                finishAndRemoveTask();
            });

            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String query = s.toString().trim().toLowerCase(Locale.getDefault());
                    shownApps.clear();
                    for (AppEntry app : allApps) {
                        if (query.isEmpty()
                                || app.label.toLowerCase(Locale.getDefault()).contains(query)
                                || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                            shownApps.add(app);
                        }
                    }
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
            search.requestFocus();
        }
    }

    public static final class FavoritesActivity extends Activity {
        private final List<AppEntry> favorites = new ArrayList<>();
        private ListView listView;
        private AppListAdapter adapter;
        private int selected = -1;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout root = verticalRoot(this);
            root.addView(titleView(this, "Manage drawer widget"));

            TextView help = new TextView(this);
            help.setText("Favorites swipe continuously in the widget. Root-freeze apps are selected separately and are marked with ❄.");
            help.setTextColor(0xFFBDBDBD);
            help.setPadding(dp(this, 8), 0, dp(this, 8), dp(this, 8));
            root.addView(help);

            LinearLayout selectors = new LinearLayout(this);
            selectors.setOrientation(LinearLayout.HORIZONTAL);
            Button selectFavorites = actionButton(this, "Select favorites");
            Button selectFreeze = actionButton(this, "Root-freeze apps");
            selectors.addView(selectFavorites, weightedButtonParams());
            selectors.addView(selectFreeze, weightedButtonParams());
            root.addView(selectors, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(this, 56)));

            listView = new ListView(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            root.addView(listView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f));

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            Button up = actionButton(this, "Move up");
            Button down = actionButton(this, "Move down");
            Button remove = actionButton(this, "Remove");
            actions.addView(up, weightedButtonParams());
            actions.addView(down, weightedButtonParams());
            actions.addView(remove, weightedButtonParams());
            root.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(this, 56)));
            setContentView(root);

            listView.setOnItemClickListener((parent, view, position, id) -> selected = position);
            selectFavorites.setOnClickListener(
                    v -> startActivity(new Intent(this, AddFavoritesActivity.class)));
            selectFreeze.setOnClickListener(
                    v -> startActivity(new Intent(this, FreezeAppsActivity.class)));
            up.setOnClickListener(v -> moveSelected(-1));
            down.setOnClickListener(v -> moveSelected(1));
            remove.setOnClickListener(v -> removeSelected());
        }

        @Override
        protected void onResume() {
            super.onResume();
            reloadFavorites();
        }

        private void reloadFavorites() {
            favorites.clear();
            Map<String, AppEntry> apps = appMap(this);
            for (String component : loadFavoriteComponents(this)) {
                AppEntry entry = apps.get(component);
                if (entry != null) {
                    favorites.add(entry);
                }
            }
            adapter = new AppListAdapter(this, favorites);
            listView.setAdapter(adapter);
            selected = -1;
        }

        private void moveSelected(int delta) {
            if (selected < 0 || selected >= favorites.size()) {
                return;
            }
            int target = selected + delta;
            if (target < 0 || target >= favorites.size()) {
                return;
            }
            Collections.swap(favorites, selected, target);
            selected = target;
            saveCurrentOrder();
            adapter.notifyDataSetChanged();
            listView.setItemChecked(selected, true);
        }

        private void removeSelected() {
            if (selected < 0 || selected >= favorites.size()) {
                return;
            }
            favorites.remove(selected);
            selected = -1;
            saveCurrentOrder();
            adapter.notifyDataSetChanged();
            listView.clearChoices();
        }

        private void saveCurrentOrder() {
            List<String> components = new ArrayList<>();
            for (AppEntry entry : favorites) {
                components.add(entry.component.flattenToString());
            }
            saveFavoriteComponents(this, components);
        }
    }

    public static final class AddFavoritesActivity extends Activity {
        private final List<AppEntry> allApps = new ArrayList<>();
        private ListView listView;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout root = verticalRoot(this);
            root.addView(titleView(this, "Select favorites"));

            listView = new ListView(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            root.addView(listView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f));

            Button save = actionButton(this, "Save favorites");
            root.addView(save, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(this, 56)));
            setContentView(root);

            allApps.addAll(loadApps(this));
            List<String> labels = new ArrayList<>();
            Set<String> existing = new LinkedHashSet<>(loadFavoriteComponents(this));
            for (AppEntry app : allApps) {
                labels.add(app.label + "\n" + app.packageName);
            }
            listView.setAdapter(new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_list_item_multiple_choice,
                    labels));
            for (int i = 0; i < allApps.size(); i++) {
                listView.setItemChecked(
                        i,
                        existing.contains(allApps.get(i).component.flattenToString()));
            }
            save.setOnClickListener(v -> saveAndClose());
        }

        private void saveAndClose() {
            SparseBooleanArray checked = listView.getCheckedItemPositions();
            Set<String> chosen = new LinkedHashSet<>();
            for (int i = 0; i < allApps.size(); i++) {
                if (checked.get(i)) {
                    chosen.add(allApps.get(i).component.flattenToString());
                }
            }

            List<String> ordered = new ArrayList<>();
            for (String existing : loadFavoriteComponents(this)) {
                if (chosen.remove(existing)) {
                    ordered.add(existing);
                }
            }
            ordered.addAll(chosen);
            saveFavoriteComponents(this, ordered);
            finish();
        }
    }

    public static final class FreezeAppsActivity extends Activity {
        private final List<AppEntry> allApps = new ArrayList<>();
        private ListView listView;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout root = verticalRoot(this);
            root.addView(titleView(this, "Root-freeze launch apps"));

            TextView help = new TextView(this);
            help.setText("Checked apps are launched through the installed Root Freeze Shortcuts app. Its own grace period and auto-refreeze settings are used.");
            help.setTextColor(0xFFBDBDBD);
            help.setPadding(dp(this, 8), 0, dp(this, 8), dp(this, 8));
            root.addView(help);

            listView = new ListView(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            root.addView(listView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f));

            Button save = actionButton(this, "Save root-freeze apps");
            root.addView(save, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(this, 56)));
            setContentView(root);

            allApps.addAll(loadApps(this));
            List<String> labels = new ArrayList<>();
            Set<String> selected = loadFreezePackages(this);
            for (AppEntry app : allApps) {
                labels.add(app.label + "\n" + app.packageName);
            }
            listView.setAdapter(new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_list_item_multiple_choice,
                    labels));
            for (int i = 0; i < allApps.size(); i++) {
                listView.setItemChecked(i, selected.contains(allApps.get(i).packageName));
            }
            save.setOnClickListener(v -> saveAndClose());
        }

        private void saveAndClose() {
            SparseBooleanArray checked = listView.getCheckedItemPositions();
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < allApps.size(); i++) {
                if (checked.get(i)) {
                    selected.add(allApps.get(i).packageName);
                }
            }
            saveFreezePackages(this, selected);
            finish();
        }
    }

    private static final class AppGridAdapter extends BaseAdapter {
        private final Context context;
        private final List<AppEntry> apps;

        AppGridAdapter(Context context, List<AppEntry> apps) {
            this.context = context;
            this.apps = apps;
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(2, dp(context, 6), 2, dp(context, 6));
                icon = new ImageView(context);
                label = new TextView(context);
                label.setTextColor(0xFFF2F2F2);
                label.setTextSize(11f);
                label.setSingleLine(true);
                label.setGravity(Gravity.CENTER);
                cell.addView(icon, new LinearLayout.LayoutParams(
                        dp(context, 56),
                        dp(context, 56)));
                cell.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 24)));
            }
            AppEntry app = apps.get(position);
            icon.setImageDrawable(app.icon);
            icon.setAlpha(app.enabled ? 1f : 0.55f);
            label.setText(usesRootFreeze(context, app.packageName)
                    ? "❄ " + app.label
                    : app.label);
            return cell;
        }
    }

    private static final class AppListAdapter extends BaseAdapter {
        private final Context context;
        private final List<AppEntry> apps;

        AppListAdapter(Context context, List<AppEntry> apps) {
            this.context = context;
            this.apps = apps;
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                icon = (ImageView) row.getChildAt(0);
                label = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(
                        dp(context, 12),
                        dp(context, 4),
                        dp(context, 12),
                        dp(context, 4));
                icon = new ImageView(context);
                label = new TextView(context);
                label.setTextColor(0xFFFFFFFF);
                label.setTextSize(15f);
                label.setPadding(dp(context, 14), 0, 0, 0);
                label.setMaxLines(2);
                row.addView(icon, new LinearLayout.LayoutParams(
                        dp(context, 44),
                        dp(context, 44)));
                row.addView(label, new LinearLayout.LayoutParams(
                        0,
                        dp(context, 58),
                        1f));
            }
            AppEntry app = apps.get(position);
            icon.setImageDrawable(app.icon);
            icon.setAlpha(app.enabled ? 1f : 0.55f);
            String prefix = usesRootFreeze(context, app.packageName) ? "❄ " : "";
            label.setText(prefix + app.label + "\n" + app.packageName);
            return row;
        }
    }

    private static final class AppEntry {
        final ComponentName component;
        final String packageName;
        final String label;
        final Drawable icon;
        final boolean enabled;

        AppEntry(ComponentName component, String label, Drawable icon, boolean enabled) {
            this.component = component;
            this.packageName = component.getPackageName();
            this.label = label;
            this.icon = icon;
            this.enabled = enabled;
        }
    }

    private static void updateWidget(
            Context context,
            AppWidgetManager manager,
            int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_drawer);
        bindAllApps(context, views, appWidgetId);
        bindFavorites(context, views, appWidgetId);

        views.setOnClickPendingIntent(
                R.id.search_bar,
                activityPendingIntent(context, SearchActivity.class, appWidgetId * 10 + 1));
        views.setOnClickPendingIntent(
                R.id.edit_favorites,
                activityPendingIntent(context, FavoritesActivity.class, appWidgetId * 10 + 2));

        manager.updateAppWidget(appWidgetId, views);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.all_apps_grid);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.favorites_stack);
    }

    private static void bindAllApps(Context context, RemoteViews views, int appWidgetId) {
        long revision = prefs(context).getLong(PREF_APPS_REVISION, 0L);
        Intent service = new Intent(context, AllAppsService.class);
        service.setData(Uri.parse(
                "drawer-widget://all/" + appWidgetId + "/" + revision));
        views.setRemoteAdapter(R.id.all_apps_grid, service);
        views.setPendingIntentTemplate(
                R.id.all_apps_grid,
                itemTemplate(context, appWidgetId, 0));
    }

    private static void bindFavorites(Context context, RemoteViews views, int appWidgetId) {
        long revision = prefs(context).getLong(PREF_FAVORITES_REVISION, 0L);
        Intent service = new Intent(context, FavoritesPagesService.class);
        service.setData(Uri.parse(
                "drawer-widget://favorites/" + appWidgetId + "/" + revision));
        views.setRemoteAdapter(R.id.favorites_stack, service);
        views.setEmptyView(R.id.favorites_stack, R.id.empty_favorites);
        views.setPendingIntentTemplate(
                R.id.favorites_stack,
                itemTemplate(context, appWidgetId, 1));
        views.setDisplayedChild(
                R.id.favorites_stack,
                virtualFavoriteMiddle(favoritePageCount(context)));
    }

    private static PendingIntent itemTemplate(Context context, int appWidgetId, int collection) {
        Intent template = new Intent(context, LaunchReceiver.class);
        template.setData(Uri.parse(
                "drawer-widget://template/" + appWidgetId + "/" + collection));
        return PendingIntent.getBroadcast(
                context,
                appWidgetId * 100 + collection,
                template,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    private static PendingIntent activityPendingIntent(
            Context context,
            Class<? extends Activity> activity,
            int requestCode) {
        Intent intent = new Intent(context, activity);
        intent.setData(Uri.parse("drawer-widget://activity/" + requestCode));
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void refreshAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, Provider.class));
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private static void refreshFavoritesWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, Provider.class));
        for (int id : ids) {
            RemoteViews partial = new RemoteViews(context.getPackageName(), R.layout.widget_drawer);
            bindFavorites(context, partial, id);
            manager.partiallyUpdateAppWidget(id, partial);
            manager.notifyAppWidgetViewDataChanged(id, R.id.favorites_stack);
        }
    }

    private static int favoritePageCount(Context context) {
        int size = loadFavoriteComponents(context).size();
        return size == 0 ? 0 : (size + FAVORITES_PER_PAGE - 1) / FAVORITES_PER_PAGE;
    }

    private static int virtualFavoriteMiddle(int realPageCount) {
        if (realPageCount <= 0) {
            return 0;
        }
        int count = realPageCount * FAVORITE_PAGE_CYCLES;
        int middle = count / 2;
        return middle - Math.floorMod(middle, realPageCount);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> loadFavoriteComponents(Context context) {
        return readLines(prefs(context).getString(PREF_FAVORITES, ""));
    }

    private static Set<String> loadFreezePackages(Context context) {
        return new LinkedHashSet<>(
                readLines(prefs(context).getString(PREF_FREEZE_PACKAGES, "")));
    }

    private static List<String> readLines(String stored) {
        List<String> result = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String line : stored.split("\\n")) {
            String value = line.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static void saveFavoriteComponents(Context context, List<String> components) {
        prefs(context).edit()
                .putString(PREF_FAVORITES, joinLines(new LinkedHashSet<>(components)))
                .putLong(PREF_FAVORITES_REVISION, System.currentTimeMillis())
                .apply();
        refreshFavoritesWidgets(context);
    }

    private static void saveFreezePackages(Context context, Set<String> packages) {
        prefs(context).edit()
                .putString(PREF_FREEZE_PACKAGES, joinLines(packages))
                .putLong(PREF_APPS_REVISION, System.currentTimeMillis())
                .putLong(PREF_FAVORITES_REVISION, System.currentTimeMillis())
                .apply();
        refreshAllWidgets(context);
    }

    private static String joinLines(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean usesRootFreeze(Context context, String packageName) {
        return loadFreezePackages(context).contains(packageName);
    }

    private static Map<String, AppEntry> appMap(Context context) {
        Map<String, AppEntry> result = new HashMap<>();
        for (AppEntry entry : loadApps(context)) {
            result.put(entry.component.flattenToString(), entry);
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static List<AppEntry> loadApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        long flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        List<ResolveInfo> resolved;
        if (Build.VERSION.SDK_INT >= 33) {
            resolved = pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(flags));
        } else {
            resolved = pm.queryIntentActivities(launcherIntent, (int) flags);
        }

        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo info : resolved) {
            ActivityInfo activityInfo = info.activityInfo;
            if (activityInfo == null) {
                continue;
            }

            ComponentName component = new ComponentName(
                    activityInfo.packageName,
                    activityInfo.name);
            String flattened = component.flattenToString();
            if (!seen.add(flattened)
                    || context.getPackageName().equals(component.getPackageName())) {
                continue;
            }

            try {
                CharSequence loadedLabel = info.loadLabel(pm);
                String label = loadedLabel == null
                        ? component.getShortClassName()
                        : loadedLabel.toString();
                Drawable icon = info.loadIcon(pm);
                apps.add(new AppEntry(
                        component,
                        label,
                        icon,
                        isComponentEnabled(pm, component, activityInfo)));
            } catch (RuntimeException ignored) {
            }
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((left, right) -> {
            int labelResult = collator.compare(left.label, right.label);
            return labelResult != 0
                    ? labelResult
                    : left.component.flattenToString().compareTo(
                            right.component.flattenToString());
        });
        return apps;
    }

    private static boolean isComponentEnabled(
            PackageManager pm,
            ComponentName component,
            ActivityInfo activityInfo) {
        boolean enabled = activityInfo.enabled;
        ApplicationInfo appInfo = activityInfo.applicationInfo;
        if (appInfo != null) {
            enabled &= appInfo.enabled;
        }

        try {
            int appSetting = pm.getApplicationEnabledSetting(component.getPackageName());
            int componentSetting = pm.getComponentEnabledSetting(component);
            enabled &= appSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && appSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && appSetting
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            enabled &= componentSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && componentSetting
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            return enabled;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Bitmap iconBitmap(Context context, Drawable drawable) {
        int size = Math.max(1, dp(context, 48));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    private static void launchEntry(Context context, AppEntry entry) {
        if (usesRootFreeze(context, entry.packageName)) {
            launchWithRootFreeze(context, entry.packageName, entry.label);
        } else {
            launchNormally(context, entry.packageName, entry.component);
        }
    }

    private static void launchNormally(
            Context context,
            String packageName,
            ComponentName fallbackComponent) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent == null && fallbackComponent != null) {
                intent = Intent.makeMainActivity(fallbackComponent);
            }
            if (intent == null) {
                Toast.makeText(
                        context,
                        "App is disabled. Select it under Root-freeze apps.",
                        Toast.LENGTH_LONG)
                        .show();
                return;
            }
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    context,
                    "Unable to open app. Select it under Root-freeze apps if it is frozen.",
                    Toast.LENGTH_LONG)
                    .show();
        }
    }

    private static void launchWithRootFreeze(
            Context context,
            String packageName,
            String label) {
        Intent proxy = new Intent();
        proxy.setComponent(new ComponentName(FREEZE_PACKAGE, FREEZE_PROXY));
        proxy.putExtra(FREEZE_EXTRA_PACKAGE, packageName);
        proxy.putExtra(
                FREEZE_EXTRA_LABEL,
                label == null || label.trim().isEmpty() ? packageName : label);
        proxy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(proxy);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    context,
                    "Install or update Root Freeze Shortcuts first",
                    Toast.LENGTH_LONG)
                    .show();
        }
    }

    private static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static LinearLayout verticalRoot(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF202020);
        root.setPadding(
                dp(context, 12),
                dp(context, 12),
                dp(context, 12),
                dp(context, 12));
        return root;
    }

    private static TextView titleView(Context context, String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 56)));
        return title;
    }

    private static Button actionButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private static LinearLayout.LayoutParams weightedButtonParams() {
        return new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
