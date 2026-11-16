package org.ztmetro.start;

/**
 * The white ztmetro glyph set, authored in a 24 x 24 dp viewport with a 2 dp
 * stroke and filled WP-like shapes.
 *
 * <p>This class deliberately imports no Android types: the launcher binds the
 * commands to {@code android.graphics.Path} ({@link SystemGlyphs}) and the
 * offline proof sheet binds the very same commands to
 * {@code java.awt.geom.Path2D} ({@code launcher/tools/GlyphSheet.java}). One
 * geometry definition therefore serves both the APK and the rendered sheet —
 * the sheet cannot drift from what ships.
 *
 * <p>Fill vs stroke: {@link #fill(int, Sink)} emits the solid part and
 * {@link #stroke(int, Sink)} the 2 dp outline. A glyph may use one or both.
 * Circles/ellipses are cubic approximations (kappa 0.5522848), which is exact
 * enough at 24 dp and keeps the format to a single command set.
 */
public final class GlyphSet {

    public static final int NONE = 0;
    public static final int BROWSER = 1;
    public static final int GALLERY = 2;
    public static final int CALENDAR = 3;
    public static final int CALCULATOR = 4;
    public static final int CAMERA = 5;
    public static final int CONTACTS = 6;
    public static final int MUSIC = 7;
    public static final int SETTINGS = 8;
    public static final int CLOCK = 9;
    public static final int ACTION_CENTRE = 10;
    public static final int PHONE = 11;
    public static final int MESSAGES = 12;
    public static final int MAIL = 13;
    public static final int STORE = 14;
    public static final int FILES = 15;
    public static final int WEATHER = 16;
    public static final int MAPS = 17;
    public static final int NOTES = 18;

    public static final int COUNT = 19;

    /** Design viewport: all coordinates below are in these units. */
    public static final float VIEWPORT = 24f;
    /** The WP stroke weight at 24 dp. */
    public static final float STROKE_WIDTH = 2f;

    private static final float K = 0.5522848f;

    /** Receives the contours; implemented over android Path and Java2D Path2D. */
    public interface Sink {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);

        void close();
    }

    private GlyphSet() {
    }

    /** Even-odd fills punch the transparent holes correctly; the rest wind. */
    public static boolean evenOdd(int kind) {
        switch (kind) {
            case CAMERA:
            case MESSAGES:
            case MAIL:
            case MAPS:
                return true;
            default:
                return false;
        }
    }

    /** Solid part of the glyph; a no-op for outline-only glyphs. */
    public static void fill(int kind, Sink sink) {
        if (sink == null) {
            return;
        }
        switch (kind) {
            case GALLERY:
                galleryFill(sink);
                break;
            case CALENDAR:
                calendarFill(sink);
                break;
            case CALCULATOR:
                calculatorFill(sink);
                break;
            case CAMERA:
                cameraFill(sink);
                break;
            case CONTACTS:
                contactsFill(sink);
                break;
            case MUSIC:
                musicFill(sink);
                break;
            case SETTINGS:
                settingsFill(sink);
                break;
            case CLOCK:
                circle(sink, 12f, 12f, 1.3f, true);
                break;
            case ACTION_CENTRE:
                actionCentreFill(sink);
                break;
            case PHONE:
                phoneFill(sink);
                break;
            case MESSAGES:
                messagesFill(sink);
                break;
            case MAIL:
                mailFill(sink);
                break;
            case FILES:
                filesFill(sink);
                break;
            case WEATHER:
                circle(sink, 12f, 12f, 4.6f, true);
                break;
            case MAPS:
                mapsFill(sink);
                break;
            case NOTES:
                notesFill(sink);
                break;
            default:
                break;
        }
    }

    /** 2 dp outline part of the glyph; a no-op for solid-only glyphs. */
    public static void stroke(int kind, Sink sink) {
        if (sink == null) {
            return;
        }
        switch (kind) {
            case BROWSER:
                browserStroke(sink);
                break;
            case GALLERY:
                roundRect(sink, 3f, 4.5f, 21f, 19.5f, 1.8f);
                break;
            case CALENDAR:
                roundRect(sink, 3.5f, 6f, 20.5f, 20.5f, 1.6f);
                break;
            case CALCULATOR:
                roundRect(sink, 5f, 2.5f, 19f, 21.5f, 1.8f);
                break;
            case CLOCK:
                circle(sink, 12f, 12f, 9f, true);
                line(sink, 12f, 12f, 12f, 6.4f);
                line(sink, 12f, 12f, 16.4f, 14.6f);
                break;
            case STORE:
                roundRect(sink, 3.5f, 8f, 20.5f, 20.5f, 2f);
                storeHandle(sink);
                break;
            case WEATHER:
                weatherRays(sink);
                break;
            case NOTES:
                roundRect(sink, 4.5f, 2.5f, 19.5f, 21.5f, 1.8f);
                break;
            default:
                break;
        }
    }

    /* ---- browser ---------------------------------------------------------- */

    private static void browserStroke(Sink s) {
        circle(s, 12f, 12f, 9f, true);
        ellipse(s, 12f, 12f, 4.4f, 9f, true);
        line(s, 3f, 12f, 21f, 12f);
        line(s, 4f, 8f, 20f, 8f);
        line(s, 4f, 16f, 20f, 16f);
    }

    /* ---- gallery ---------------------------------------------------------- */

    private static void galleryFill(Sink s) {
        // Sun at the upper right, then the solid mountain range.
        circle(s, 16.9f, 8.5f, 1.7f, true);
        s.moveTo(3.6f, 18.4f);
        s.lineTo(9.3f, 10.6f);
        s.lineTo(13.2f, 14.9f);
        s.lineTo(16.2f, 11.7f);
        s.lineTo(20.4f, 16.2f);
        s.lineTo(20.4f, 18.4f);
        s.close();
    }

    /* ---- calendar --------------------------------------------------------- */

    private static void calendarFill(Sink s) {
        // Header bar plus the two binding rings; the page itself is stroked.
        roundRect(s, 3.5f, 6f, 20.5f, 9.8f, 1f);
        roundRect(s, 7.6f, 2.6f, 9.4f, 7.4f, 0.8f);
        roundRect(s, 14.6f, 2.6f, 16.4f, 7.4f, 0.8f);
    }

    /* ---- calculator ------------------------------------------------------- */

    private static void calculatorFill(Sink s) {
        roundRect(s, 6.9f, 5.4f, 18.1f, 7.4f, 0.6f); // display
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float x = 6.9f + col * 4.1f;
                float y = 9.4f + row * 3.4f;
                roundRect(s, x, y, x + 3f, y + 2.2f, 0.6f);
            }
        }
    }

    /* ---- camera ----------------------------------------------------------- */

    private static void cameraFill(Sink s) {
        // No overlap between body and hump: the fill is even-odd, so touching
        // contours stay white while the lens and flash are true holes.
        roundRect(s, 2.5f, 7f, 21.5f, 19.5f, 2.4f);
        roundRect(s, 8f, 4.2f, 16f, 7f, 1.2f);
        circle(s, 12f, 13.2f, 4.2f, true);
        circle(s, 18.6f, 9.7f, 1.1f, true);
    }

    /* ---- contacts --------------------------------------------------------- */

    private static void contactsFill(Sink s) {
        // Winding union: two people, the nearer one larger and to the left.
        circle(s, 8.3f, 8f, 3.2f, true);
        s.moveTo(2.5f, 20.5f);
        s.cubicTo(2.5f, 16.5f, 5.1f, 13.6f, 8.3f, 13.6f);
        s.cubicTo(11.5f, 13.6f, 14.1f, 16.5f, 14.1f, 20.5f);
        s.close();
        circle(s, 16.6f, 9.4f, 2.6f, true);
        s.moveTo(12.2f, 20.5f);
        s.cubicTo(12.2f, 16.8f, 14.2f, 14.2f, 16.6f, 14.2f);
        s.cubicTo(19.2f, 14.2f, 21.5f, 16.8f, 21.5f, 20.5f);
        s.close();
    }

    /* ---- music ------------------------------------------------------------ */

    private static void musicFill(Sink s) {
        ellipse(s, 8.3f, 17.4f, 3f, 2.2f, true);
        ellipse(s, 16.7f, 15.9f, 3f, 2.2f, true);
        s.moveTo(9.9f, 6.2f);
        s.lineTo(11.5f, 6.2f);
        s.lineTo(11.5f, 17.2f);
        s.lineTo(9.9f, 17.2f);
        s.close();
        s.moveTo(18.3f, 4.2f);
        s.lineTo(19.9f, 4.2f);
        s.lineTo(19.9f, 15.7f);
        s.lineTo(18.3f, 15.7f);
        s.close();
        // The beam joins both stems at the top.
        s.moveTo(9.9f, 6.2f);
        s.lineTo(19.9f, 4.2f);
        s.lineTo(19.9f, 7.4f);
        s.lineTo(9.9f, 9.4f);
        s.close();
    }

    /* ---- settings --------------------------------------------------------- */

    private static void settingsFill(Sink s) {
        // Winding union: disk + eight round teeth, then a reversed centre hole.
        circle(s, 12f, 12f, 7.6f, true);
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45.0);
            float cx = 12f + (float) (8.4 * Math.cos(angle));
            float cy = 12f + (float) (8.4 * Math.sin(angle));
            circle(s, cx, cy, 2f, true);
        }
        circle(s, 12f, 12f, 3.5f, false);
    }

    /* ---- action centre ---------------------------------------------------- */

    private static void actionCentreFill(Sink s) {
        roundRect(s, 3f, 3f, 10.8f, 10.8f, 1.4f);
        roundRect(s, 13.2f, 3f, 21f, 10.8f, 1.4f);
        roundRect(s, 3f, 13.2f, 10.8f, 21f, 1.4f);
        roundRect(s, 13.2f, 13.2f, 21f, 21f, 1.4f);
    }

    /* ---- phone ------------------------------------------------------------ */

    private static void phoneFill(Sink s) {
        // A solid WP handset (receiver at 45 degrees), not a thin outline.
        s.moveTo(6.62f, 10.79f);
        s.cubicTo(8.06f, 13.62f, 10.38f, 15.93f, 13.21f, 17.38f);
        s.lineTo(15.41f, 15.18f);
        s.cubicTo(15.68f, 14.91f, 16.08f, 14.82f, 16.43f, 14.94f);
        s.cubicTo(17.55f, 15.31f, 18.76f, 15.51f, 20f, 15.51f);
        s.cubicTo(20.55f, 15.51f, 21f, 15.96f, 21f, 16.51f);
        s.lineTo(21f, 20f);
        s.cubicTo(21f, 20.55f, 20.55f, 21f, 20f, 21f);
        s.cubicTo(10.61f, 21f, 3f, 13.39f, 3f, 4f);
        s.cubicTo(3f, 3.45f, 3.45f, 3f, 4f, 3f);
        s.lineTo(7.5f, 3f);
        s.cubicTo(8.05f, 3f, 8.5f, 3.45f, 8.5f, 4f);
        s.cubicTo(8.5f, 5.25f, 8.7f, 6.45f, 9.07f, 7.57f);
        s.cubicTo(9.18f, 7.92f, 9.1f, 8.31f, 8.82f, 8.59f);
        s.lineTo(6.62f, 10.79f);
        s.close();
    }

    /* ---- messages --------------------------------------------------------- */

    private static void messagesFill(Sink s) {
        // One contour (bubble + tail), then three even-odd dot holes.
        s.moveTo(6f, 3.5f);
        s.lineTo(18f, 3.5f);
        s.cubicTo(19.9f, 3.5f, 21.5f, 5.1f, 21.5f, 7f);
        s.lineTo(21.5f, 13.6f);
        s.cubicTo(21.5f, 15.5f, 19.9f, 17.1f, 18f, 17.1f);
        s.lineTo(12.6f, 17.1f);
        s.lineTo(6.8f, 22f);
        s.lineTo(6.8f, 17.1f);
        s.lineTo(6f, 17.1f);
        s.cubicTo(4.1f, 17.1f, 2.5f, 15.5f, 2.5f, 13.6f);
        s.lineTo(2.5f, 7f);
        s.cubicTo(2.5f, 5.1f, 4.1f, 3.5f, 6f, 3.5f);
        s.close();
        circle(s, 7.6f, 10.3f, 1.15f, true);
        circle(s, 12f, 10.3f, 1.15f, true);
        circle(s, 16.4f, 10.3f, 1.15f, true);
    }

    /* ---- mail ------------------------------------------------------------- */

    private static void mailFill(Sink s) {
        // Body, then an even-odd triangle that reads as the open flap.
        roundRect(s, 2.5f, 5f, 21.5f, 19f, 1.8f);
        s.moveTo(2.5f, 5.8f);
        s.lineTo(12f, 13.3f);
        s.lineTo(21.5f, 5.8f);
        s.close();
    }

    /* ---- store ------------------------------------------------------------ */

    private static void storeHandle(Sink s) {
        s.moveTo(8.3f, 8.5f);
        s.lineTo(8.3f, 7f);
        s.cubicTo(8.3f, 4.8f, 9.9f, 3.2f, 12f, 3.2f);
        s.cubicTo(14.1f, 3.2f, 15.7f, 4.8f, 15.7f, 7f);
        s.lineTo(15.7f, 8.5f);
    }

    /* ---- files ------------------------------------------------------------ */

    private static void filesFill(Sink s) {
        // Folder with a raised tab; one contour so the fill stays solid.
        s.moveTo(3f, 7.3f);
        s.cubicTo(3f, 6.3f, 3.8f, 5.5f, 4.8f, 5.5f);
        s.lineTo(8.8f, 5.5f);
        s.cubicTo(9.3f, 5.5f, 9.8f, 5.7f, 10.1f, 6.1f);
        s.lineTo(11.6f, 8f);
        s.lineTo(19.2f, 8f);
        s.cubicTo(20.2f, 8f, 21f, 8.8f, 21f, 9.8f);
        s.lineTo(21f, 18.5f);
        s.cubicTo(21f, 19.5f, 20.2f, 20.3f, 19.2f, 20.3f);
        s.lineTo(4.8f, 20.3f);
        s.cubicTo(3.8f, 20.3f, 3f, 19.5f, 3f, 18.5f);
        s.close();
    }

    /* ---- weather ---------------------------------------------------------- */

    private static void weatherRays(Sink s) {
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45.0);
            float x1 = 12f + (float) (6.9 * Math.cos(angle));
            float y1 = 12f + (float) (6.9 * Math.sin(angle));
            float x2 = 12f + (float) (9.6 * Math.cos(angle));
            float y2 = 12f + (float) (9.6 * Math.sin(angle));
            line(s, x1, y1, x2, y2);
        }
    }

    /* ---- maps ------------------------------------------------------------- */

    private static void mapsFill(Sink s) {
        s.moveTo(12f, 2.2f);
        s.cubicTo(7.7f, 2.2f, 4.6f, 5.4f, 4.6f, 9.5f);
        s.cubicTo(4.6f, 14.7f, 12f, 21.8f, 12f, 21.8f);
        s.cubicTo(12f, 21.8f, 19.4f, 14.7f, 19.4f, 9.5f);
        s.cubicTo(19.4f, 5.4f, 16.3f, 2.2f, 12f, 2.2f);
        s.close();
        circle(s, 12f, 9.4f, 2.8f, true);
    }

    /* ---- notes ------------------------------------------------------------ */

    private static void notesFill(Sink s) {
        roundRect(s, 7.5f, 7f, 16.5f, 8.7f, 0.5f);
        roundRect(s, 7.5f, 11.2f, 16.5f, 12.9f, 0.5f);
        roundRect(s, 7.5f, 15.4f, 13.8f, 17.1f, 0.5f);
    }

    /* ---- primitive helpers ------------------------------------------------ */

    private static void line(Sink s, float x1, float y1, float x2, float y2) {
        s.moveTo(x1, y1);
        s.lineTo(x2, y2);
    }

    private static void circle(Sink s, float cx, float cy, float r, boolean clockwise) {
        ellipse(s, cx, cy, r, r, clockwise);
    }

    private static void ellipse(Sink s, float cx, float cy, float rx, float ry,
                                boolean clockwise) {
        float kx = rx * K;
        float ky = ry * K;
        if (clockwise) {
            s.moveTo(cx, cy - ry);
            s.cubicTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy);
            s.cubicTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry);
            s.cubicTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy);
            s.cubicTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry);
        } else {
            s.moveTo(cx, cy - ry);
            s.cubicTo(cx - kx, cy - ry, cx - rx, cy - ky, cx - rx, cy);
            s.cubicTo(cx - rx, cy + ky, cx - kx, cy + ry, cx, cy + ry);
            s.cubicTo(cx + kx, cy + ry, cx + rx, cy + ky, cx + rx, cy);
            s.cubicTo(cx + rx, cy - ky, cx + kx, cy - ry, cx, cy - ry);
        }
        s.close();
    }

    private static void roundRect(Sink s, float left, float top, float right,
                                  float bottom, float radius) {
        float k = radius * K;
        s.moveTo(left + radius, top);
        s.lineTo(right - radius, top);
        s.cubicTo(right - radius + k, top, right, top + radius - k,
                right, top + radius);
        s.lineTo(right, bottom - radius);
        s.cubicTo(right, bottom - radius + k, right - radius + k, bottom,
                right - radius, bottom);
        s.lineTo(left + radius, bottom);
        s.cubicTo(left + radius - k, bottom, left, bottom - radius + k,
                left, bottom - radius);
        s.lineTo(left, top + radius);
        s.cubicTo(left, top + radius - k, left + radius - k, top,
                left + radius, top);
        s.close();
    }
}
