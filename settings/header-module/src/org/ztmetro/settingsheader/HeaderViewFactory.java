package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Inflates and binds the Metro header view.  Contains no Xposed types, so the same class
 * can also be exercised by a plain Android app (see evidence/settings-header/harness).
 */
public final class HeaderViewFactory {

    static final String TAG = "ztmetro-header";

    static final String HEADER_TAG = "ztmetro_header";
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
        int layoutId = moduleResources.getIdentifier(
                "metro_header", "layout", MODULE_PACKAGE);
        if (layoutId == 0) {
            throw new Resources.NotFoundException(
                    "layout metro_header missing from " + MODULE_PACKAGE);
        }

        LayoutInflater inflater = LayoutInflater.from(activity)
                .cloneInContext(ModuleContext.wrap(activity, moduleResources));
        View header = inflater.inflate(layoutId, null, false);
        header.setTag(HEADER_TAG);

        // The toolbar already exposes the screen title; the header is decoration and must
        // not produce a second TalkBack stop.
        header.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return header;
    }

    public static void bind(View header, CharSequence title, CharSequence subtitle) {
        TextView titleView = header.findViewWithTag(TITLE_TAG);
        TextView subtitleView = header.findViewWithTag(SUBTITLE_TAG);

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

    /** Header height from the generated dimens (56dp); never a second hard-coded value. */
    public static int heightPx(Activity activity, Resources moduleResources) {
        int id = moduleResources.getIdentifier(
                "metro_header_height", "dimen", MODULE_PACKAGE);
        if (id == 0) {
            throw new Resources.NotFoundException(
                    "dimen metro_header_height missing from " + MODULE_PACKAGE);
        }
        return moduleResources.getDimensionPixelSize(id);
    }

    /**
     * Layout params for the header.  Only LinearLayout/FrameLayout are expected (the two
     * documented containers); anything else gets plain params instead of a ClassCastException
     * from ViewGroup.addView.
     */
    public static android.view.ViewGroup.LayoutParams paramsFor(
            android.view.ViewGroup parent, int heightPx) {
        if (parent instanceof LinearLayout) {
            return new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, heightPx);
        }
        if (parent instanceof android.widget.FrameLayout) {
            return new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, heightPx);
        }
        return new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
    }
}
