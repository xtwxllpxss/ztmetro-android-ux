import org.ztmetro.start.GlyphSet;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Offline proof sheet for the white glyph set.
 *
 * <p>Renders the exact {@link GlyphSet} geometry (the same class the launcher
 * compiles into the APK) at two real sizes: 48 dp and 24 dp, at 2x density
 * (96 px and 48 px), white on black. No Android classes are involved, so this
 * runs with a plain JDK and does not need adb or a device.
 *
 * <pre>
 *   javac -d /tmp/glyphsheet \
 *       launcher/app/src/main/java/org/ztmetro/start/GlyphSet.java \
 *       launcher/tools/GlyphSheet.java
 *   java -cp /tmp/glyphsheet GlyphSheet evidence/icon-set/glyphs.png
 * </pre>
 */
public final class GlyphSheet {

    private static final String[] NAMES = {
            "", "browser", "gallery", "calendar", "calculator", "camera",
            "contacts", "music", "settings", "clock", "action centre",
            "phone", "messages", "mail", "store", "files", "weather", "maps",
            "notes",
    };

    private static final int COLUMNS = 6;
    private static final int PX_PER_DP = 2;

    private GlyphSheet() {
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "evidence/icon-set/glyphs.png");
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }

        int bigCell = 112;
        int bigGlyph = 48 * PX_PER_DP;
        int smallCell = 72;
        int smallGlyph = 24 * PX_PER_DP;
        int margin = 24;
        int width = COLUMNS * bigCell + margin * 2;
        int header = 44;
        int bigRows = 3;
        int smallRows = 3;
        int blockLabel = 24;
        int height = header + bigRows * bigCell + blockLabel
                + header + smallRows * smallCell + margin;

        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);

        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
        Font labelFont = new Font(Font.MONOSPACED, Font.PLAIN, 10);

        int y = margin;
        drawHeader(g, "48 dp tile glyph (2x = 96 px) - white on black", titleFont,
                margin, y + 18);
        y += header;
        y = drawBlock(g, bigCell, bigGlyph, y, margin, labelFont, 10, true);

        y += blockLabel;
        drawHeader(g, "24 dp small-tile glyph (2x = 48 px)", titleFont, margin, y + 18);
        y += header;
        y = drawBlock(g, smallCell, smallGlyph, y, margin, labelFont, 8, false);

        g.dispose();
        ImageIO.write(image, "PNG", out);
        System.out.println("wrote " + out.getPath() + " (" + width + "x" + height
                + ", " + GlyphSet.COUNT + " glyphs, 48 dp + 24 dp)");
    }

    private static void drawHeader(Graphics2D g, String text, Font font,
                                   int x, int y) {
        g.setFont(font);
        g.setColor(new Color(0xFFFFFF));
        g.drawString(text, x, y);
    }

    private static int drawBlock(Graphics2D g, int cell, int glyphPx, int top,
                                 int left, Font labelFont, int labelSize,
                                 boolean withIndex) {
        int rows = (GlyphSet.COUNT - 1 + COLUMNS - 1) / COLUMNS;
        for (int i = 1; i < GlyphSet.COUNT; i++) {
            int index = i - 1;
            int col = index % COLUMNS;
            int row = index / COLUMNS;
            int x = left + col * cell;
            int y = top + row * cell;

            // A barely visible cell frame keeps the sheet readable without
            // changing the black background.
            g.setColor(new Color(0x1A1A1A));
            g.drawRect(x, y, cell - 1, cell - 1);

            g.setColor(Color.WHITE);
            g.translate(x + (cell - glyphPx) / 2f, y + 6f);
            float scale = glyphPx / GlyphSet.VIEWPORT;
            g.scale(scale, scale);

            Path2D.Float fill = path(i, true);
            Path2D.Float stroke = path(i, false);
            if (fill != null) {
                g.fill(fill);
            }
            if (stroke != null) {
                g.setStroke(new BasicStroke(GlyphSet.STROKE_WIDTH,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(stroke);
            }
            g.scale(1f / scale, 1f / scale);
            g.translate(-(x + (cell - glyphPx) / 2f), -(y + 6f));

            g.setFont(labelFont.deriveFont((float) labelSize));
            g.setColor(new Color(0xA0A0A0));
            String label = NAMES[i];
            if (withIndex) {
                label = i + " " + label;
            }
            g.drawString(label, x + 5, y + cell - 4);
        }
        return top + rows * cell;
    }

    private static Path2D.Float path(int kind, boolean fill) {
        P2D sink = new P2D();
        if (fill) {
            GlyphSet.fill(kind, sink);
        } else {
            GlyphSet.stroke(kind, sink);
        }
        if (!sink.used) {
            return null;
        }
        if (fill && GlyphSet.evenOdd(kind)) {
            sink.path.setWindingRule(Path2D.WIND_EVEN_ODD);
        }
        return sink.path;
    }

    /** {@link GlyphSet.Sink} over a Java2D path. */
    private static final class P2D implements GlyphSet.Sink {
        final Path2D.Float path = new Path2D.Float();
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
            path.curveTo(c1x, c1y, c2x, c2y, x, y);
            used = true;
        }

        @Override
        public void close() {
            path.closePath();
            used = true;
        }
    }
}
