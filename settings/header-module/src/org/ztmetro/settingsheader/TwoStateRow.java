package org.ztmetro.settingsheader;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Two-state (switch) row value line — {@code ВКЛ} / {@code ВЫКЛ} at 22 sp white.
 *
 * <p>Deliverable 2026-11-16, requirement 3: a switch row keeps the ztmetro two-line
 * treatment, but its value line is the live state, not the stock summary.  The switch
 * itself keeps the cobalt track ({@code #0050EF}) and the white thumb from
 * {@link MetroTinter}; this class only writes the value line.
 *
 * <p>Row height stability: the state line is forced {@code VISIBLE} and always has exactly
 * one of the two one-line texts, so toggling never adds/removes a line.  The stock
 * {@code summary} visibility is restored once on the first pass and from then on only the
 * text changes.
 *
 * <p>The same rule is re-applied on every {@code CompoundButton.setChecked()} so a
 * RecyclerView re-bind of the row (Settings binds the switch after the summary) cannot put
 * the stock summary back; that hook is local (walk up to the row, {@code findViewById}),
 * not a tree walk.
 */
final class TwoStateRow {

    private static final String TAG = "ztmetro-header";

    static final String ON = "ВКЛ";
    static final String OFF = "ВЫКЛ";

    private static final int MAX_ANCESTORS = 8;

    private static boolean sInstalled;
    private static boolean sUpdating;
    /** Summary view -> its two-state switch, so a late summary update cannot undo the state. */
    private static final java.util.WeakHashMap<TextView, CompoundButton> ROWS =
            new java.util.WeakHashMap<>();

    private TwoStateRow() {
    }

    /** Applies the state line; @return true when the view changed. */
    static boolean applyState(CompoundButton button, TextView value) {
        if (button == null || value == null) {
            return false;
        }
        boolean changed = false;
        String text = button.isChecked() ? ON : OFF;
        if (!text.contentEquals(value.getText())) {
            value.setText(text);
            changed = true;
        }
        if (value.getVisibility() != View.VISIBLE) {
            value.setVisibility(View.VISIBLE);
            changed = true;
        }
        if (RowType.styleValue(value)) {
            changed = true;
        }
        ROWS.put(value, button);
        return changed;
    }

    /**
     * Hook {@code CompoundButton.setChecked(boolean)} once per target process.  Inert for
     * radio/checkbox buttons and for a switch that is not inside a preference row (e.g. the
     * {@code switch_bar} master switch): no value line, nothing to keep in sync.
     */
    static void install(ClassLoader classLoader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            XposedBridge.hookAllMethods(CompoundButton.class, "setChecked",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sUpdating || !(param.thisObject instanceof CompoundButton)) {
                                return;
                            }
                            CompoundButton button = (CompoundButton) param.thisObject;
                            if (!Views.isSwitch(button)) {
                                return;
                            }
                            try {
                                sync(button);
                            } catch (Throwable t) {
                                Log.d(TAG, "switch state sync skipped: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked CompoundButton.setChecked (switch value line)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CompoundButton.setChecked hook failed");
            XposedBridge.log(t);
        }
        // Settings sometimes rewrites a preference summary asynchronously (Wi-Fi state
        // callbacks).  For a summary that belongs to a switch row the state text wins:
        // one id check + one weak-map lookup per setText, no tree walk.
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sUpdating || !(param.thisObject instanceof TextView)) {
                        return;
                    }
                    TextView value = (TextView) param.thisObject;
                    if (value.getId() != android.R.id.summary) {
                        return;
                    }
                    CompoundButton button = ROWS.get(value);
                    if (button == null) {
                        return;
                    }
                    String text = button.isChecked() ? ON : OFF;
                    if (text.contentEquals(value.getText())) {
                        return;
                    }
                    sUpdating = true;
                    try {
                        value.setText(text);
                    } finally {
                        sUpdating = false;
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TextView.setText hook failed (summary may lag)");
            XposedBridge.log(t);
        }
    }

    private static void sync(CompoundButton button) {
        Context context = button.getContext();
        ScreenPass.ensureConfigured(context);
        ViewGroup row = findRow(button);
        if (row == null) {
            return;
        }
        TextView value = row.findViewById(android.R.id.summary);
        if (value == null) {
            // A switch row without a summary line: the title stays the 22 sp value line
            // (there is nothing to write the state into); the pass log counts it under
            // switch rows without a state line.
            return;
        }
        sUpdating = true;
        try {
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) {
                RowType.styleLabel(title);
            }
            if (applyState(button, value)) {
                Log.d(TAG, "switch state line -> " + (button.isChecked() ? ON : OFF)
                        + " (height unchanged: one state line)");
            }
        } finally {
            sUpdating = false;
        }
    }

    /** Nearest ancestor that owns an {@code @android:id/summary} (the preference row). */
    private static ViewGroup findRow(View view) {
        View current = view;
        for (int i = 0; i < MAX_ANCESTORS; i++) {
            if (!(current.getParent() instanceof View)) {
                return null;
            }
            current = (View) current.getParent();
            if (current instanceof ViewGroup
                    && ((ViewGroup) current).findViewById(android.R.id.summary) != null) {
                return (ViewGroup) current;
            }
        }
        return null;
    }
}
