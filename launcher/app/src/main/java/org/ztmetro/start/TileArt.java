package org.ztmetro.start;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4 glyph pipeline.
 *
 * The owner's complaint was blobs and dots on system tiles: silhouetting an
 * arbitrary adaptive icon (even a monochrome layer) cannot produce the crisp
 * WP glyphs the reference frame has. The corrected order is:
 *
 *   1. Fixed system package map: our own white 24 dp Path glyph
 *      (SystemGlyphs -> GlyphSet) wins before any icon drawable is consulted.
 *      Browser, Contacts, Camera, Settings, Clock, etc. are always readable,
 *      at 48 dp and on the 22 dp small-tile glyph.
 *   2. Third-party apps: AdaptiveIconDrawable.getMonochrome() (API 33+) if it
 *      renders a non-degenerate silhouette, tinted white.
 *   3. Otherwise the app's original brand mark in COLOUR, drawn in the 44 %
 *      glyph box with no white tint, no white circle and no backdrop.
 *   4. If all of that is empty: the app's first letter in white (the caller
 *      draws it for TYPE_NONE).
 *
 * The per-app tile colour still comes from the ztmetro C palette and is a pure
 * function of the component name.
 */
final class TileArt {

    static final int TYPE_NONE = 0;
    static final int TYPE_WHITE = 1;
    static final int TYPE_COLOR = 2;
    static final int TYPE_SYSTEM = 3;

    static final class Art {
        final int type;
        final Bitmap bitmap;
        final int systemKind;

        Art(int type, Bitmap bitmap, int systemKind) {
            this.type = type;
            this.bitmap = bitmap;
            this.systemKind = systemKind;
        }
    }

    private static final Art NONE = new Art(TYPE_NONE, null, SystemGlyphs.NONE);
    private static final int CACHE_LIMIT = 96;
    private static final float INK_MIN_FRACTION = 0.008f;
    private static final float INK_MAX_FRACTION = 0.55f;

    private final PackageManager pm;
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final int[] tileColors;

    private final LinkedHashMap<String, Art> cache =
            new LinkedHashMap<String, Art>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Art> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    TileArt(Context context) {
        Context app = context.getApplicationContext();
        pm = app.getPackageManager();
        Resources res = app.getResources();
        accent = res.getColor(R.color.metro_accent, null);
        whitePaint.setColor(Color.WHITE);
        whitePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));

        // 26 buckets: cobalt 13 (the ztmetro default, still the most common),
        // steel 2, crimson 2, and one each of the remaining palette colours.
        tileColors = new int[26];
        int at = 0;
        for (int i = 0; i < 13; i++) {
            tileColors[at++] = accent;
        }
        int steel = res.getColor(R.color.metro_tile_steel, null);
        int crimson = res.getColor(R.color.metro_tile_crimson, null);
        tileColors[at++] = steel;
        tileColors[at++] = steel;
        tileColors[at++] = crimson;
        tileColors[at++] = crimson;
        tileColors[at++] = res.getColor(R.color.metro_tile_lime, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_green, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_emerald, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_teal, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_cyan, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_magenta, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_red, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_brown, null);
        tileColors[at++] = res.getColor(R.color.metro_tile_olive, null);
    }

    /** Glyph/colour art at {@code size} px; never null. */
    Art art(ComponentName component, int size) {
        if (component == null || size <= 0) {
            return NONE;
        }
        String key = component.flattenToString() + "@" + size;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Art art = buildArt(component, size);
        cache.put(key, art);
        return art;
    }

    private Art buildArt(ComponentName component, int size) {
        Drawable original = loadDrawable(component);
        if (original == null) {
            return NONE;
        }

        // 1. Known system packages get the authored white glyph FIRST. This is
        //    the fix for the browser sparkle / contacts blob: their adaptive or
        //    monochrome layer is never allowed to win over the WP shape.
        int kind = SystemGlyphs.kindFor(component.getPackageName());
        if (kind != SystemGlyphs.NONE) {
            return new Art(TYPE_SYSTEM, null, kind);
        }

        // 2. Third-party apps may provide a proper monochrome layer. Keep it
        //    white, but only when the silhouette is neither empty, near-solid
        //    nor a tiny corner mark.
        if (Build.VERSION.SDK_INT >= 33 && original instanceof AdaptiveIconDrawable) {
            Drawable monochrome = null;
            try {
                monochrome = Monochrome33.get((AdaptiveIconDrawable) original);
            } catch (Exception ignored) {
                // Broken/vendor adaptive icon; fall through.
            }
            if (monochrome != null) {
                Bitmap white = render(monochrome, size, 1.20f, true, true);
                if (white != null) {
                    return new Art(TYPE_WHITE, white, SystemGlyphs.NONE);
                }
            }
        }

        // 3. Third-party brand mark in COLOUR, no white tint and no circle.
        //    The bitmap fills the callers' 44 % glyph box. For an adaptive icon
        //    only the foreground layer is drawn, so the brand mark appears
        //    instead of a miniature app-tile (background plate) pasted in.
        if (original instanceof AdaptiveIconDrawable) {
            Drawable foreground = ((AdaptiveIconDrawable) original).getForeground();
            if (foreground != null) {
                Bitmap colour = render(foreground, size, 1.34f, false, false);
                if (colour != null) {
                    return new Art(TYPE_COLOR, colour, SystemGlyphs.NONE);
                }
            }
        }
        Bitmap whole = render(original, size, 1.0f, false, false);
        if (whole != null) {
            return new Art(TYPE_COLOR, whole, SystemGlyphs.NONE);
        }
        // 4. TYPE_NONE: the tile/row draws the app's first letter in white.
        return NONE;
    }

    private Drawable loadDrawable(ComponentName component) {
        try {
            Drawable drawable = pm.getActivityIcon(component);
            if (drawable != null) {
                return drawable;
            }
        } catch (Exception ignored) {
            // fall through to the package icon
        }
        try {
            return pm.getApplicationIcon(component.getPackageName());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap render(Drawable drawable, int size, float scale,
                          boolean white, boolean requireUsable) {
        Bitmap source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        drawable.setBounds(0, 0, size, size);
        canvas.save();
        if (scale != 1f) {
            canvas.scale(scale, scale, size / 2f, size / 2f);
        }
        drawable.draw(canvas);
        canvas.restore();

        if (!hasAnyInk(source)) {
            source.recycle();
            return null;
        }
        if (!white) {
            return source;
        }

        Bitmap silhouette = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas mask = new Canvas(silhouette);
        mask.drawBitmap(source, 0f, 0f, null);
        mask.drawRect(0f, 0f, size, size, whitePaint);
        source.recycle();

        if (requireUsable && !hasUsableInk(silhouette)) {
            silhouette.recycle();
            return null;
        }
        return silhouette;
    }

    private static boolean hasAnyInk(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int visible = 0;
        for (int i = 0; i < pixels.length; i += 2) {
            if ((pixels[i] >>> 24) > 24) {
                visible++;
            }
        }
        return visible >= 2;
    }

    /**
     * An empty mask or a near-solid one (a full-bleed legacy icon) is not a
     * usable glyph: the caller should use the system Path glyph, colour mark or
     * letter instead of a blank white square.
     */
    private static boolean hasUsableInk(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int visible = 0;
        int sampled = 0;
        int minX = width;
        int maxX = -1;
        int minY = height;
        int maxY = -1;
        for (int y = 0; y < height; y += 2) {
            int row = y * width;
            for (int x = 0; x < width; x += 2) {
                sampled++;
                if ((pixels[row + x] >>> 24) > 24) {
                    visible++;
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (visible < 2 || sampled == 0
                || visible < sampled * INK_MIN_FRACTION
                || visible > sampled * INK_MAX_FRACTION) {
            return false;
        }
        // A tiny diamond parked in a corner is not a tile glyph. Require the
        // visible mask to occupy a reasonable part of the icon box; if it does
        // not, the caller falls through to the system Path glyph / colour mark.
        float spanX = (maxX - minX) / (float) width;
        float spanY = (maxY - minY) / (float) height;
        return spanX >= 0.30f && spanY >= 0.30f;
    }

    /** First visible character of a label, upper-cased; "?" when there is none. */
    static String firstLetter(String label) {
        if (label == null) {
            return "?";
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "?";
    }

    /**
     * Determine a tile colour from the ztmetro C palette by package key. Cobalt
     * is the default and stays the modal colour; steel/crimson and the remaining
     * ztmetro hues appear deterministically for other packages.
     */
    int colorFor(String key) {
        int bucket = (stableHash(key) & 0x7fffffff) % tileColors.length;
        return tileColors[bucket];
    }

    static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    static int blend(int from, int to, float amount) {
        float t = clamp01(amount);
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    /** FNV-1a over UTF-16 chars; stable across processes, unlike String.hashCode(). */
    static int stableHash(String value) {
        int hash = 0x811c9dc5;
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                hash ^= value.charAt(i);
                hash *= 0x01000193;
            }
        }
        return hash;
    }

    /** API 33+ call isolated so API 29-32 never resolves getMonochrome(). */
    private static final class Monochrome33 {
        static Drawable get(AdaptiveIconDrawable drawable) {
            return drawable.getMonochrome();
        }
    }
}
