package org.ztmetro.start;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Locale;

/**
 * White, code-drawn WP glyphs for the fixed system packages whose Android
 * adaptive icon has no usable monochrome layer. Paths are authored in a 0..1
 * box and scaled to the tile; no drawable resources and no third-party icon
 * files are added. The set is deliberately small and literal: gear (settings /
 * action centre), camera body, calendar grid, two people, handset, envelope,
 * globe, photo stack, clock face, musical note, and a 2x2 grid for Action
 * Centre.
 */
final class SystemGlyphs {

    static final int NONE = 0;
    static final int GEAR = 1;
    static final int CAMERA = 2;
    static final int CALENDAR = 3;
    static final int CONTACTS = 4;
    static final int HANDSET = 5;
    static final int ENVELOPE = 6;
    static final int GLOBE = 7;
    static final int PHOTOS = 8;
    static final int CLOCK = 9;
    static final int NOTE = 10;
    static final int GRID = 11;

    private static final int COUNT = 12;

    private static final class Glyph {
        Path fill;
        Path stroke;
        float strokeWidth = 0.075f;
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
        STROKE.setColor(0xFFFFFFFF);
    }

    private SystemGlyphs() {
    }

    /** Package-name match. First match wins; keep the tokens specific. */
    static int kindFor(String packageName) {
        if (packageName == null) {
            return NONE;
        }
        String pkg = packageName.toLowerCase(Locale.US);
        if (pkg.equals("org.ztmetro.actioncentre")) {
            return GRID;
        }
        if (pkg.contains("settings")) {
            return GEAR;
        }
        if (pkg.contains("camera") || pkg.contains("snap")) {
            return CAMERA;
        }
        if (pkg.contains("calendar")) {
            return CALENDAR;
        }
        if (pkg.contains("contacts")) {
            return CONTACTS;
        }
        if (pkg.contains("dialer") || pkg.contains("phone")) {
            return HANDSET;
        }
        if (pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms")) {
            return ENVELOPE;
        }
        if (pkg.contains("browser") || pkg.contains("jelly")) {
            return GLOBE;
        }
        if (pkg.contains("gallery")) {
            return PHOTOS;
        }
        if (pkg.contains("clock") || pkg.contains("deskclock")) {
            return CLOCK;
        }
        if (pkg.contains("music") || pkg.contains("eleven")) {
            return NOTE;
        }
        return NONE;
    }

    static void draw(Canvas canvas, int kind, RectF box) {
        if (kind <= NONE || kind >= COUNT || box.width() <= 0f || box.height() <= 0f) {
            return;
        }
        Glyph glyph = glyph(kind);
        if (glyph == null) {
            return;
        }
        canvas.save();
        canvas.translate(box.left, box.top);
        canvas.scale(box.width(), box.height());
        if (glyph.fill != null) {
            canvas.drawPath(glyph.fill, FILL);
        }
        if (glyph.stroke != null) {
            STROKE.setStrokeWidth(glyph.strokeWidth);
            canvas.drawPath(glyph.stroke, STROKE);
        }
        canvas.restore();
    }

    private static Glyph glyph(int kind) {
        Glyph cached = CACHE[kind];
        if (cached != null) {
            return cached;
        }
        Glyph glyph;
        switch (kind) {
            case GEAR:
                glyph = gear();
                break;
            case CAMERA:
                glyph = camera();
                break;
            case CALENDAR:
                glyph = calendar();
                break;
            case CONTACTS:
                glyph = contacts();
                break;
            case HANDSET:
                glyph = handset();
                break;
            case ENVELOPE:
                glyph = envelope();
                break;
            case GLOBE:
                glyph = globe();
                break;
            case PHOTOS:
                glyph = photos();
                break;
            case CLOCK:
                glyph = clock();
                break;
            case NOTE:
                glyph = note();
                break;
            case GRID:
                glyph = grid();
                break;
            default:
                return null;
        }
        CACHE[kind] = glyph;
        return glyph;
    }

    private static Glyph gear() {
        Glyph glyph = new Glyph();
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addCircle(0.50f, 0.50f, 0.40f, Path.Direction.CW);
        path.addCircle(0.50f, 0.50f, 0.15f, Path.Direction.CW);
        for (int i = 0; i < 8; i++) {
            Path tooth = new Path();
            tooth.addRoundRect(new RectF(-0.065f, -0.50f, 0.065f, -0.40f),
                    0.025f, 0.025f, Path.Direction.CW);
            Matrix matrix = new Matrix();
            matrix.setRotate(45f * i);
            matrix.postTranslate(0.50f, 0.50f);
            tooth.transform(matrix);
            path.addPath(tooth);
        }
        glyph.fill = path;
        return glyph;
    }

    private static Glyph camera() {
        Glyph glyph = new Glyph();
        Path path = evenOdd();
        path.addRoundRect(new RectF(0.08f, 0.28f, 0.92f, 0.84f),
                0.08f, 0.08f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.30f, 0.16f, 0.60f, 0.28f),
                0.03f, 0.03f, Path.Direction.CW);
        path.addCircle(0.50f, 0.56f, 0.19f, Path.Direction.CW); // lens cut-out
        path.addCircle(0.79f, 0.37f, 0.035f, Path.Direction.CW); // flash cut-out
        glyph.fill = path;
        return glyph;
    }

    private static Glyph calendar() {
        Glyph glyph = new Glyph();
        Path path = evenOdd();
        path.addRoundRect(new RectF(0.08f, 0.16f, 0.92f, 0.90f),
                0.06f, 0.06f, Path.Direction.CW);
        // The inner rounded rect is the transparent page; the bars below are
        // the white grid, so even-odd gives a frame + grid rather than a blob.
        path.addRoundRect(new RectF(0.15f, 0.30f, 0.85f, 0.84f),
                0.03f, 0.03f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.26f, 0.08f, 0.36f, 0.16f),
                0.02f, 0.02f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.64f, 0.08f, 0.74f, 0.16f),
                0.02f, 0.02f, Path.Direction.CW);
        path.addRect(0.24f, 0.40f, 0.76f, 0.44f, Path.Direction.CW);
        path.addRect(0.24f, 0.56f, 0.76f, 0.60f, Path.Direction.CW);
        path.addRect(0.38f, 0.36f, 0.42f, 0.72f, Path.Direction.CW);
        path.addRect(0.58f, 0.36f, 0.62f, 0.72f, Path.Direction.CW);
        glyph.fill = path;
        return glyph;
    }

    private static Glyph contacts() {
        Glyph glyph = new Glyph();
        Path path = new Path(); // winding union: two overlapping people
        path.addCircle(0.33f, 0.33f, 0.13f, Path.Direction.CW);
        path.addCircle(0.68f, 0.40f, 0.10f, Path.Direction.CW);
        path.addOval(new RectF(0.12f, 0.50f, 0.54f, 0.90f), Path.Direction.CW);
        path.addOval(new RectF(0.48f, 0.54f, 0.84f, 0.90f), Path.Direction.CW);
        glyph.fill = path;
        return glyph;
    }

    private static Glyph handset() {
        Glyph glyph = new Glyph();
        Path stroke = new Path();
        stroke.moveTo(0.20f, 0.22f);
        stroke.cubicTo(0.20f, 0.58f, 0.44f, 0.80f, 0.80f, 0.80f);
        glyph.stroke = stroke;
        glyph.strokeWidth = 0.15f;
        Path fill = new Path();
        fill.addCircle(0.20f, 0.22f, 0.10f, Path.Direction.CW);
        fill.addCircle(0.80f, 0.80f, 0.10f, Path.Direction.CW);
        glyph.fill = fill;
        return glyph;
    }

    private static Glyph envelope() {
        Glyph glyph = new Glyph();
        Path path = evenOdd();
        path.addRoundRect(new RectF(0.08f, 0.26f, 0.92f, 0.82f),
                0.04f, 0.04f, Path.Direction.CW);
        Path flap = new Path();
        flap.moveTo(0.08f, 0.28f);
        flap.lineTo(0.50f, 0.60f);
        flap.lineTo(0.92f, 0.28f);
        flap.close();
        path.addPath(flap); // transparent V = envelope flap
        glyph.fill = path;
        return glyph;
    }

    private static Glyph globe() {
        Glyph glyph = new Glyph();
        Path stroke = new Path();
        stroke.addCircle(0.50f, 0.50f, 0.40f, Path.Direction.CW);
        stroke.addOval(new RectF(0.26f, 0.10f, 0.74f, 0.90f), Path.Direction.CW);
        stroke.moveTo(0.10f, 0.50f);
        stroke.lineTo(0.90f, 0.50f);
        glyph.stroke = stroke;
        glyph.strokeWidth = 0.075f;
        return glyph;
    }

    private static Glyph photos() {
        Glyph glyph = new Glyph();
        Path path = new Path(); // winding union: two offset photo cards
        path.addRoundRect(new RectF(0.18f, 0.16f, 0.88f, 0.76f),
                0.04f, 0.04f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.10f, 0.28f, 0.78f, 0.88f),
                0.04f, 0.04f, Path.Direction.CW);
        glyph.fill = path;
        return glyph;
    }

    private static Glyph clock() {
        Glyph glyph = new Glyph();
        Path stroke = new Path();
        stroke.addCircle(0.50f, 0.50f, 0.40f, Path.Direction.CW);
        stroke.moveTo(0.50f, 0.50f);
        stroke.lineTo(0.50f, 0.27f);
        stroke.moveTo(0.50f, 0.50f);
        stroke.lineTo(0.67f, 0.62f);
        glyph.stroke = stroke;
        glyph.strokeWidth = 0.075f;
        return glyph;
    }

    private static Glyph note() {
        Glyph glyph = new Glyph();
        Path path = new Path(); // winding union: head + stem + flag
        path.addOval(new RectF(0.14f, 0.62f, 0.44f, 0.86f), Path.Direction.CW);
        path.addRect(0.40f, 0.18f, 0.46f, 0.78f, Path.Direction.CW);
        path.addRect(0.46f, 0.18f, 0.78f, 0.24f, Path.Direction.CW);
        path.addRect(0.72f, 0.24f, 0.78f, 0.42f, Path.Direction.CW);
        glyph.fill = path;
        return glyph;
    }

    private static Glyph grid() {
        Glyph glyph = new Glyph();
        Path path = new Path();
        path.addRoundRect(new RectF(0.16f, 0.16f, 0.46f, 0.46f),
                0.03f, 0.03f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.54f, 0.16f, 0.84f, 0.46f),
                0.03f, 0.03f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.16f, 0.54f, 0.46f, 0.84f),
                0.03f, 0.03f, Path.Direction.CW);
        path.addRoundRect(new RectF(0.54f, 0.54f, 0.84f, 0.84f),
                0.03f, 0.03f, Path.Direction.CW);
        glyph.fill = path;
        return glyph;
    }

    private static Path evenOdd() {
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        return path;
    }
}
