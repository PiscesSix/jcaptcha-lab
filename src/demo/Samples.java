package demo;

import java.io.File;
import java.util.UUID;
import javax.imageio.ImageIO;

import com.octo.captcha.service.captchastore.MapCaptchaStore;
import com.octo.captcha.service.image.DefaultManageableImageCaptchaService;

/** java -cp out demo.Samples 6  -> simple_1.png.. (SimpleCaptcha) và jcaptcha_1.png.. (JCaptcha), in kèm đáp án simple */
public class Samples {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        var jc = new DefaultManageableImageCaptchaService(new MapCaptchaStore(), new SiteLikeEngine(), 180, 100000, 75000);
        for (int i = 1; i <= n; i++) {
            var c = SimpleCaptchaFactory.create(200, 60);
            ImageIO.write(c.getImage(), "png", new File("simple_" + i + ".png"));
            ImageIO.write(jc.getImageChallengeForID(UUID.randomUUID().toString()), "png", new File("jcaptcha_" + i + ".png"));
            System.out.println("simple_" + i + ".png = " + c.getAnswer() + "   jcaptcha_" + i + ".png = " + RecordingWordGenerator.LAST.get());
        }
    }
}
