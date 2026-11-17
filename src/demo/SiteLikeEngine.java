package demo;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.List;

import com.octo.captcha.component.image.backgroundgenerator.BackgroundGenerator;
import com.octo.captcha.component.image.color.RandomListColorGenerator;
import com.octo.captcha.component.image.fontgenerator.FontGenerator;
import com.octo.captcha.component.image.fontgenerator.RandomFontGenerator;
import com.octo.captcha.component.image.textpaster.GlyphsPaster;
import com.octo.captcha.component.image.textpaster.TextPaster;
import com.octo.captcha.component.image.textpaster.glyphsdecorator.GlyphsDecorator;
import com.octo.captcha.component.image.textpaster.glyphsvisitor.GlyphsVisitors;
import com.octo.captcha.component.image.textpaster.glyphsvisitor.HorizontalSpaceGlyphsVisitor;
import com.octo.captcha.component.image.textpaster.glyphsvisitor.RotateGlyphsRandomVisitor;
import com.octo.captcha.component.image.textpaster.glyphsvisitor.TranslateAllToRandomPointVisitor;
import com.octo.captcha.component.image.textpaster.glyphsvisitor.TranslateGlyphsVerticalRandomVisitor;
import com.octo.captcha.component.image.deformation.ImageDeformation;
import com.octo.captcha.component.image.deformation.ImageDeformationByBufferedImageOp;
import com.octo.captcha.component.image.wordtoimage.DeformedComposedWordToImage;
import com.octo.captcha.component.image.wordtoimage.WordToImage;
import com.octo.captcha.component.word.wordgenerator.RandomWordGenerator;
import com.octo.captcha.component.word.wordgenerator.WordGenerator;
import com.octo.captcha.engine.image.ListImageCaptchaEngine;
import com.octo.captcha.image.gimpy.GimpyFactory;

/**
 * Engine JCaptcha tự lắp, mô phỏng captcha 200x60 của web kia.
 * Khung + phần lớn component là của JCaptcha 2.0; nền và decorator đường nhiễu là tự viết.
 */
public class SiteLikeEngine extends ListImageCaptchaEngine {

    @Override
    protected void buildInitialFactories() {
        // 1. Sinh đáp án: 6 ký tự ngẫu nhiên.
        //    Bỏ các ký tự dễ nhầm khi bị xoay/đè nhiễu: 0 O o, 1 l I i j, 2 z Z, 5 S(chỉ giữ s), 6 b?, 8 B?,
        //    9 g q, u v (lẫn nhau và lẫn y), r (rn trông như m).
        //    Check không phân biệt hoa thường nên giữ cặp hoa/thường cùng một chữ (c/C, k/K...).
        //    (bọc trong RecordingWordGenerator để chế độ DEBUG đọc được đáp án khi test)
        WordGenerator words = new RecordingWordGenerator(new RandomWordGenerator(
                "abcdefhkmnpstwxyACDEFGHJKLMNPQRTWXY347"));

        // 2. Font: cỡ 26..32, sans in đậm
        FontGenerator fonts = new RandomFontGenerator(26, 32, new Font[] {
                new Font("Arial", Font.BOLD, 34),
                new Font("SansSerif", Font.BOLD, 34),
                new Font("DejaVu Sans", Font.BOLD, 34) }, false);

        // 3. Màu chữ: đen, navy, nâu đỏ, xanh rêu, olive, teal — mỗi ký tự 1 màu
        RandomListColorGenerator textColors = new RandomListColorGenerator(new Color[] {
                new Color(0x1a, 0x1a, 0x22), new Color(0x22, 0x22, 0x48), new Color(0x4a, 0x1e, 0x22),
                new Color(0x1f, 0x48, 0x3a), new Color(0x3a, 0x5a, 0x1c), new Color(0x0f, 0x33, 0x40) });

        // 4. Dán chữ: visitor = biến đổi vị trí/góc từng ký tự, decorator = vẽ đè sau khi dán
        TextPaster paster = new GlyphsPaster(6, 6, textColors, Boolean.TRUE,
                new GlyphsVisitors[] {
                        new TranslateGlyphsVerticalRandomVisitor(3),     // lệch lên/xuống ±3px
                        new RotateGlyphsRandomVisitor(Math.PI / 24),    // xoay ±7.5° (xoay nhiều thì z, N, u... dễ thành chữ khác)
                        new HorizontalSpaceGlyphsVisitor(4),            // cách nhau 4px
                        new TranslateAllToRandomPointVisitor(12, 12) }, // đặt cả chữ vào vị trí ngẫu nhiên, chừa lề 12px để không bị cắt mép
                new GlyphsDecorator[] {
                        new WavyBandDecorator(4) });                   // tự viết: chùm đường đè lên chữ (4 đường nhạt, đủ rối mà người vẫn đọc được)

        // 5. Nền tự viết 200x60: xước + 2 đường sin cố định + đường lượn (nằm dưới chữ)
        BackgroundGenerator background = new ScratchWavyBackground(200, 60);

        // 6. Biến dạng sau cùng: làm mờ nhẹ 3x3 (ảnh gốc hơi nhòe)
        float a = 1f / 16f;
        List<ImageDeformation> finalDef = new ArrayList<ImageDeformation>();
        finalDef.add(new ImageDeformationByBufferedImageOp(new ConvolveOp(
                new Kernel(3, 3, new float[] { a, 2 * a, a, 2 * a, 4 * a, 2 * a, a, 2 * a, a }),
                ConvolveOp.EDGE_NO_OP, null)));

        // 7. Ghép: font (random theo từng ký tự) + nền + paster + biến dạng -> ảnh
        WordToImage wordToImage = new DeformedComposedWordToImage(true, fonts, background, paster,
                new ArrayList<ImageDeformation>(),   // biến dạng nền: không
                new ArrayList<ImageDeformation>(),   // biến dạng chữ: không
                finalDef);

        // 8. Factory: đáp án + cách vẽ; false = không phân biệt hoa thường khi check
        addFactory(new GimpyFactory(words, wordToImage, false));
    }
}
