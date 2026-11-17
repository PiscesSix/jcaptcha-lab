package demo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import com.octo.captcha.component.image.textpaster.Glyphs;
import com.octo.captcha.component.image.textpaster.glyphsdecorator.GlyphsDecorator;

/**
 * Decorator tự viết, cắm vào GlyphsPaster của JCaptcha.
 * GlyphsPaster gọi decorator SAU khi vẽ chữ -> các đường này nằm ĐÈ LÊN chữ.
 * Vẽ chùm đường xám gần nằm ngang dồn ở giữa ảnh, cắt ngang thân chữ.
 */
public class WavyBandDecorator implements GlyphsDecorator {
    private final int lines;
    public WavyBandDecorator(int lines) { this.lines = lines; }

    @Override
    public void decorate(Graphics2D g2, Glyphs glyphs, BufferedImage background) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = background.getWidth(), h = background.getHeight();
        for (int i = 0; i < lines; i++)
            WavyLines.draw(g2, w, h * 0.30, h * 0.62, 4, 165, 190, 1.2f, 1.8f);
    }
}
