package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.reflect.Method;

/**
 * The ztmetro signature bottom bar: a flat 40 dp strip with a back arrow on the left,
 * ALL-CAPS {@code ZRN} in the centre and a search glyph on the right — identical chrome on
 * the Settings home page and on every sub-screen.
 *
 * <p>Attached to the window decor (not to the Settings content) so fragment transactions
 * and {@code setContentView} calls cannot remove it; idempotent by tag
 * {@code ztmetro_bottom_bar}.  The activity's content root
 * ({@code android.R.id.content}) gets {@code paddingBottom = bar height + navigation-bar
 * inset}, so the 40 dp strip never covers the last preference row.  The original padding
 * is remembered per content view, so repeated passes do not accumulate it.
 *
 * <p>Back calls the activity's back invoker when one exists (androidx
 * {@code OnBackPressedDispatcher}, reached reflectively), else {@code Activity.onBackPressed()},
 * else {@code finish()}.  Search starts Settings with {@code action=android.settings.SETTINGS}
 * and the boolean extra {@code search}; if nothing resolves the intent the click is a
 * logged no-op.  Every failure leaves Settings exactly as it was.
 */
final class BottomBar {

    static final String TAG = "ztmetro-header";
    static final String BAR_TAG = "ztmetro_bottom_bar";

    private static final String TARGET_PACKAGE = "com.android.settings";
    private static final String SETTINGS_ACTION = "android.settings.SETTINGS";
    /** SettingsIntelligence's exported search activity (the surface we restyle). */
    private static final String APP_SEARCH_ACTION = "android.settings.APP_SEARCH_SETTINGS";
    private static final String INTELLIGENCE_PACKAGE = "com.android.settings.intelligence";

    private BottomBar() {
    }

    /**
     * True when the device has hardware/capacitive navigation keys (the ugglite does:
     * menu / home / back). On such a phone the ztmetro bottom bar would duplicate the
     * physical keys, so it is never drawn. Detection uses the framework boolean that also
     * decides whether the system shows a navigation bar.
     */
    private static boolean hasHardwareNavigationKeys(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "config_showNavigationBar", "bool", "android");
            if (id == 0) return false;
            return !activity.getResources().getBoolean(id);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Removes a previously attached bar and the extra content padding. */
    static void detach(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof FrameLayout) {
                View bar = decor.findViewWithTag(BAR_TAG);
                if (bar != null) ((FrameLayout) decor).removeView(bar);
            }
            View content = activity.findViewById(android.R.id.content);
            if (content != null) content.setPadding(content.getPaddingLeft(),
                    content.getPaddingTop(), content.getPaddingRight(), 0);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    static void attach(Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) {
                return;
            }
            if (hasHardwareNavigationKeys(activity)) {
                detach(activity);
                Log.i(TAG, "hardware navigation keys present: bottom bar suppressed");
                return;
            }
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof FrameLayout)) {
                Log.d(TAG, "window decor is not a FrameLayout, bottom bar skipped");
                return;
            }
            Resources moduleResources = HeaderResources.get(activity);
            int barHeight = HeaderViewFactory.bottomBarHeightPx(activity, moduleResources);

            View bar = decor.findViewWithTag(BAR_TAG);
            if (bar == null) {
                bar = HeaderViewFactory.createBottomBar(activity, moduleResources);
                if (bar == null) {
                    return;
                }
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM);
                ((FrameLayout) decor).addView(bar, params);
                wire(activity, bar);
                Log.i(TAG, "bottom bar attached (40dp: back / ZRN / search)");
            }

            int navInset = navBarInset(decor);
            ViewGroup.LayoutParams raw = bar.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
                params.gravity = Gravity.BOTTOM;
                params.height = barHeight;
                params.bottomMargin = navInset;
                bar.setLayoutParams(params);
            }
            bar.setVisibility(View.VISIBLE);
            applyContentPadding(activity, barHeight, navInset);
        } catch (Throwable t) {
            Log.w(TAG, "bottom bar not attached (Settings stays stock)", t);
        }
    }

    /**
     * Keeps the last row above the bar; called on every attach and delayed pass.  Uses
     * {@code max(current, bar + nav inset)} instead of remembered padding: repeated passes
     * cannot accumulate, and a larger padding applied by Settings itself (IME, cutout) is
     * preserved.
     */
    private static void applyContentPadding(Activity activity, int barHeight, int navInset) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        int needed = barHeight + navInset;
        if (content.getPaddingBottom() < needed) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), needed);
        }
    }

    private static int navBarInset(View decor) {
        try {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                return insets.getSystemWindowInsetBottom();
            }
        } catch (Throwable ignored) {
            // no insets available: the bar sits at the very bottom
        }
        return 0;
    }

    private static void wire(final Activity activity, View bar) {
        View back = bar.findViewWithTag("ztmetro_bottom_back");
        if (back != null) {
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goBack(activity);
                }
            });
        }
        View search = bar.findViewWithTag("ztmetro_bottom_search");
        if (search != null) {
            search.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSearch(activity);
                }
            });
        }
        View label = bar.findViewWithTag("ztmetro_bottom_label");
        if (label != null) {
            label.setClickable(false);
            label.setFocusable(false);
        }
    }

    private static void goBack(Activity activity) {
        try {
            Method getDispatcher = activity.getClass().getMethod("getOnBackPressedDispatcher");
            Object dispatcher = getDispatcher.invoke(activity);
            if (dispatcher != null) {
                dispatcher.getClass().getMethod("onBackPressed").invoke(dispatcher);
                return;
            }
        } catch (Throwable ignored) {
            // not a ComponentActivity or androidx is not on the host classpath
        }
        try {
            activity.onBackPressed();
            return;
        } catch (Throwable ignored) {
            // fall through to finish()
        }
        try {
            activity.finish();
        } catch (Throwable t) {
            Log.d(TAG, "back: nothing to finish");
        }
    }

    private static void openSearch(Activity activity) {
        try {
            // Open the real search results surface (SettingsIntelligence SearchActivity);
            // it is the screen the module restyles.  The old SETTINGS+search intent only
            // opened the home page (which panel chrome is hidden on).
            Intent intent = new Intent(APP_SEARCH_ACTION)
                    .setPackage(INTELLIGENCE_PACKAGE);
            if (intent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(intent);
                Log.i(TAG, "settings search: SettingsIntelligence search surface");
                return;
            }
            intent = new Intent(SETTINGS_ACTION);
            intent.putExtra("search", true);
            if (intent.resolveActivity(activity.getPackageManager()) == null) {
                Log.i(TAG, "settings search: no activity for " + APP_SEARCH_ACTION
                        + " / " + SETTINGS_ACTION + " (no-op)");
                return;
            }
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.i(TAG, "settings search no-op: " + t);
        }
    }
}
