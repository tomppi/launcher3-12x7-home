package io.github.tomppi.launchergrid712;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS Launcher3 grid override for the Samsung Galaxy Z Fold5 (q5q).
 *
 * The hook runs before InvariantDeviceProfile initializes the selected grid. It changes only the
 * workspace row/column counts and caps workspace icon/text sizes so twelve rows remain usable.
 * All-apps fields, folder fields, hotseat fields, and search fields are deliberately untouched.
 */
public final class LauncherGridHook implements IXposedHookLoadPackage {
    private static final String TAG = "Q5QLauncherGrid";
    private static final String TARGET_PACKAGE = "com.android.launcher3";
    private static final String IDP_CLASS = "com.android.launcher3.InvariantDeviceProfile";

    private static final int WORKSPACE_COLUMNS = 7;
    private static final int WORKSPACE_ROWS = 12;

    // Only workspace arrays are capped. The app drawer has separate allApps* arrays and is untouched.
    private static final float MAX_WORKSPACE_ICON_DP = 42.0f;
    private static final float MAX_WORKSPACE_TEXT_SP = 10.5f;

    private static volatile boolean appliedLogWritten;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        final Class<?> idpClass;
        try {
            idpClass = XposedHelpers.findClass(IDP_CLASS, lpparam.classLoader);
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
                                XposedBridge.log(TAG + ": unexpected initGridForDisplayOption signature");
                                return;
                            }

                            Object displayOption = param.args[1];
                            Object gridOption = XposedHelpers.getObjectField(displayOption, "grid");

                            // GridOption fields are final in Launcher3, but LSPosed reflection can update
                            // the selected instance before Launcher3 copies them into the active profile.
                            XposedHelpers.setIntField(gridOption, "numColumns", WORKSPACE_COLUMNS);
                            XposedHelpers.setIntField(gridOption, "numRows", WORKSPACE_ROWS);

                            capFloatArray(displayOption, "iconSizes", MAX_WORKSPACE_ICON_DP);
                            capFloatArray(displayOption, "textSizes", MAX_WORKSPACE_TEXT_SP);

                            if (!appliedLogWritten) {
                                appliedLogWritten = true;
                                XposedBridge.log(TAG + ": applied 7 columns x 12 rows; app drawer untouched");
                            }
                        } catch (Throwable error) {
                            // Never crash Launcher3 because of a failed compatibility hook.
                            XposedBridge.log(TAG + ": grid hook failed; leaving stock grid in place");
                            XposedBridge.log(error);
                        }
                    }
                });

        if (hooks == null || hooks.isEmpty()) {
            XposedBridge.log(TAG + ": initGridForDisplayOption was not found; module is incompatible");
        } else {
            XposedBridge.log(TAG + ": hook installed for " + TARGET_PACKAGE);
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
