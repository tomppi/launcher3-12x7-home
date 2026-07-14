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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
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
    private static final String PREF_PAGE_PREFIX = "page_";
    private static final String EXTRA_COMPONENT = "component";
    private static final int FAVORITES_PER_PAGE = 12;

    private static final String ACTION_PREVIOUS =
            "io.github.tomppi.drawerwidget.action.PREVIOUS";
    private static final String ACTION_NEXT =
            "io.github.tomppi.drawerwidget.action.NEXT";
    private static final String ACTION_LAUNCH =
            "io.github.tomppi.drawerwidget.action.LAUNCH";

    private DrawerWidget() {}

    public static final class Provider extends AppWidgetProvider {
        @Override
        public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
            for (int appWidgetId : appWidgetIds) {
                updateWidget(context, manager, appWidgetId);
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
            if (!ACTION_PREVIOUS.equals(action) && !ACTION_NEXT.equals(action)) {
                return;
            }

            int appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return;
            }

            int pageCount = favoritePageCount(context);
            int page = currentPage(context, appWidgetId, pageCount);
            if (ACTION_PREVIOUS.equals(action)) {
                page = Math.max(0, page - 1);
            } else {
                page = Math.min(pageCount - 1, page + 1);
            }
            prefs(context).edit().putInt(PREF_PAGE_PREFIX + appWidgetId, page).apply();
            updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId);
        }
    }

    public static final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAllWidgets(context);
        }
    }

    /**
     * Receives collection-item clicks and launches the target activity directly.
     *
     * Using a broadcast PendingIntent template avoids creating any activity/task owned by the
     * widget app. The launched app therefore owns its own task and Recents card.
     */
    public static final class LaunchReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_LAUNCH.equals(intent.getAction())) {
                return;
            }

            String flattened = intent.getStringExtra(EXTRA_COMPONENT);
            ComponentName component = flattened == null
                    ? null
                    : ComponentName.unflattenFromString(flattened);
            if (component != null) {
                launchComponent(context, component);
            }
        }
    }

    public static final class AllAppsService extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            return new AppsFactory(getApplicationContext(), false, intent);
        }
    }

    public static final class FavoritesService extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            return new AppsFactory(getApplicationContext(), true, intent);
        }
    }

    private static final class AppsFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private final boolean favoritesOnly;
        private final int appWidgetId;
        private List<AppEntry> entries = new ArrayList<>();

        AppsFactory(Context context, boolean favoritesOnly, Intent intent) {
            this.context = context;
            this.favoritesOnly = favoritesOnly;
            this.appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
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
            List<AppEntry> allApps = loadApps(context);
            if (!favoritesOnly) {
                entries = allApps;
                return;
            }

            Map<String, AppEntry> byComponent = new HashMap<>();
            for (AppEntry app : allApps) {
                byComponent.put(app.component.flattenToString(), app);
            }

            List<AppEntry> favorites = new ArrayList<>();
            for (String component : loadFavoriteComponents(context)) {
                AppEntry app = byComponent.get(component);
                if (app != null) {
                    favorites.add(app);
                }
            }

            int pageCount = Math.max(
                    1,
                    (favorites.size() + FAVORITES_PER_PAGE - 1) / FAVORITES_PER_PAGE);
            int page = currentPage(context, appWidgetId, pageCount);
            int start = page * FAVORITES_PER_PAGE;
            int end = Math.min(favorites.size(), start + FAVORITES_PER_PAGE);
            entries = start < end
                    ? new ArrayList<>(favorites.subList(start, end))
                    : new ArrayList<>();
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

            AppEntry entry = entries.get(position);
            RemoteViews views = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_app_item);
            views.setTextViewText(R.id.app_label, entry.label);
            views.setImageViewBitmap(R.id.app_icon, iconBitmap(context, entry.icon));

            Intent fillIn = new Intent();
            fillIn.putExtra(EXTRA_COMPONENT, entry.component.flattenToString());
            fillIn.setData(Uri.parse(
                    "drawer-widget://launch/" + Uri.encode(entry.component.flattenToString())));
            views.setOnClickFillInIntent(R.id.app_item_root, fillIn);
            return views;
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
            return entries.get(position).component.flattenToString().hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
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
                launchComponent(this, shownApps.get(position).component);
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
                                || app.label.toLowerCase(Locale.getDefault()).contains(query)) {
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

            TextView title = titleView(this, "Favorite apps");
            root.addView(title);

            listView = new ListView(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            root.addView(listView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f));

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            Button add = actionButton(this, "Add");
            Button up = actionButton(this, "↑");
            Button down = actionButton(this, "↓");
            Button remove = actionButton(this, "Remove");
            actions.addView(add, weightedButtonParams());
            actions.addView(up, weightedButtonParams());
            actions.addView(down, weightedButtonParams());
            actions.addView(remove, weightedButtonParams());
            root.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(this, 56)));
            setContentView(root);

            listView.setOnItemClickListener((parent, view, position, id) -> selected = position);
            add.setOnClickListener(v -> startActivity(new Intent(this, AddFavoritesActivity.class)));
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
                labels.add(app.label);
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
            return apps.get(position).component.flattenToString().hashCode();
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
            label.setText(app.label);
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
            return apps.get(position).component.flattenToString().hashCode();
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
                label.setTextSize(16f);
                label.setPadding(dp(context, 14), 0, 0, 0);
                row.addView(icon, new LinearLayout.LayoutParams(
                        dp(context, 44),
                        dp(context, 44)));
                row.addView(label, new LinearLayout.LayoutParams(
                        0,
                        dp(context, 52),
                        1f));
            }
            AppEntry app = apps.get(position);
            icon.setImageDrawable(app.icon);
            label.setText(app.label);
            return row;
        }
    }

    private static final class AppEntry {
        final ComponentName component;
        final String label;
        final Drawable icon;

        AppEntry(ComponentName component, String label, Drawable icon) {
            this.component = component;
            this.label = label;
            this.icon = icon;
        }
    }

    private static void updateWidget(
            Context context,
            AppWidgetManager manager,
            int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_drawer);

        Intent allAppsIntent = new Intent(context, AllAppsService.class);
        allAppsIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        allAppsIntent.setData(Uri.parse("drawer-widget://all/" + appWidgetId));
        views.setRemoteAdapter(R.id.all_apps_grid, allAppsIntent);

        Intent favoritesIntent = new Intent(context, FavoritesService.class);
        favoritesIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        favoritesIntent.setData(Uri.parse("drawer-widget://favorites/" + appWidgetId));
        views.setRemoteAdapter(R.id.favorites_grid, favoritesIntent);
        views.setEmptyView(R.id.favorites_grid, R.id.empty_favorites);

        Intent launchIntent = new Intent(context, LaunchReceiver.class);
        launchIntent.setAction(ACTION_LAUNCH);
        launchIntent.setData(Uri.parse("drawer-widget://template/" + appWidgetId));
        PendingIntent launchTemplate = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.all_apps_grid, launchTemplate);
        views.setPendingIntentTemplate(R.id.favorites_grid, launchTemplate);

        views.setOnClickPendingIntent(
                R.id.search_bar,
                activityPendingIntent(context, SearchActivity.class, appWidgetId * 10 + 1));
        views.setOnClickPendingIntent(
                R.id.edit_favorites,
                activityPendingIntent(context, FavoritesActivity.class, appWidgetId * 10 + 2));
        views.setOnClickPendingIntent(
                R.id.favorites_previous,
                pagePendingIntent(context, appWidgetId, ACTION_PREVIOUS, appWidgetId * 10 + 3));
        views.setOnClickPendingIntent(
                R.id.favorites_next,
                pagePendingIntent(context, appWidgetId, ACTION_NEXT, appWidgetId * 10 + 4));

        int pageCount = favoritePageCount(context);
        int page = currentPage(context, appWidgetId, pageCount);
        views.setTextViewText(R.id.favorites_page, pageIndicator(page, pageCount));
        views.setTextColor(R.id.favorites_previous, page > 0 ? 0xFFFFFFFF : 0xFF777777);
        views.setTextColor(
                R.id.favorites_next,
                page < pageCount - 1 ? 0xFFFFFFFF : 0xFF777777);

        manager.updateAppWidget(appWidgetId, views);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.all_apps_grid);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.favorites_grid);
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

    private static PendingIntent pagePendingIntent(
            Context context,
            int appWidgetId,
            String action,
            int requestCode) {
        Intent intent = new Intent(context, Provider.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.setData(Uri.parse("drawer-widget://page/" + requestCode));
        return PendingIntent.getBroadcast(
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

    private static int favoritePageCount(Context context) {
        int size = loadFavoriteComponents(context).size();
        return Math.max(1, (size + FAVORITES_PER_PAGE - 1) / FAVORITES_PER_PAGE);
    }

    private static int currentPage(Context context, int appWidgetId, int pageCount) {
        int page = prefs(context).getInt(PREF_PAGE_PREFIX + appWidgetId, 0);
        int clamped = Math.max(0, Math.min(page, pageCount - 1));
        if (clamped != page) {
            prefs(context).edit().putInt(PREF_PAGE_PREFIX + appWidgetId, clamped).apply();
        }
        return clamped;
    }

    private static String pageIndicator(int page, int pageCount) {
        if (pageCount <= 1) {
            return "●";
        }
        if (pageCount > 5) {
            return (page + 1) + " / " + pageCount;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(i == page ? '●' : '○');
        }
        return builder.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> loadFavoriteComponents(Context context) {
        String stored = prefs(context).getString(PREF_FAVORITES, "");
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
        LinkedHashSet<String> unique = new LinkedHashSet<>(components);
        StringBuilder builder = new StringBuilder();
        for (String component : unique) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(component);
        }
        prefs(context).edit().putString(PREF_FAVORITES, builder.toString()).apply();
        refreshAllWidgets(context);
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
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            ComponentName component = new ComponentName(
                    info.activityInfo.packageName,
                    info.activityInfo.name);
            String flattened = component.flattenToString();
            if (!seen.add(flattened)
                    || context.getPackageName().equals(component.getPackageName())) {
                continue;
            }
            CharSequence loadedLabel = info.loadLabel(packageManager);
            String label = loadedLabel == null
                    ? component.getShortClassName()
                    : loadedLabel.toString();
            Drawable icon = info.loadIcon(packageManager);
            apps.add(new AppEntry(component, label, icon));
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

    private static Bitmap iconBitmap(Context context, Drawable drawable) {
        int size = Math.max(1, dp(context, 48));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    private static void launchComponent(Context context, ComponentName component) {
        try {
            Intent intent = Intent.makeMainActivity(component);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(context, "Unable to open app", Toast.LENGTH_SHORT).show();
        }
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
