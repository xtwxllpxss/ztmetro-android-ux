package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Attaches the header to the two container points documented in
 * {@code repo/design/settings-container-map.md}:
 *
 * <ul>
 *   <li><b>content_frame</b> (all {@code SettingsBaseActivity} screens, incl. SubSettings):
 *       index 0 of the FrameLayout that {@code SettingsBaseActivity.setContentView(int)}
 *       inflates into.  The screen layout is pushed down by the header height via its
 *       FrameLayout top margin; the header does <em>not</em> go into
 *       {@code settings_main_prefs}, so {@code main_content}'s
 *       {@code layout_weight=1} and the fragment's own layout are untouched.</li>
 *   <li><b>homepage_container</b> ({@code SettingsHomepageActivity}): index 0 of the
 *       vertical LinearLayout inside the NestedScrollView, so the header scrolls with the
 *       dashboard exactly like the suggestion/card/content blocks.</li>
 * </ul>
 *
 * <p>Idempotent: any existing child tagged {@code ztmetro_header} is removed before the new
 * one is inserted, so repeated {@code setContentView()} / recreation never stacks headers.
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

    /** Re-binds title + subtitle; also called from onResume and from setTitle(). */
    public static void refresh(Activity activity) {
        try {
            View header = findHeader(activity);
            if (header == null) {
                return;
            }
            Resources moduleResources = HeaderResources.get(activity);
            CharSequence title = HeaderTitles.title(activity);
            HeaderViewFactory.bind(header, title,
                    HeaderTitles.subtitle(activity, moduleResources, title));
            // Settings re-applies its own title after setContentView (setTitleFromIntent /
            // setTitleFromBackStack); clear the toolbar title again so the Metro header is
            // the only title on screen.
            clearStockToolbarTitle(activity);
            MetroTinter.apply(activity);
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed (header left as-is)", t);
        }
    }

    private static void attach(Activity activity, ViewGroup parent, boolean homepage)
            throws Exception {
        Resources moduleResources = HeaderResources.get(activity);

        View existing = parent.findViewWithTag(HeaderViewFactory.HEADER_TAG);
        if (existing != null) {
            parent.removeView(existing);
        }

        View header = HeaderViewFactory.create(activity, moduleResources);
        int height = HeaderViewFactory.heightPx(activity, moduleResources);

        // Keep the Metro header below the status bar: the activity is edge-to-edge, so the
        // banner would otherwise be painted under the clock/battery icons.
        // Only the homepage host starts at the top of the screen; SettingsBaseActivity
        // screens sit below the toolbar, so adding the status-bar height there leaves a gap.
        int statusBar = homepage ? statusBarHeightPx(activity) : 0;
        if (statusBar > 0) {
            height += statusBar;
            header.setPadding(header.getPaddingLeft(), header.getPaddingTop() + statusBar,
                    header.getPaddingRight(), header.getPaddingBottom());
        }

        ViewGroup.LayoutParams params = HeaderViewFactory.paramsFor(parent, height);

        if (homepage) {
            compensateContainerTopMargin(parent, params);
        }

        parent.addView(header, 0, params);

        if (!homepage) {
            // Push the screen layout below the header.  settings_main_prefs is a
            // match_parent child of content_frame, so raising its top margin shortens it
            // by exactly the header height; its inner main_content keeps layout_weight=1.
            for (int i = 1; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                ViewGroup.LayoutParams childParams = child.getLayoutParams();
                if (childParams instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) childParams).topMargin = height;
                    child.setLayoutParams(childParams);
                }
            }
        }

        dedupeStockChrome(activity, homepage);
        MetroTinter.apply(activity);

        CharSequence title = HeaderTitles.title(activity);
        HeaderViewFactory.bind(header, title,
                HeaderTitles.subtitle(activity, moduleResources, title));
        Log.i(TAG, "header attached to " + parent.getClass().getSimpleName()
                + " (" + (homepage ? "homepage" : "base") + "), title=\"" + title + "\"");
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
     * The stock screen already carries a title (toolbar) and the homepage carries a search
     * app bar; together with the Metro header that is three stacked titles. Keep exactly one:
     * on sub-screens clear the toolbar title (the back arrow stays usable), on the homepage
     * hide the search app bar (WP Start has no search bar; search lives in the app list).
     */
    private static void dedupeStockChrome(Activity activity, boolean homepage) {
        Resources res = activity.getResources();
        try {
            if (homepage) {
                for (String name : new String[]{"app_bar_container", "app_bar", "search_action_bar"}) {
                    int id = res.getIdentifier(name, "id", TARGET_PACKAGE);
                    if (id == 0) continue;
                    View v = activity.findViewById(id);
                    if (v != null) v.setVisibility(View.GONE);
                }
            } else {
                int id = res.getIdentifier("action_bar", "id", TARGET_PACKAGE);
                if (id != 0) {
                    View v = activity.findViewById(id);
                    if (v != null) {
                        // Toolbar may be android.widget.Toolbar or the appcompat one; both
                        // expose setTitle/setSubtitle, so call them reflectively and never
                        // compile against either class.
                        try {
                            v.getClass().getMethod("setTitle", CharSequence.class).invoke(v, (Object) null);
                            v.getClass().getMethod("setSubtitle", CharSequence.class).invoke(v, (Object) null);
                        } catch (Throwable ignored) {
                            // not a toolbar: leave it alone
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dedupe of stock chrome failed (stock title kept)", t);
        }
    }

    private static ViewGroup findContainer(Activity activity, String name,
                                           boolean isContentFrame) {
        int id = isContentFrame
                ? resolveContentFrameId(activity)
                : resolveHomepageContainerId(activity);
        if (id == 0) {
            return null;
        }
        View view = activity.findViewById(id);
        return view instanceof ViewGroup ? (ViewGroup) view : null;
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

    static View findHeader(Activity activity) {
        try {
            View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            return decor == null ? null : decor.findViewWithTag(HeaderViewFactory.HEADER_TAG);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Used by the optional host-side harness: attach to a plain FrameLayout. */
    public static View attachForTest(Activity activity, FrameLayout contentFrame)
            throws Exception {
        attach(activity, contentFrame, false);
        return contentFrame.findViewWithTag(HeaderViewFactory.HEADER_TAG);
    }
}
