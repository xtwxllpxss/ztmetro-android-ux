package org.ztmetro.start;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Locale;

/**
 * White system glyphs for the packages whose Android icon is a brand plate or
 * an unreadable adaptive silhouette.
 *
 * <p>Geometry lives in {@link GlyphSet} (24 dp viewport, 2 dp stroke) so the
 * offline proof sheet renders exactly these paths. This class only adapts that
 * geometry to {@link Path}, scales it into the caller's box and paints white.
 *
 * <p>Package matching is deliberately conservative: a fixed map for known
 * LineageOS/AOSP packages first, then keyword rules <em>only</em> inside
 * system namespaces (com.android., org.lineageos., org.ztmetro., …). A
 * third-party package such as com.google.android.apps.maps must fall through
 * to the colour brand mark, not get a white pin.
 */
final class SystemGlyphs {

    static final int NONE = GlyphSet.NONE;
    static final int BROWSER = GlyphSet.BROWSER;
    static final int GALLERY = GlyphSet.GALLERY;
    static final int CALENDAR = GlyphSet.CALENDAR;
    static final int CALCULATOR = GlyphSet.CALCULATOR;
    static final int CAMERA = GlyphSet.CAMERA;
    static final int CONTACTS = GlyphSet.CONTACTS;
    static final int MUSIC = GlyphSet.MUSIC;
    static final int SETTINGS = GlyphSet.SETTINGS;
    static final int CLOCK = GlyphSet.CLOCK;
    static final int ACTION_CENTRE = GlyphSet.ACTION_CENTRE;
    static final int PHONE = GlyphSet.PHONE;
    static final int MESSAGES = GlyphSet.MESSAGES;
    static final int MAIL = GlyphSet.MAIL;
    static final int STORE = GlyphSet.STORE;
    static final int FILES = GlyphSet.FILES;
    static final int WEATHER = GlyphSet.WEATHER;
    static final int MAPS = GlyphSet.MAPS;
    static final int NOTES = GlyphSet.NOTES;

    private static final int COUNT = GlyphSet.COUNT;

    private static final class Glyph {
        Path fill;
        Path stroke;
    }

    private static final Glyph[] CACHE = new Glyph[COUNT];
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        FILL.setStyle(Paint.Style.FILL);
        FILL.setColor(0xFFFFFFFF);
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
        STROKE.setStrokeWidth(GlyphSet.STROKE_WIDTH);
        STROKE.setColor(0xFFFFFFFF);
    }

    private SystemGlyphs() {
    }

    /**
     * Exact package map, then a system-namespace keyword fallback. Keep the
     * exact entries specific: they are the ones the device actually ships.
     */
    static int kindFor(String packageName) {
        if (packageName == null) {
            return NONE;
        }
        String pkg = packageName.toLowerCase(Locale.US);

        switch (pkg) {
            case "org.lineageos.etar":
            case "com.android.calendar":
                return CALENDAR;
            case "org.lineageos.aperture":
            case "com.android.camera2":
            case "com.android.camera":
                return CAMERA;
            case "org.lineageos.jelly":
            case "com.android.browser":
                return BROWSER;
            case "org.lineageos.twelve":
            case "org.lineageos.eleven":
            case "com.android.music":
                return MUSIC;
            case "org.ztmetro.actioncentre":
            case "org.ztmetro.actioncenter":
            case "org.ztmetro.start":
                return ACTION_CENTRE;
            case "com.android.settings":
                return SETTINGS;
            case "com.android.dialer":
            case "com.android.phone":
                return PHONE;
            case "com.android.messaging":
            case "com.android.mms":
                return MESSAGES;
            case "com.android.gallery3d":
                return GALLERY;
            case "com.android.documentsui":
                return FILES;
            case "com.android.deskclock":
                return CLOCK;
            case "com.android.calculator2":
            case "com.android.calculator3":
                return CALCULATOR;
            case "com.android.contacts":
                return CONTACTS;
            case "com.android.email":
                return MAIL;
            case "com.android.vending":
                return STORE;
            default:
                break;
        }

        if (!isSystemNamespace(pkg)) {
            return NONE;
        }
        if (pkg.contains("actioncentre") || pkg.contains("actioncenter")) {
            return ACTION_CENTRE;
        }
        if (pkg.contains("settings")) {
            return SETTINGS;
        }
        if (pkg.contains("camera") || pkg.contains("aperture")) {
            return CAMERA;
        }
        if (pkg.contains("calendar") || pkg.contains("etar")) {
            return CALENDAR;
        }
        if (pkg.contains("contacts")) {
            return CONTACTS;
        }
        if (pkg.contains("dialer") || pkg.contains("phone")
                || pkg.contains("telecom")) {
            return PHONE;
        }
        if (pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms")) {
            return MESSAGES;
        }
        if (pkg.contains("browser") || pkg.contains("jelly")) {
            return BROWSER;
        }
        if (pkg.contains("gallery") || pkg.contains("photos")) {
            return GALLERY;
        }
        if (pkg.contains("document") || pkg.contains("files")) {
            return FILES;
        }
        if (pkg.contains("music") || pkg.contains("eleven")
                || pkg.contains("twelve")) {
            return MUSIC;
        }
        if (pkg.contains("clock") || pkg.contains("deskclock")) {
            return CLOCK;
        }
        if (pkg.contains("calculator")) {
            return CALCULATOR;
        }
        if (pkg.contains("email") || pkg.contains("mail")) {
            return MAIL;
        }
        if (pkg.contains("store") || pkg.contains("vending")) {
            return STORE;
        }
        if (pkg.contains("weather")) {
            return WEATHER;
        }
        if (pkg.contains("maps")) {
            return MAPS;
        }
        if (pkg.contains("notes")) {
            return NOTES;
        }
        return NONE;
    }

    private static boolean isSystemNamespace(String pkg) {
        return pkg.startsWith("com.android.")
                || pkg.startsWith("org.lineageos.")
                || pkg.startsWith("org.ztmetro.")
                || pkg.startsWith("org.aosp.")
                || pkg.startsWith("com.cyanogenmod.");
    }

    /** Draw the white glyph into {@code box}, keeping the 24 dp aspect square. */
    static void draw(Canvas canvas, int kind, RectF box) {
        if (kind <= NONE || kind >= COUNT || box == null
                || box.width() <= 0f || box.height() <= 0f) {
            return;
        }
        Glyph glyph = glyph(kind);
        if (glyph == null) {
            return;
        }
        float scale = Math.min(box.width(), box.height()) / GlyphSet.VIEWPORT;
        if (scale <= 0f) {
            return;
        }
        float left = box.centerX() - GlyphSet.VIEWPORT * scale / 2f;
        float top = box.centerY() - GlyphSet.VIEWPORT * scale / 2f;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        if (glyph.fill != null) {
            canvas.drawPath(glyph.fill, FILL);
        }
        if (glyph.stroke != null) {
            STROKE.setStrokeWidth(GlyphSet.STROKE_WIDTH);
            canvas.drawPath(glyph.stroke, STROKE);
        }
        canvas.restore();
    }

    private static Glyph glyph(int kind) {
        Glyph cached = CACHE[kind];
        if (cached != null) {
            return cached;
        }
        Path fill = null;
        Path stroke = null;
        PathSink fillSink = new PathSink();
        GlyphSet.fill(kind, fillSink);
        if (fillSink.used) {
            fill = fillSink.path;
            fill.setFillType(GlyphSet.evenOdd(kind)
                    ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        }
        PathSink strokeSink = new PathSink();
        GlyphSet.stroke(kind, strokeSink);
        if (strokeSink.used) {
            stroke = strokeSink.path;
        }
        if (fill == null && stroke == null) {
            return null;
        }
        Glyph glyph = new Glyph();
        glyph.fill = fill;
        glyph.stroke = stroke;
        CACHE[kind] = glyph;
        return glyph;
    }

    /** {@link GlyphSet.Sink} over an android.graphics.Path. */
    private static final class PathSink implements GlyphSet.Sink {
        final Path path = new Path();
        boolean used;

        @Override
        public void moveTo(float x, float y) {
            path.moveTo(x, y);
            used = true;
        }

        @Override
        public void lineTo(float x, float y) {
            path.lineTo(x, y);
            used = true;
        }

        @Override
        public void cubicTo(float c1x, float c1y, float c2x, float c2y,
                            float x, float y) {
            path.cubicTo(c1x, c1y, c2x, c2y, x, y);
            used = true;
        }

        @Override
        public void close() {
            path.close();
            used = true;
        }
    }
}
