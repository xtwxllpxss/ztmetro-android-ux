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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v3 icon redraw: turns a full-colour Android launcher icon into the white
 * monochrome silhouette that ztmetro/WP tiles use.
 *
 * The silhouette comes from the icon's alpha channel, exactly as the task
 * requires: an AdaptiveIconDrawable is rendered from its foreground layer (or
 * the whole drawable when the foreground is null), everything else is rendered
 * whole. The alpha mask is then painted white with SRC_IN, so any artwork
 * colour is discarded and only the shape survives. An empty mask, or a
 * near-solid one (an opaque legacy icon), falls back to the app's first letter.
 *
 * Also owns the deterministic per-app tile colour: a weighted pick from the
 * ztmetro C palette with cobalt as the default. The colour is a pure function
 * of the component name, so a tile keeps its colour across reboots.
 */
final class TileArt {

    private static final int CACHE_LIMIT = 96;
    private static final float INK_MIN_FRACTION = 0.008f;
    private static final float INK_MAX_FRACTION = 0.85f;

    private final PackageManager pm;
    private final Paint alphaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final int[] tileColors;

    private final LinkedHashMap<String, Bitmap> cache =
            new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
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
        // This keeps Start cobalt-led like the reference frame but makes a
        // steel or crimson tile very likely on a normal app set.
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

    /** White alpha silhouette at {@code size} px, or null when nothing was drawn. */
    Bitmap glyph(ComponentName component, int size) {
        if (component == null || size <= 0) {
            return null;
        }
        String key = component.flattenToString() + "@" + size;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Bitmap bitmap = buildGlyph(component, size);
        cache.put(key, bitmap);
        return bitmap;
    }

    private Bitmap buildGlyph(ComponentName component, int size) {
        Drawable drawable = null;
        try {
            drawable = pm.getActivityIcon(component);
        } catch (Exception ignored) {
            // fall through to the package icon
        }
        if (drawable == null) {
            try {
                drawable = pm.getApplicationIcon(component.getPackageName());
            } catch (Exception ignored) {
                return null;
            }
        }
        if (drawable == null) {
            return null;
        }

        Bitmap source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        boolean foreground = false;
        if (drawable instanceof AdaptiveIconDrawable) {
            Drawable front = ((AdaptiveIconDrawable) drawable).getForeground();
            if (front != null) {
                drawable = front;
                foreground = true;
            }
        }
        drawable.setBounds(0, 0, size, size);
        if (foreground) {
            // Adaptive foregrounds carry a 1/3 safe-zone padding. Draw them a
            // little larger so the silhouette actually fills the 44% icon box.
            canvas.save();
            canvas.scale(1.34f, 1.34f, size / 2f, size / 2f);
            drawable.draw(canvas);
            canvas.restore();
        } else {
            drawable.draw(canvas);
        }

        Bitmap silhouette = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas mask = new Canvas(silhouette);
        mask.drawBitmap(source, 0f, 0f, alphaPaint);
        mask.drawRect(0f, 0f, size, size, whitePaint);
        source.recycle();

        if (!hasInk(silhouette)) {
            silhouette.recycle();
            return null;
        }
        return silhouette;
    }

    private static boolean hasInk(Bitmap bitmap) {
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
        int sampled = (pixels.length + 1) / 2;
        // An empty mask needs the letter fallback. So does a near-solid mask:
        // an opaque legacy icon would otherwise become a blank white square,
        // which is the same "backing shape" the WP redraw is removing.
        return visible >= 2
                && visible >= sampled * INK_MIN_FRACTION
                && visible <= sampled * INK_MAX_FRACTION;
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
     * ztmetro hues appear deterministically for other packages. No random hue,
     * no Material pastel: the package name always maps to the same tile.
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
}
