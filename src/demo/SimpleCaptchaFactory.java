package demo;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;

import nl.captcha.Captcha;
import nl.captcha.backgrounds.BackgroundProducer;
import nl.captcha.noise.NoiseProducer;
import nl.captcha.text.producer.TextProducer;
import nl.captcha.text.renderer.WordRenderer;

/**
 * Engine SimpleCaptcha mô phỏng ảnh của endpoint "simplecaptchanew".
 *
 * Khung là của SimpleCaptcha: Captcha.Builder -> addText -> addNoise -> addBackground -> build.
 * Cả 4 khối cắm vào đều tự viết (giống web gốc: ảnh của họ không phải renderer mặc định của thư viện):
 *   TextProducer   sinh đáp án
 *   WordRenderer   vẽ chữ gần như đứng thẳng, mỗi chữ 1 màu / 1 cỡ / lệch cao thấp
 *   NoiseProducer  chùm đường xám dày đè LÊN chữ
 *   Background     nền xám nhạt lấm tấm + vết xước + 2 đường cố định + cung tròn (nằm DƯỚI chữ)
 */
public final class SimpleCaptchaFactory {
    private static final SecureRandom R = new SecureRandom();

    // Không dùng DefaultTextProducer của thư viện: nó có lỗi nextInt(len-1)+1 -> ký tự đầu bộ ký tự không bao giờ ra.
    // Bỏ ký tự dễ nhầm: 0 o O, 1 l i I j, 2 z Z, 5 s S, 6 b, 8 B, 9 g q, u v r.
    private static final char[] CHARS = "acdefhkmnptwxyACDEFGHJKLMNPQRTWXY347".toCharArray();

    private static final Color[] TEXT_COLORS = {
        new Color(0x1a, 0x1a, 0x2e), new Color(0x22, 0x22, 0x55), new Color(0x1f, 0x4a, 0x2a),
        new Color(0x3e, 0x2a, 0x1e), new Color(0x0f, 0x33, 0x40), new Color(0x4a, 0x1e, 0x22) };
    private static final String[] FONTS = { "Arial", "SansSerif", "DejaVu Sans" };

    private SimpleCaptchaFactory() {}

    public static Captcha create(int width, int height) {
        return new Captcha.Builder(width, height)
                .addText(new RandomTextProducer(6), new UprightWordRenderer())
                .addNoise(new ThickBandNoise(4))          // vẽ lên lớp chữ -> nằm trên chữ
                .addBackground(new SpeckleArcBackground()) // lớp riêng -> build() dán chữ đè lên
                .build();
    }

    /** Sinh đáp án bằng SecureRandom. */
    static final class RandomTextProducer implements TextProducer {
        private final int len;
        RandomTextProducer(int len) { this.len = len; }
        @Override public String getText() {
            char[] c = new char[len];
            for (int i = 0; i < len; i++) c[i] = CHARS[R.nextInt(CHARS.length)];
            return new String(c);
        }
    }

    /**
     * Chữ gần như đứng thẳng (±5°), cỡ 28..38, mỗi chữ một màu, baseline lệch cao/thấp.
     * Đo bề rộng thật của từng ký tự (FontMetrics) để xếp sát nhau như ảnh gốc, rồi căn cả cụm vào giữa.
     */
    static final class UprightWordRenderer implements WordRenderer {
        @Override public void render(String word, BufferedImage image) {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = image.getWidth(), h = image.getHeight(), n = word.length();
            Font[] fonts = new Font[n];
            double[] adv = new double[n], gap = new double[n];
            double total = 0;
            for (int i = 0; i < n; i++) {
                // cỡ chữ theo chiều cao ảnh: 60px -> 28..38
                float size = (float) (h * (0.47 + R.nextDouble() * 0.17));
                fonts[i] = new Font(FONTS[R.nextInt(FONTS.length)], Font.BOLD, 1).deriveFont(size);
                adv[i] = g.getFontMetrics(fonts[i]).charWidth(word.charAt(i));
                gap[i] = i == n - 1 ? 0 : -1 + R.nextDouble() * 5;      // khoảng cách -1..4px
                total += adv[i] + gap[i];
            }
            double scale = Math.min(1.0, (w - 16) / total);             // chữ quá rộng thì thu nhỏ cho vừa
            double x = (w - total * scale) / 2 + (R.nextDouble() - 0.5) * Math.max(0, w - 16 - total * scale) * 0.6;
            for (int i = 0; i < n; i++) {
                Font f = fonts[i].deriveFont((float) (fonts[i].getSize2D() * scale));
                g.setFont(f);
                g.setColor(TEXT_COLORS[R.nextInt(TEXT_COLORS.length)]);
                FontMetrics fm = g.getFontMetrics();
                double y = h / 2.0 + fm.getAscent() * 0.38 + (R.nextDouble() - 0.5) * h * 0.22;
                AffineTransform old = g.getTransform();
                g.rotate(Math.toRadians(-5 + R.nextDouble() * 10), x + adv[i] * scale / 2, y);
                g.drawString(String.valueOf(word.charAt(i)), (float) x, (float) y);
                g.setTransform(old);
                x += (adv[i] + gap[i]) * scale;
            }
            g.dispose();
        }
    }

    /** Chùm đường xám dày gần nằm ngang, dồn quanh thân chữ. */
    static final class ThickBandNoise implements NoiseProducer {
        private final int lines;
        ThickBandNoise(int lines) { this.lines = lines; }
        @Override public void makeNoise(BufferedImage image) {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = image.getWidth(), h = image.getHeight();
            for (int i = 0; i < lines; i++)
                WavyLines.draw(g, w, h * 0.30, h * 0.65, 4, 160, 185, 1.6f, 2.4f);
            g.dispose();
        }
    }

    /** Nền: xám nhạt lấm tấm, vết xước, 2 đường cố định (giống nhau ở mọi ảnh), vài cung tròn ngẫu nhiên. */
    static final class SpeckleArcBackground implements BackgroundProducer {
        @Override public BufferedImage addBackground(BufferedImage image) {
            BufferedImage bg = getBackground(image.getWidth(), image.getHeight());
            Graphics2D g = bg.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return bg;
        }

        @Override public BufferedImage getBackground(int w, int h) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(234, 234, 232));
            g.fillRect(0, 0, w, h);
            for (int i = 0; i < 220; i++) {                       // lấm tấm
                int v = 150 + R.nextInt(80);
                img.setRGB(R.nextInt(w), R.nextInt(h), (v << 16) | (v << 8) | v);
            }
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < 45; i++) {                        // vết xước
                int v = 140 + R.nextInt(60);
                g.setColor(new Color(v, v, v));
                double x = R.nextDouble() * w, y = R.nextDouble() * h, len = 3 + R.nextDouble() * 6, a = R.nextDouble() * Math.PI;
                g.drawLine((int) x, (int) y, (int) (x + len * Math.cos(a)), (int) (y + len * Math.sin(a)));
            }
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(150, 150, 150));
            fixedLine(g, w, h * 0.22, 3.0, 70.0, 0.4);             // 2 đường cố định
            fixedLine(g, w, h * 0.60, 4.0, 90.0, 2.1);
            for (int i = 0; i < 3; i++) {                          // cung tròn / vòng
                int v = 140 + R.nextInt(40);
                g.setColor(new Color(v, v, v));
                double d = 18 + R.nextDouble() * 30;
                g.draw(new Arc2D.Double(R.nextDouble() * (w - d), R.nextDouble() * (h - d / 2) - d / 4, d, d * 0.8,
                        R.nextDouble() * 360, 120 + R.nextDouble() * 220, Arc2D.OPEN));
            }
            for (int i = 0; i < 3; i++)                            // vài đường lượn mảnh
                WavyLines.draw(g, w, h * 0.1, h * 0.95, 8, 140, 175, 1.2f, 2.0f);
            g.dispose();
            return img;
        }

        private static void fixedLine(Graphics2D g, int w, double y0, double amp, double period, double phase) {
            Path2D.Double p = new Path2D.Double();
            for (int x = 0; x <= w; x += 2) {
                double y = y0 + amp * Math.sin(2 * Math.PI * x / period + phase) + 0.04 * x;
                if (x == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            g.draw(p);
        }
    }
}
