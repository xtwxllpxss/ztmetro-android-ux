package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Attaches the ztmetro page header to the two container points documented in
 * {@code repo/design/settings-container-map.md}:
 *
 * <ul>
 *   <li><b>content_frame</b> (all {@code SettingsBaseActivity} screens, incl. SubSettings):
 *       index 0 of the FrameLayout that {@code SettingsBaseActivity.setContentView(int)}
 *       inflates into.  The screen layout is pushed down by the header's measured height
 *       via its FrameLayout top margin; the header does <em>not</em> go into
 *       {@code settings_main_prefs}, so {@code main_content}'s
 *       {@code layout_weight=1} and the fragment's own layout are untouched.</li>
 *   <li><b>homepage_container</b> ({@code SettingsHomepageActivity}): index 0 of the
 *       vertical LinearLayout inside the NestedScrollView, so the header scrolls with the
 *       dashboard exactly like the suggestion/card/content blocks.  Only here is the
 *       status-bar inset added (the home host starts at the top of the screen; base
 *       screens already sit below the toolbar/status bar).</li>
 * </ul>
 *
 * <p>Idempotent: any existing child tagged {@code ztmetro_header} is removed before the new
 * one is inserted, so repeated {@code setContentView()} / recreation never stacks headers.
 * The header is flat black with the 15 sp {@code SETTINGS} caps label and the 58 sp
 * sans-serif-light lowercase page title from the reference frame ({@code ztos-about.png},
 * addressed at 24 dp from the left); it is attached on sub-screens as well as on home.
 */
public final class HeaderBinder {

    static final String TARGET_PACKAGE = "com.android.settings";
    private static final String TAG = "ztmetro-header";

    private static final String CONTENT_FRAME_ID = "content_frame";
    private static final String HOMEPAGE_CONTAINER_ID = "homepage_container";

    private static int sContentFrameId;
    private static int sHomepageContainerId;

    private HeaderBinder() {
    }

    /** After SettingsBaseActivity.setContentView(int). */
    public static void attachBase(Activity activity) throws Exception {
        ViewGroup frame = findContainer(activity, CONTENT_FRAME_ID, true);
        if (frame == null) {
            Log.d(TAG, "content_frame not found on " + activity.getClass().getName());
            return;
        }
        attach(activity, frame, false);
    }

    /** After SettingsHomepageActivity.onCreate(Bundle). */
    public static void attachHome(Activity activity) throws Exception {
        ViewGroup container = findContainer(activity, HOMEPAGE_CONTAINER_ID, false);
        if (container == null) {
            Log.d(TAG, "homepage_container not found on " + activity.getClass().getName());
            return;
        }
        attach(activity, container, true);
    }

    /** Re-binds title + subtitle and re-applies the chrome; called from onResume/setTitle. */
    public static void refresh(Activity activity) {
        try {
            boolean homepage = isHomepage(activity);
            View header = findHeader(activity);
            if (header == null) {
                if (!homepage) {
                    // Not one of the screens this module decorates (a dialog/helper
                    // FragmentActivity in the Settings process): leave it stock.
                    return;
                }
                // The homepage header can be lost to an early attach; onResume/setTitle
                // re-attaches it once instead of leaving the page without chrome.
                attachHome(activity);
                header = findHeader(activity);
                if (header == null) {
                    return;
                }
            }
            Resources moduleResources = HeaderResources.get(activity);
            CharSequence title = HeaderTitles.title(activity);
            if (title.length() == 0 && homepage) {
                // The home activity has no title of its own; the reference still shows a
                // page block, so fall back to the page name instead of an empty title.
                title = "settings";
            }
            HeaderViewFactory.bind(header, title,
                    HeaderTitles.subtitle(activity, moduleResources, title));
            hideStockChrome(activity, homepage);
            applyContentOffsets(activity);
            ScreenStyler.apply(activity, homepage);
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed (header left as-is)", t);
        }
    }

    private static void attach(final Activity activity, ViewGroup parent, boolean homepage)
            throws Exception {
        Resources moduleResources = HeaderResources.get(activity);

        View existing = parent.findViewWithTag(HeaderViewFactory.HEADER_TAG);
        if (existing != null) {
            parent.removeView(existing);
        }

        View header = HeaderViewFactory.create(activity, moduleResources);
        int statusBar = homepage ? statusBarHeightPx(activity) : 0;
        if (statusBar > 0) {
            // Black page block continues behind the status strip on the home page.
            header.setPadding(header.getPaddingLeft(), header.getPaddingTop() + statusBar,
                    header.getPaddingRight(), header.getPaddingBottom());
        }

        ViewGroup.LayoutParams params = HeaderViewFactory.paramsFor(parent);
        if (homepage) {
            compensateContainerTopMargin(parent, params);
        }

        parent.addView(header, 0, params);

        hideStockChrome(activity, homepage);

        CharSequence title = HeaderTitles.title(activity);
        if (title.length() == 0 && homepage) {
            title = "settings";
        }
        HeaderViewFactory.bind(header, title,
                HeaderTitles.subtitle(activity, moduleResources, title));
        // The only two styling passes: pass 1 applies the content offset with the
        // generated height immediately, pass 2 corrects it to the measured height.
        ScreenStyler.apply(activity, homepage);

        Log.i(TAG, "header attached to " + parent.getClass().getSimpleName()
                + " (" + (homepage ? "homepage" : "base") + "), title=\"" + title
                + "\", statusBar=" + statusBar + "px");
    }

    /** True for the Settings home activity; false for SettingsActivity/SubSettings. */
    static boolean isHomepage(Activity activity) {
        return activity != null
                && activity.getClass().getName().toLowerCase(java.util.Locale.ROOT)
                .contains("homepage");
    }

    /**
     * Pushes every sibling of the header down by its measured height.  Only applies to
     * FrameLayout-style hosts (content_frame); the homepage header already sits in the
     * LinearLayout flow.  Safe to call repeatedly.
     */
    static void applyContentOffsets(Activity activity) {
        try {
            View header = findHeader(activity);
            if (header == null || !(header.getParent() instanceof ViewGroup)) {
                return;
            }
            ViewGroup parent = (ViewGroup) header.getParent();
            if (parent instanceof LinearLayout) {
                return;
            }
            int height = header.getHeight();
            if (height <= 0) {
                height = HeaderViewFactory.heightPx(activity, HeaderResources.get(activity));
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child == header) {
                    continue;
                }
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    if (margins.topMargin != height) {
                        margins.topMargin = height;
                        child.setLayoutParams(margins);
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "header offset not applied: " + t);
        }
    }

    /**
     * homepage_container carries {@code layout_marginTop="-8dp"} in
     * {@code settings_homepage_container.xml}; without this the top 8dp of the header would
     * be clipped by the scroll view.
     */
    private static void compensateContainerTopMargin(ViewGroup container,
                                                     ViewGroup.LayoutParams headerParams) {
        ViewGroup.LayoutParams containerParams = container.getLayoutParams();
        if (containerParams instanceof ViewGroup.MarginLayoutParams
                && headerParams instanceof ViewGroup.MarginLayoutParams) {
            int containerTop = ((ViewGroup.MarginLayoutParams) containerParams).topMargin;
            if (containerTop < 0) {
                ((ViewGroup.MarginLayoutParams) headerParams).topMargin = -containerTop;
            }
        }
    }

    /**
     * The stock screen used to show its own toolbar title above the Metro header; the
     * reference has no top bar at all, so the empty stock chrome is hidden (the back
     * action lives in the bottom bar now).  Guard: if the toolbar currently carries an
     * action button ({@code primary_button}/{@code secondary_button}/{@code action_button}
     * — Save/Done flows), the bar is kept (black, title cleared) so that screen stays
     * usable; the bottom bar still provides Back.  On the home page the search app bar is
     * hidden as well.  Never hides a view that contains the fragment host.
     */
    private static void hideStockChrome(Activity activity, boolean homepage) {
        Resources res = activity.getResources();
        View content = activity.findViewById(android.R.id.content);
        try {
            if (homepage) {
                for (String name : new String[]{"app_bar_container", "app_bar", "search_action_bar"}) {
                    hideUnlessContains(activity, res, name, content);
                }
            } else {
                View frame = findViewById(res, activity, CONTENT_FRAME_ID);
                View actionBar = findViewById(res, activity, "action_bar");
                boolean keepToolbar = toolbarHasVisibleActions(activity, res);
                View appBar = findViewById(res, activity, "app_bar");
                if (keepToolbar) {
                    // Save/Done lives in the toolbar: keep it, paint it flat black and
                    // drop only its title (the Metro header is the page title).
                    showUnlessContains(activity, res, "app_bar", frame);
                    showUnlessContains(activity, res, "action_bar", frame);
                    if (actionBar != null) {
                        actionBar.setBackgroundColor(android.graphics.Color.BLACK);
                    }
                } else {
                    hideUnlessContains(activity, res, "app_bar", frame);
                    hideUnlessContains(activity, res, "action_bar", frame);
                }
                if (keepToolbar) {
                    Log.d(TAG, "toolbar kept: visible action button on "
                            + activity.getClass().getSimpleName());
                }
                if (appBar != null && !keepToolbar && frame != null
                        && Views.isAncestor(appBar, frame)) {
                    Log.d(TAG, "not hiding app_bar: it contains content_frame");
                }
            }
            clearStockToolbarTitle(activity);
        } catch (Throwable t) {
            Log.w(TAG, "stock chrome dedupe failed (stock title kept)", t);
        }
    }

    /** True when Settings has shown a toolbar action (Save/Done/Edit) on this screen. */
    private static boolean toolbarHasVisibleActions(Activity activity, Resources res) {
        for (String name : new String[]{"primary_button", "secondary_button",
                "action_button"}) {
            int id = res.getIdentifier(name, "id", TARGET_PACKAGE);
            if (id == 0) {
                continue;
            }
            View view = activity.findViewById(id);
            if (view != null && view.getVisibility() == View.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private static void showUnlessContains(Activity activity, Resources res, String name,
                                           View mustNotContain) {
        int id = res.getIdentifier(name, "id", TARGET_PACKAGE);
        if (id == 0) {
            return;
        }
        View view = activity.findViewById(id);
        if (view == null) {
            return;
        }
        if (mustNotContain != null && Views.isAncestor(view, mustNotContain)) {
            return;
        }
        view.setVisibility(View.VISIBLE);
    }

    private static void hideUnlessContains(Activity activity, Resources res, String name,
                                           View mustNotContain) {
        int id = res.getIdentifier(name, "id", TARGET_PACKAGE);
        if (id == 0) {
            return;
        }
        View view = activity.findViewById(id);
        if (view == null) {
            return;
        }
        if (mustNotContain != null && Views.isAncestor(view, mustNotContain)) {
            Log.d(TAG, "not hiding " + name + ": it contains the content host");
            return;
        }
        view.setVisibility(View.GONE);
    }

    private static View findViewById(Resources res, Activity activity, String name) {
        int id = res.getIdentifier(name, "id", TARGET_PACKAGE);
        return id == 0 ? null : activity.findViewById(id);
    }

    private static void clearStockToolbarTitle(Activity activity) {
        // Settings renders the screen title through a CollapsingToolbarLayout (id
        // collapsing_toolbar) AND a Toolbar (id action_bar); clearing only the Toolbar
        // leaves the visible title. Clear both, reflectively (androidx vs framework).
        for (String name : new String[]{"action_bar", "collapsing_toolbar"}) {
            try {
                int id = activity.getResources().getIdentifier(name, "id", TARGET_PACKAGE);
                if (id == 0) continue;
                View v = activity.findViewById(id);
                if (v == null) continue;
                v.getClass().getMethod("setTitle", CharSequence.class).invoke(v, (Object) null);
                v.getClass().getMethod("setSubtitle", CharSequence.class).invoke(v, (Object) null);
            } catch (Throwable ignored) {
                // not a toolbar / not present: keep stock title
            }
        }
    }

    private static int statusBarHeightPx(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id == 0 ? 0 : activity.getResources().getDimensionPixelSize(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    static View findHeader(Activity activity) {
        try {
            View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            return decor == null ? null : decor.findViewWithTag(HeaderViewFactory.HEADER_TAG);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ViewGroup findContainer(Activity activity, String name,
                                           boolean isContentFrame) {
        int id = isContentFrame
                ? resolveContentFrameId(activity)
                : resolveHomepageContainerId(activity);
        if (id != 0) {
            View view = activity.findViewById(id);
            if (view instanceof ViewGroup) {
                return (ViewGroup) view;
            }
        }
        if (isContentFrame) {
            return null;
        }
        // Fallback for a Settings build that renamed homepage_container: the vertical
        // LinearLayout inside the homepage CoordinatorLayout (the block that scrolls).
        return findHomepageLinearLayout(activity);
    }

    /** First vertical LinearLayout with children inside the homepage scroll host. */
    private static ViewGroup findHomepageLinearLayout(Activity activity) {
        try {
            int root = activity.getResources().getIdentifier(
                    "settings_homepage_container", "id", TARGET_PACKAGE);
            if (root == 0) {
                return null;
            }
            View view = activity.findViewById(root);
            return view instanceof ViewGroup
                    ? firstLinearLayout((ViewGroup) view, 0, false) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ViewGroup firstLinearLayout(ViewGroup group, int depth, boolean inScroll) {
        if (depth > 6) {
            return null;
        }
        boolean scroll = inScroll || group.getClass().getName()
                .toLowerCase(java.util.Locale.ROOT).contains("scrollview");
        if (scroll && group instanceof LinearLayout) {
            LinearLayout linear = (LinearLayout) group;
            if (linear.getOrientation() == LinearLayout.VERTICAL
                    && linear.getChildCount() > 0) {
                return linear;
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup found = firstLinearLayout((ViewGroup) child, depth + 1, scroll);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int resolveContentFrameId(Activity activity) {
        if (sContentFrameId == 0) {
            sContentFrameId = activity.getResources().getIdentifier(
                    CONTENT_FRAME_ID, "id", TARGET_PACKAGE);
        }
        return sContentFrameId;
    }

    private static int resolveHomepageContainerId(Activity activity) {
        if (sHomepageContainerId == 0) {
            sHomepageContainerId = activity.getResources().getIdentifier(
                    HOMEPAGE_CONTAINER_ID, "id", TARGET_PACKAGE);
        }
        return sHomepageContainerId;
    }

    /** Used by the optional host-side harness: attach to a plain FrameLayout. */
    public static View attachForTest(Activity activity, FrameLayout contentFrame)
            throws Exception {
        attach(activity, contentFrame, false);
        return contentFrame.findViewWithTag(HeaderViewFactory.HEADER_TAG);
    }
}
