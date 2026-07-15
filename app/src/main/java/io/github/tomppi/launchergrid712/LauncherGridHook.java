package io.github.tomppi.launchergrid712;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS Launcher3 adjustments for the Samsung Galaxy Z Fold5 (q5q).
 *
 * <p>The module keeps Launcher3's stock foldable page model while changing the workspace grid and
 * widget resize limits. It also adds horizontal swipe paging to the companion Drawer Widget's
 * static Favorites area. All Apps, folders, hotseat, taskbar, gestures, and Recents are otherwise
 * left alone.</p>
 */
public final class LauncherGridHook implements IXposedHookLoadPackage {
    private static final String TAG = "Q5QLauncherGrid";
    private static final String TARGET_PACKAGE = "com.android.launcher3";

    private static final String IDP_CLASS = "com.android.launcher3.InvariantDeviceProfile";
    private static final String WIDGET_PROVIDER_INFO_CLASS =
            "com.android.launcher3.widget.LauncherAppWidgetProviderInfo";
    private static final String WIDGET_RESIZE_FRAME_CLASS =
            "com.android.launcher3.AppWidgetResizeFrame";

    private static final String DRAWER_WIDGET_PACKAGE = "io.github.tomppi.drawerwidget";
    private static final String ACTION_FAVORITES_NEXT =
            "io.github.tomppi.drawerwidget.action.FAVORITES_NEXT";
    private static final String ACTION_FAVORITES_PREVIOUS =
            "io.github.tomppi.drawerwidget.action.FAVORITES_PREVIOUS";
    private static final String FAVORITES_VIEW_NAME = "favorites_touch_area";
    private static final String FAVORITE_SLOT_PREFIX = "favorite_slot_";

    private static final int WORKSPACE_COLUMNS = 7;
    private static final int WORKSPACE_ROWS = 12;

    private static final int WIDGET_MIN_COLUMNS = 1;
    private static final int WIDGET_MIN_ROWS = 1;
    private static final int WIDGET_RESIZE_HORIZONTAL_AND_VERTICAL = 3;

    // Only workspace arrays are capped. The app drawer has separate allApps* arrays and is untouched.
    private static final float MAX_WORKSPACE_ICON_DP = 42.0f;
    private static final float MAX_WORKSPACE_TEXT_SP = 10.5f;

    private static final Map<View, Boolean> SWIPE_LISTENERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Weak keys alone are not sufficient when a map value strongly captures its key. Each binding
     * therefore keeps only a WeakReference to its host. The host owns the attach-state listener and
     * the ViewTreeObserver owns it while attached, so no static strong path keeps old Launcher view
     * trees alive.
     */
    private static final Map<AppWidgetHostView, FavoritesHostBinding> HOST_BINDINGS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean gridAppliedLogWritten;
    private static volatile boolean widgetMetadataLogWritten;
    private static volatile boolean widgetFrameLogWritten;
    private static volatile boolean favoritesSwipeLogWritten;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        installGridHook(lpparam.classLoader);
        installWidgetMetadataHook(lpparam.classLoader);
        installWidgetResizeFrameHook(lpparam.classLoader);
        installDrawerFavoritesSwipeHook();
    }

    private static void installGridHook(ClassLoader classLoader) {
        final Class<?> idpClass;
        try {
            idpClass = XposedHelpers.findClass(IDP_CLASS, classLoader);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": unable to find " + IDP_CLASS);
            XposedBridge.log(error);
            return;
        }

        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                idpClass,
                "initGridForDisplayOption",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length < 2 || param.args[1] == null) {
                                XposedBridge.log(
                                        TAG + ": unexpected initGridForDisplayOption signature");
                                return;
                            }

                            Object displayOption = param.args[1];
                            Object gridOption = XposedHelpers.getObjectField(displayOption, "grid");

                            XposedHelpers.setIntField(
                                    gridOption,
                                    "numColumns",
                                    WORKSPACE_COLUMNS);
                            XposedHelpers.setIntField(
                                    gridOption,
                                    "numRows",
                                    WORKSPACE_ROWS);

                            capFloatArray(displayOption, "iconSizes", MAX_WORKSPACE_ICON_DP);
                            capFloatArray(displayOption, "textSizes", MAX_WORKSPACE_TEXT_SP);

                            if (!gridAppliedLogWritten) {
                                gridAppliedLogWritten = true;
                                XposedBridge.log(
                                        TAG
                                                + ": applied 7 columns x 12 rows; app drawer untouched");
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(
                                    TAG + ": grid hook failed; leaving stock grid in place");
                            XposedBridge.log(error);
                        }
                    }
                });

        logHookResult(hooks, "initGridForDisplayOption");
    }

    /**
     * Relaxes every widget provider's metadata after Launcher3 calculates spans from its manifest.
     * The stock foldable two-panel page model is not changed.
     */
    private static void installWidgetMetadataHook(ClassLoader classLoader) {
        final Class<?> widgetProviderInfoClass;
        try {
            widgetProviderInfoClass =
                    XposedHelpers.findClass(WIDGET_PROVIDER_INFO_CLASS, classLoader);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": unable to find " + WIDGET_PROVIDER_INFO_CLASS);
            XposedBridge.log(error);
            return;
        }

        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                widgetProviderInfoClass,
                "initSpans",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object info = param.thisObject;
                            XposedHelpers.setIntField(info, "minSpanX", WIDGET_MIN_COLUMNS);
                            XposedHelpers.setIntField(info, "minSpanY", WIDGET_MIN_ROWS);
                            XposedHelpers.setIntField(info, "maxSpanX", WORKSPACE_COLUMNS);
                            XposedHelpers.setIntField(info, "maxSpanY", WORKSPACE_ROWS);
                            XposedHelpers.setIntField(
                                    info,
                                    "resizeMode",
                                    WIDGET_RESIZE_HORIZONTAL_AND_VERTICAL);
                            XposedHelpers.setBooleanField(info, "mIsMinSizeFulfilled", true);

                            if (!widgetMetadataLogWritten) {
                                widgetMetadataLogWritten = true;
                                XposedBridge.log(
                                        TAG
                                                + ": widget metadata set to min 1x1, max 7x12, both directions");
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + ": widget metadata hook failed");
                            XposedBridge.log(error);
                        }
                    }
                });

        logHookResult(hooks, "LauncherAppWidgetProviderInfo.initSpans");
    }

    /** Applies the same global limits directly to the active resize frame as a fallback. */
    private static void installWidgetResizeFrameHook(ClassLoader classLoader) {
        final Class<?> resizeFrameClass;
        try {
            resizeFrameClass = XposedHelpers.findClass(WIDGET_RESIZE_FRAME_CLASS, classLoader);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": unable to find " + WIDGET_RESIZE_FRAME_CLASS);
            XposedBridge.log(error);
            return;
        }

        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                resizeFrameClass,
                "setupForWidget",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object frame = param.thisObject;
                            XposedHelpers.setIntField(frame, "minHSpan", WIDGET_MIN_COLUMNS);
                            XposedHelpers.setIntField(frame, "minVSpan", WIDGET_MIN_ROWS);
                            XposedHelpers.setIntField(frame, "maxHSpan", WORKSPACE_COLUMNS);
                            XposedHelpers.setIntField(frame, "maxVSpan", WORKSPACE_ROWS);

                            if (!widgetFrameLogWritten) {
                                widgetFrameLogWritten = true;
                                XposedBridge.log(
                                        TAG + ": active widget resize frame set to 1x1 through 7x12");
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + ": widget resize-frame hook failed");
                            XposedBridge.log(error);
                        }
                    }
                });

        logHookResult(hooks, "AppWidgetResizeFrame.setupForWidget");
    }

    /**
     * The Drawer Widget renders Favorites as a static 6x3 page. This Launcher3-side hook maps
     * left/right gestures over that page to explicit broadcasts that change the stored page index.
     */
    private static void installDrawerFavoritesSwipeHook() {
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                AppWidgetHostView.class,
                "updateAppWidget",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof AppWidgetHostView)) {
                            return;
                        }

                        AppWidgetHostView host = (AppWidgetHostView) param.thisObject;
                        if (!isDrawerWidgetHost(host)) {
                            return;
                        }

                        host.post(() -> attachFavoritesSwipeSupport(host));
                    }
                });

        logHookResult(hooks, "AppWidgetHostView.updateAppWidget");
    }

    private static boolean isDrawerWidgetHost(AppWidgetHostView host) {
        try {
            AppWidgetProviderInfo info = host.getAppWidgetInfo();
            return info != null
                    && info.provider != null
                    && DRAWER_WIDGET_PACKAGE.equals(info.provider.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void attachFavoritesSwipeSupport(AppWidgetHostView host) {
        attachToCurrentFavoritesTree(host);

        synchronized (HOST_BINDINGS) {
            FavoritesHostBinding existing = HOST_BINDINGS.get(host);
            if (existing != null) {
                existing.ensureGlobalLayoutListener();
                return;
            }

            FavoritesHostBinding binding = new FavoritesHostBinding(host);
            HOST_BINDINGS.put(host, binding);
            host.addOnAttachStateChangeListener(binding);
            binding.ensureGlobalLayoutListener();
        }
    }

    private static final class FavoritesHostBinding
            implements ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private final WeakReference<AppWidgetHostView> hostReference;
        private boolean globalLayoutListenerRegistered;

        FavoritesHostBinding(AppWidgetHostView host) {
            hostReference = new WeakReference<>(host);
        }

        void ensureGlobalLayoutListener() {
            AppWidgetHostView host = hostReference.get();
            if (host == null || globalLayoutListenerRegistered) {
                return;
            }
            ViewTreeObserver observer = host.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.addOnGlobalLayoutListener(this);
                globalLayoutListenerRegistered = true;
            }
        }

        private void removeGlobalLayoutListener(AppWidgetHostView host) {
            if (!globalLayoutListenerRegistered) {
                return;
            }
            ViewTreeObserver observer = host.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
            }
            globalLayoutListenerRegistered = false;
        }

        @Override
        public void onGlobalLayout() {
            AppWidgetHostView host = hostReference.get();
            if (host != null) {
                attachToCurrentFavoritesTree(host);
            }
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            AppWidgetHostView host = hostReference.get();
            if (host != null) {
                ensureGlobalLayoutListener();
                attachToCurrentFavoritesTree(host);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            AppWidgetHostView host = hostReference.get();
            if (host != null) {
                removeGlobalLayoutListener(host);
            }
        }
    }

    private static void attachToCurrentFavoritesTree(AppWidgetHostView host) {
        View favorites = findViewByEntryName(host, FAVORITES_VIEW_NAME);
        if (favorites == null) {
            return;
        }

        attachSwipeListenersRecursively(favorites, host);

        if (!favoritesSwipeLogWritten) {
            favoritesSwipeLogWritten = true;
            XposedBridge.log(
                    TAG + ": horizontal Favorites swipe broadcasts attached to Drawer Widget");
        }
    }

    private static void attachSwipeListenersRecursively(
            View view,
            AppWidgetHostView host) {
        String name = resourceEntryName(view);
        if (FAVORITES_VIEW_NAME.equals(name)
                || (name != null && name.startsWith(FAVORITE_SLOT_PREFIX))) {
            synchronized (SWIPE_LISTENERS) {
                if (!SWIPE_LISTENERS.containsKey(view)) {
                    view.setOnTouchListener(new HorizontalFavoritesSwipeListener(host));
                    SWIPE_LISTENERS.put(view, Boolean.TRUE);
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                attachSwipeListenersRecursively(group.getChildAt(i), host);
            }
        }
    }

    private static View findViewByEntryName(View view, String wantedName) {
        if (wantedName.equals(resourceEntryName(view))) {
            return view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByEntryName(group.getChildAt(i), wantedName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String resourceEntryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return null;
        }

        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class HorizontalFavoritesSwipeListener implements View.OnTouchListener {
        private final AppWidgetHostView host;
        private float downX;
        private float downY;
        private boolean horizontalSwipe;

        HorizontalFavoritesSwipeListener(AppWidgetHostView host) {
            this.host = host;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    horizontalSwipe = false;
                    requestNoParentIntercept(view, true);
                    return false;

                case MotionEvent.ACTION_MOVE:
                    float moveX = event.getRawX() - downX;
                    float moveY = event.getRawY() - downY;
                    int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    if (Math.abs(moveX) > slop
                            && Math.abs(moveX) > Math.abs(moveY) * 1.25f) {
                        horizontalSwipe = true;
                        requestNoParentIntercept(view, true);
                        view.setPressed(false);
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                    requestNoParentIntercept(view, false);
                    if (!horizontalSwipe) {
                        return false;
                    }

                    float deltaX = event.getRawX() - downX;
                    float deltaY = event.getRawY() - downY;
                    int minimum = Math.max(
                            dp(view, 42),
                            ViewConfiguration.get(view.getContext()).getScaledTouchSlop() * 3);

                    if (Math.abs(deltaX) >= minimum
                            && Math.abs(deltaX) > Math.abs(deltaY) * 1.25f) {
                        sendFavoritePageBroadcast(
                                deltaX < 0
                                        ? ACTION_FAVORITES_NEXT
                                        : ACTION_FAVORITES_PREVIOUS);
                    }
                    view.setPressed(false);
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    requestNoParentIntercept(view, false);
                    horizontalSwipe = false;
                    return false;

                default:
                    return false;
            }
        }

        private void sendFavoritePageBroadcast(String action) {
            int appWidgetId = host.getAppWidgetId();
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return;
            }

            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(
                    DRAWER_WIDGET_PACKAGE,
                    DRAWER_WIDGET_PACKAGE + ".DrawerWidget$Provider"));
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            host.getContext().sendBroadcast(intent);
        }

        private static void requestNoParentIntercept(View view, boolean disallow) {
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        }

        private static int dp(View view, int value) {
            return Math.round(value * view.getResources().getDisplayMetrics().density);
        }
    }

    private static void logHookResult(Set<XC_MethodHook.Unhook> hooks, String methodName) {
        if (hooks == null || hooks.isEmpty()) {
            XposedBridge.log(TAG + ": " + methodName + " was not found; hook is incompatible");
        } else {
            XposedBridge.log(TAG + ": hook installed for " + methodName);
        }
    }

    private static void capFloatArray(Object owner, String fieldName, float maximum) {
        Object value = XposedHelpers.getObjectField(owner, fieldName);
        if (!(value instanceof float[])) {
            return;
        }

        float[] values = (float[]) value;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > maximum) {
                values[i] = maximum;
            }
        }
    }
}
