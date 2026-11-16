package org.ztmetro.transitiontrack;

import android.os.IBinder;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Stub LSPosed module for the ztmetro app -> HOME transition research track.
 *
 * It does not animate anything yet. It logs which Shell transition handler consumes
 * the transition and what the transition contains, so the hook that will replace the
 * animation can be chosen from observed data instead of from guesses.
 *
 * Two hook sites are deliberately covered:
 *   - SystemUI process (WMShell): RemoteTransitionHandler / DefaultTransitionHandler.
 *   - Launcher3 process: QuickstepTransitionManager (only when Launcher3 is the
 *     remote-transition owner, i.e. stock HOME or gesture/recents path).
 */
public class TransitionTrackModule implements IXposedHookLoadPackage {
    private static final String TAG = "ztmetro-transition";

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if ("com.android.systemui".equals(lp.packageName)) {
            hookShell(lp.classLoader);
        } else if ("com.android.launcher3".equals(lp.packageName)) {
            hookLauncher(lp.classLoader);
        }
    }

    private static void hookShell(ClassLoader cl) {
        try {
            final Class<?> finishCb = XposedHelpers.findClass(
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback", cl);
            hook("com.android.wm.shell.transition.RemoteTransitionHandler", cl,
                    "startAnimation", finishCb, "remote");
            hook("com.android.wm.shell.transition.DefaultTransitionHandler", cl,
                    "startAnimation", finishCb, "default");
            XposedBridge.log(TAG + ": shell hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": shell hook failed: " + t);
        }
    }

    private static void hook(String className, ClassLoader cl, String method, Class<?> finishCb,
            final String who) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, method,
                    IBinder.class, TransitionInfo.class,
                    SurfaceControl.Transaction.class, SurfaceControl.Transaction.class,
                    finishCb,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            TransitionInfo info = (TransitionInfo) param.args[1];
                            StringBuilder sb = new StringBuilder();
                            sb.append(TAG).append(" ").append(who)
                              .append(" type=").append(info.getType())
                              .append(" changes=").append(info.getChanges().size());
                            for (TransitionInfo.Change c : info.getChanges()) {
                                sb.append(" [mode=").append(c.getMode());
                                if (c.getTaskInfo() != null) {
                                    sb.append(" task=").append(c.getTaskInfo().taskId);
                                }
                                if (c.getActivityComponent() != null) {
                                    sb.append(" act=").append(c.getActivityComponent().toShortString());
                                }
                                sb.append("]");
                            }
                            XposedBridge.log(sb.toString());
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + className + "#" + method + " failed: " + t);
        }
    }

    private static void hookLauncher(ClassLoader cl) {
        try {
            final Class<?> result = XposedHelpers.findClass(
                    "com.android.launcher3.LauncherAnimationRunner$AnimationResult", cl);
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.QuickstepTransitionManager$WallpaperOpenLauncherAnimationRunner",
                    cl, "onAnimationStart",
                    int.class, RemoteAnimationTarget[].class, RemoteAnimationTarget[].class,
                    RemoteAnimationTarget[].class, result,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": launcher wallpaper-open start transit="
                                    + param.args[0]
                                    + " appTargets=" + ((Object[]) param.args[1]).length
                                    + " wallpaperTargets=" + ((Object[]) param.args[2]).length);
                        }
                    });
            XposedBridge.log(TAG + ": launcher hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": launcher hook failed: " + t);
        }
    }
}
