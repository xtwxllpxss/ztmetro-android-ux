package org.ztmetro.settingsheader;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckedTextView;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Settings alert dialogs — requirement 2: black window, 0 corner radius, white 20 sp
 * title, accent {@code #0050EF} buttons, standard dim left untouched.
 *
 * <p>A dialog is a separate window, not part of the Activity content tree that
 * {@link ScreenPass} walks, so this is a targeted styling of the window background,
 * {@code @android:id/alertTitle} and the three {@code @android:id/button*} text buttons.
 * There is no tree walk: every lookup is an id lookup on the dialog decor (plus a
 * bounded pass over the single-choice list children when the dialog has one).
 *
 * <p>The window background is replaced with a flat {@link ColorDrawable}, which removes
 * Material's rounded {@code MaterialShapeDrawable}/{@code InsetDrawable} corners and the
 * grey surface fill in one step.  {@code FLAG_DIM_BEHIND} and the theme's
 * {@code backgroundDimAmount} are not touched: the scrim stays exactly the standard dim.
 *
 * <p>Installed only in the {@code com.android.settings} process; dialogs in other
 * processes are left stock.
 */
final class DialogStyler {

    private static final String TAG = "ztmetro-header";
    private static final int BLACK = Color.BLACK;

    private static boolean sInstalled;

    private DialogStyler() {
    }

    static void install(ClassLoader classLoader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod("android.app.Dialog", classLoader, "show",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Dialog)) {
                                return;
                            }
                            try {
                                apply((Dialog) param.thisObject);
                            } catch (Throwable t) {
                                Log.d(TAG, "dialog styling skipped: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Dialog.show (Settings dialogs -> black, square)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Dialog.show hook failed, dialogs stay stock");
            XposedBridge.log(t);
        }
    }

    /** @return number of views the styling touched. */
    static int apply(Dialog dialog) {
        if (dialog == null) {
            return 0;
        }
        Window window = dialog.getWindow();
        if (window == null) {
            return 0;
        }
        View decor = window.getDecorView();
        if (decor == null) {
            return 0;
        }
        Context context = window.getContext() != null
                ? window.getContext() : decor.getContext();
        if (context == null
                || !SettingsHeaderHooks.SETTINGS_PACKAGE.equals(context.getPackageName())) {
            return 0;   // not a Settings dialog
        }
        ScreenPass.ensureConfigured(context);

        int applies = 0;
        try {
            window.setBackgroundDrawable(new ColorDrawable(BLACK));   // 0 corner radius
            decor.setBackgroundColor(BLACK);
            applies++;
        } catch (Throwable t) {
            Log.d(TAG, "dialog window background skipped: " + t);
        }
        View content = decor.findViewById(android.R.id.content);
        if (content != null && !isBlack(content.getBackground())) {
            content.setBackgroundColor(BLACK);
            applies++;
        }

        TextView title = findViewById(decor, "alertTitle");

        if (title != null) {
            if (title.getCurrentTextColor() != ScreenPass.valueColor()) {
                title.setTextColor(ScreenPass.valueColor());
                applies++;
            }
            if (title.getTextSize() != ScreenPass.dialogTitlePx()) {
                title.setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenPass.dialogTitlePx());
                applies++;
            }
            title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            title.setIncludeFontPadding(false);
        }

        for (int id : new int[]{android.R.id.button1, android.R.id.button2,
                android.R.id.button3}) {
            View button = decor.findViewById(id);
            if (button instanceof TextView) {
                TextView text = (TextView) button;
                if (text.getCurrentTextColor() != ScreenPass.accentColor()) {
                    text.setTextColor(ScreenPass.accentColor());
                    applies++;
                }
            }
        }

        // Single-choice dialogs (preferred network type, ...): white labels, accent dot.
        View list = findViewById(decor, "select_dialog_listview");
        if (list instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) list;
            ColorStateListHolder holder = ColorStateListHolder.create(
                    ScreenPass.accentColor(), ScreenPass.labelColor());
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof CheckedTextView)) {
                    continue;
                }
                CheckedTextView item = (CheckedTextView) child;
                if (item.getCurrentTextColor() != ScreenPass.valueColor()) {
                    item.setTextColor(ScreenPass.valueColor());
                    applies++;
                }
                if (!holder.list.equals(item.getCheckMarkTintList())) {
                    item.setCheckMarkTintList(holder.list);
                    applies++;
                }
            }
        }

        Log.i(TAG, "dialog [dialog]: " + applies + " applies, title="
                + (title != null ? "20sp white" : "none") + ", corners=0, dim=theme default");
        return applies;
    }

    /**
     * Framework ids that are not exposed on {@code android.R.id} (alertTitle,
     * select_dialog_listview) are resolved by name at runtime.
     */
    private static TextView findViewById(View decor, String name) {
        View view = findViewByIdView(decor, name);
        return view instanceof TextView ? (TextView) view : null;
    }

    private static View findViewByIdView(View decor, String name) {
        // alertTitle / select_dialog_listview are not on android.R.id.  They may be the
        // framework id or (for Material dialogs) an id merged into the app's resource table;
        // try both, never throw.
        String app = decor.getContext() == null
                ? null : decor.getContext().getPackageName();
        String[] packages = { "android", app, "com.android.settings",
                "com.google.android.material" };
        for (String pkg : packages) {
            if (pkg == null) {
                continue;
            }
            try {
                int id = decor.getResources().getIdentifier(name, "id", pkg);
                if (id != 0) {
                    View view = decor.findViewById(id);
                    if (view != null) {
                        return view;
                    }
                }
            } catch (Throwable ignored) {
                // try the next package
            }
        }
        return null;
    }

    private static boolean isBlack(android.graphics.drawable.Drawable drawable) {
        return drawable instanceof ColorDrawable
                && ((ColorDrawable) drawable).getColor() == BLACK;
    }

    /** Reusable checked/unchecked colour list for the list-dialog radio dots. */
    private static final class ColorStateListHolder {
        final android.content.res.ColorStateList list;

        private ColorStateListHolder(android.content.res.ColorStateList list) {
            this.list = list;
        }

        static ColorStateListHolder create(int checked, int unchecked) {
            return new ColorStateListHolder(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{checked, unchecked}));
        }
    }
}
