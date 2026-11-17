package demo;

import java.awt.*;
import java.awt.geom.Path2D;
import java.security.SecureRandom;

/** Vẽ đường lượn sóng = tổng 2 sóng sin với biên độ/tần số/pha ngẫu nhiên. */
final class WavyLines {
    private static final SecureRandom R = new SecureRandom();
    private WavyLines() {}

    static void draw(Graphics2D g, int w, double yMin, double yMax,
                     double ampMax, int grayMin, int grayMax, float widthMin, float widthMax) {
        double x0 = -10 + R.nextDouble() * w * 0.4;
        double len = w * (0.5 + R.nextDouble() * 0.7);
        double y0 = yMin + R.nextDouble() * (yMax - yMin);
        double a1 = R.nextDouble() * ampMax, f1 = 0.02 + R.nextDouble() * 0.06, p1 = R.nextDouble() * 6.28;
        double a2 = R.nextDouble() * ampMax / 2, f2 = 0.08 + R.nextDouble() * 0.12, p2 = R.nextDouble() * 6.28;
        double tilt = (R.nextDouble() - 0.5) * 0.15;
        Path2D.Double p = new Path2D.Double();
        for (double x = x0; x <= x0 + len; x += 2) {
            double y = y0 + tilt * (x - x0) + a1 * Math.sin(f1 * x + p1) + a2 * Math.sin(f2 * x + p2);
            if (x == x0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        int gray = grayMin + R.nextInt(grayMax - grayMin + 1);
        g.setColor(new Color(gray, gray, gray));
        g.setStroke(new BasicStroke(widthMin + R.nextFloat() * (widthMax - widthMin),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }
}
