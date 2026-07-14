package io.github.tomppi.launchergrid712;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS Launcher3 adjustments for the Samsung Galaxy Z Fold5 (q5q).
 *
 * <p>The module keeps Launcher3's stock foldable page model while changing the workspace grid and
 * widget resize limits. All Apps, folders, hotseat, taskbar, gestures, and Recents are deliberately
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

    private static final int WORKSPACE_COLUMNS = 7;
    private static final int WORKSPACE_ROWS = 12;

    private static final int WIDGET_MIN_COLUMNS = 1;
    private static final int WIDGET_MIN_ROWS = 1;
    private static final int WIDGET_RESIZE_HORIZONTAL_AND_VERTICAL = 3;

    // Only workspace arrays are capped. The app drawer has separate allApps* arrays and is untouched.
    private static final float MAX_WORKSPACE_ICON_DP = 42.0f;
    private static final float MAX_WORKSPACE_TEXT_SP = 10.5f;

    private static volatile boolean gridAppliedLogWritten;
    private static volatile boolean widgetMetadataLogWritten;
    private static volatile boolean widgetFrameLogWritten;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        installGridHook(lpparam.classLoader);
        installWidgetMetadataHook(lpparam.classLoader);
        installWidgetResizeFrameHook(lpparam.classLoader);
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
                            Object gridOption =
                                    XposedHelpers.getObjectField(displayOption, "grid");

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
     * Relaxes the widget provider metadata after Launcher3 calculates spans from the widget manifest.
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

    /** Applies the same limits directly to the active resize frame as a compatibility fallback. */
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
