package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Inflates and binds the two module views: the page header and the bottom bar.
 * Contains no Xposed types, so the same class can also be exercised by a plain Android app
 * (see evidence/settings-header/harness).
 */
public final class HeaderViewFactory {

    static final String TAG = "ztmetro-header";

    static final String HEADER_TAG = "ztmetro_header";
    static final String LABEL_TAG = "ztmetro_header_label";
    static final String TITLE_TAG = "ztmetro_header_title";
    static final String SUBTITLE_TAG = "ztmetro_header_subtitle";

    private static final String MODULE_PACKAGE = HeaderResources.MODULE_PACKAGE;

    private HeaderViewFactory() {
    }

    /**
     * Inflates the header with the module's resources.
     *
     * @throws Resources.NotFoundException if the module APK does not carry the layout
     *                                     (caught by the hook, header stays absent)
     */
    public static View create(Activity activity, Resources moduleResources) {
        return inflate(activity, moduleResources, "metro_header", HEADER_TAG);
    }

    /** Inflates the 40 dp back/ZRN/search bar (module resources). */
    public static View createBottomBar(Activity activity, Resources moduleResources) {
        return inflate(activity, moduleResources, "metro_bottom_bar", BottomBar.BAR_TAG);
    }

    private static View inflate(Activity activity, Resources moduleResources,
                                String layoutName, String tag) {
        int layoutId = moduleResources.getIdentifier(
                layoutName, "layout", MODULE_PACKAGE);
        if (layoutId == 0) {
            throw new Resources.NotFoundException(
                    "layout " + layoutName + " missing from " + MODULE_PACKAGE);
        }

        LayoutInflater inflater = LayoutInflater.from(activity)
                .cloneInContext(ModuleContext.wrap(activity, moduleResources));
        View view = inflater.inflate(layoutId, null, false);
        view.setTag(tag);
        // A decorative title block must not produce a second TalkBack stop; the stock
        // activity title is hidden by the module, the header text stays readable.
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** Sets the dynamic header title/subtitle; the SETTINGS label is static in the layout. */
    public static void bind(View header, CharSequence title, CharSequence subtitle) {
        TextView labelView = header.findViewWithTag(LABEL_TAG);
        TextView titleView = header.findViewWithTag(TITLE_TAG);
        TextView subtitleView = header.findViewWithTag(SUBTITLE_TAG);

        if (labelView != null && labelView.getText().length() == 0) {
            // static resource failed: fall back to the literal (never crash the header)
            labelView.setText("SETTINGS");
        }
        if (titleView != null) {
            titleView.setText(title == null ? "" : title);
        }
        if (subtitleView != null) {
            if (subtitle == null || subtitle.length() == 0) {
                subtitleView.setVisibility(View.GONE);
            } else {
                subtitleView.setVisibility(View.VISIBLE);
                subtitleView.setText(subtitle);
            }
        }
    }

    /** Header minimum height from the generated dimens (104dp; see gen_header_res.py). */
    public static int heightPx(Activity activity, Resources moduleResources) {
        return dimension(moduleResources, "metro_header_height");
    }

    /** Bottom-bar height from the generated dimens (40dp = tokens nav_bar_height_dp). */
    public static int bottomBarHeightPx(Activity activity, Resources moduleResources) {
        return dimension(moduleResources, "metro_bottom_bar_height");
    }

    private static int dimension(Resources moduleResources, String name) {
        int id = moduleResources.getIdentifier(name, "dimen", MODULE_PACKAGE);
        if (id == 0) {
            throw new Resources.NotFoundException(
                    "dimen " + name + " missing from " + MODULE_PACKAGE);
        }
        return moduleResources.getDimensionPixelSize(id);
    }

    /**
     * Layout params for the header: MATCH_PARENT x WRAP_CONTENT.  The header grows to fit
     * the 48 sp title (and the status-bar inset on the home page) instead of clipping it;
     * its measured height is pushed onto the screen layout as a top margin by
     * {@link HeaderBinder}.
     */
    public static android.view.ViewGroup.LayoutParams paramsFor(
            android.view.ViewGroup parent) {
        if (parent instanceof LinearLayout) {
            return new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        if (parent instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
        }
        return new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
