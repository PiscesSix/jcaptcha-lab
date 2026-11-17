package demo;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;

import com.octo.captcha.component.image.backgroundgenerator.BackgroundGenerator;

/**
 * Nền tự viết, cắm vào JCaptcha qua interface BackgroundGenerator.
 * Mọi thứ vẽ ở đây nằm DƯỚI chữ.
 *  - 2 đường sin CỐ ĐỊNH (tham số không đổi -> captcha nào cũng có, giống ảnh gốc)
 *  - vết xước, bụi, đường lượn ngẫu nhiên
 */
public class ScratchWavyBackground implements BackgroundGenerator {
    private static final SecureRandom R = new SecureRandom();
    private final int w, h;

    public ScratchWavyBackground(int width, int height) { this.w = width; this.h = height; }

    @Override public int getImageWidth() { return w; }
    @Override public int getImageHeight() { return h; }

    @Override
    public BufferedImage getBackground() {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(244, 244, 242));
        g.fillRect(0, 0, w, h);

        // Vết xước ngắn
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i < 70; i++) {
            int gray = 140 + R.nextInt(60);
            g.setColor(new Color(gray, gray, gray));
            double x = R.nextDouble() * w, y = R.nextDouble() * h;
            double len = 2 + R.nextDouble() * 5, a = R.nextDouble() * Math.PI;
            g.drawLine((int) x, (int) y, (int) (x + len * Math.cos(a)), (int) (y + len * Math.sin(a)));
        }
        // Bụi
        for (int i = 0; i < 150; i++) {
            int gray = 150 + R.nextInt(70);
            img.setRGB(R.nextInt(w), R.nextInt(h), (gray << 16) | (gray << 8) | gray);
        }
        // 2 đường sin cố định, chạy hết chiều ngang
        fixedSine(g, h * 0.27, 4.0, 48.0, 0.0);
        fixedSine(g, h * 0.68, 5.0, 52.0, 1.3);
        // Đường lượn ngẫu nhiên
        for (int i = 0; i < 5; i++)
            WavyLines.draw(g, w, h * 0.1, h * 0.95, 9, 135, 175, 1.2f, 2.0f);
        g.dispose();
        return img;
    }

    private void fixedSine(Graphics2D g, double y0, double amp, double period, double phase) {
        Path2D.Double p = new Path2D.Double();
        for (int x = 0; x <= w; x += 2) {
            double y = y0 + amp * Math.sin(2 * Math.PI * x / period + phase);
            if (x == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        g.setColor(new Color(150, 150, 150));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }
}
