import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;

/**
 * Renders the adaptive-icon foreground: the neon guitar pick (same shape as the tuner's pointer)
 * with its beam, and a glowing "NEON TUNER" label. Background is a grey vector drawable.
 *
 * Run from the project root:  java tools/IconGen.java
 */
public class IconGen {
    static final Color GREEN = new Color(0x39, 0xFF, 0x88);
    static final Color CYAN = new Color(0x00, 0xF0, 0xFF);
    static final Color MAGENTA = new Color(0xFF, 0x2B, 0xD6);
    static final Color BODY = new Color(0x10, 0x3A, 0x30);

    public static void main(String[] args) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File("C:/Windows/Fonts/ARIALNB.TTF"));
        BufferedImage master = render(1728, font); // 4x xxxhdpi, downscaled for smooth edges
        String[][] out = {{"mdpi", "108"}, {"hdpi", "162"}, {"xhdpi", "216"}, {"xxhdpi", "324"}, {"xxxhdpi", "432"}};
        for (String[] o : out) {
            int s = Integer.parseInt(o[1]);
            File dir = new File("app/src/main/res/mipmap-" + o[0]);
            dir.mkdirs();
            ImageIO.write(scale(master, s), "png", new File(dir, "ic_launcher_fg.png"));
        }
        // Store-style preview with the grey background, for checking the look.
        BufferedImage preview = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x4A, 0x4A, 0x56), 0, 512, new Color(0x26, 0x26, 0x2E)));
        g.fillRect(0, 0, 512, 512);
        g.drawImage(scale(master, 512), 0, 0, null);
        g.dispose();
        ImageIO.write(preview, "png", new File("docs/screenshots/icon.png"));
        System.out.println("ok");
    }

    /** Canvas = 108dp; the launcher shows roughly the central 72dp, the safe circle is 66dp. */
    static BufferedImage render(int size, Font baseFont) {
        float u = size / 108f; // 1 dp
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        float cx = 54 * u;
        float pw = 27 * u, ph = pw * 1.18f;
        float top = 27.5f * u;
        float tipY = top + ph;
        Shape pick = pickPath(cx, top, pw, ph);

        // --- glow layer (blurred), then crisp layer ---
        BufferedImage glow = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = aa(glow.createGraphics());
        gg.setColor(GREEN);
        gg.setStroke(new BasicStroke(3.2f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        gg.draw(pick);
        gg.setStroke(new BasicStroke(2.4f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        gg.drawLine((int) cx, (int) tipY, (int) cx, (int) (tipY + 8 * u));

        Font font = baseFont.deriveFont(Font.BOLD, 11.5f * u);
        gg.setFont(font);
        FontMetrics fm = gg.getFontMetrics();
        String a = "NEON", b = "TUNER";
        float gap = 2.2f * u;
        float tw = fm.stringWidth(a) + gap + fm.stringWidth(b);
        float maxW = 54 * u;
        if (tw > maxW) {
            font = font.deriveFont(font.getSize2D() * maxW / tw);
            gg.setFont(font);
            fm = gg.getFontMetrics();
            tw = fm.stringWidth(a) + gap + fm.stringWidth(b);
        }
        float tx = cx - tw / 2, ty = 80.5f * u;
        gg.setColor(MAGENTA);
        gg.drawString(a, tx, ty);
        gg.setColor(CYAN);
        gg.drawString(b, tx + fm.stringWidth(a) + gap, ty);
        gg.dispose();
        BufferedImage blurred = blur(glow, Math.round(3.2f * u));

        Graphics2D g = aa(img.createGraphics());
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f));
        g.drawImage(blurred, 0, 0, null);
        g.drawImage(blurred, 0, 0, null); // stronger halo
        g.setComposite(AlphaComposite.SrcOver);

        // pick body + outline
        g.setColor(new Color(BODY.getRed(), BODY.getGreen(), BODY.getBlue(), 235));
        g.fill(pick);
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(2.0f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(pick);
        // highlight on the "celluloid"
        g.setColor(new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke(1.1f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) (cx - pw * 0.24f), (int) (top + ph * 0.22f), (int) (cx - pw * 0.09f), (int) (top + ph * 0.5f));
        // beam from the tip
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(1.5f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) cx, (int) (tipY + 1.5f * u), (int) cx, (int) (tipY + 8 * u));

        // label: white-hot core over the coloured glow
        g.setFont(font);
        g.setColor(blend(MAGENTA, Color.WHITE, 0.35f));
        g.drawString(a, tx, ty);
        g.setColor(blend(CYAN, Color.WHITE, 0.35f));
        g.drawString(b, tx + fm.stringWidth(a) + gap, ty);
        g.dispose();
        return img;
    }

    /** Same curve as TunerView.buildPick(): wide rounded shoulders, tip pointing down. */
    static Shape pickPath(float cx, float top, float w, float h) {
        float hw = w / 2f, sy = top + h * 0.2f;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx - hw, sy);
        p.curveTo(cx - hw * 0.9f, top - h * 0.04f, cx + hw * 0.9f, top - h * 0.04f, cx + hw, sy);
        p.curveTo(cx + hw * 1.1f, top + h * 0.5f, cx + hw * 0.25f, top + h * 0.9f, cx, top + h);
        p.curveTo(cx - hw * 0.25f, top + h * 0.9f, cx - hw * 1.1f, top + h * 0.5f, cx - hw, sy);
        p.closePath();
        return p;
    }

    static Graphics2D aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    static Color blend(Color a, Color b, float t) {
        return new Color(
            Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Separable Gaussian blur. */
    static BufferedImage blur(BufferedImage src, int radius) {
        int n = radius * 2 + 1;
        float[] k = new float[n];
        float sigma = radius / 2.5f, sum = 0;
        for (int i = 0; i < n; i++) {
            float x = i - radius;
            k[i] = (float) Math.exp(-x * x / (2 * sigma * sigma));
            sum += k[i];
        }
        for (int i = 0; i < n; i++) k[i] /= sum;
        BufferedImage tmp = new ConvolveOp(new Kernel(n, 1, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
        return new ConvolveOp(new Kernel(1, n, k), ConvolveOp.EDGE_NO_OP, null).filter(tmp, null);
    }

    static BufferedImage scale(BufferedImage src, int s) {
        BufferedImage cur = src;
        int w = src.getWidth();
        while (w / 2 >= s) { // progressive halving for quality
            w /= 2;
            cur = resize(cur, w);
        }
        return w == s ? cur : resize(cur, s);
    }

    static BufferedImage resize(BufferedImage src, int s) {
        BufferedImage dst = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, s, s, null);
        g.dispose();
        return dst;
    }
}
