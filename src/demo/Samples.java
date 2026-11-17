package demo;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.UUID;
import javax.imageio.ImageIO;

import com.octo.captcha.service.captchastore.MapCaptchaStore;
import com.octo.captcha.service.image.DefaultManageableImageCaptchaService;
import com.octo.captcha.service.image.ImageCaptchaService;

/** java -cp out demo.Samples 6  -> sample_1.png ... */
public class Samples {
    public static void main(String[] args) throws Exception {
        ImageCaptchaService service = new DefaultManageableImageCaptchaService(
                new MapCaptchaStore(), new SiteLikeEngine(), 180, 100000, 75000);
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        for (int i = 1; i <= n; i++) {
            String id = UUID.randomUUID().toString();
            BufferedImage img = service.getImageChallengeForID(id);
            ImageIO.write(img, "png", new File("sample_" + i + ".png"));
            System.out.println("sample_" + i + ".png  id=" + id);
        }
    }
}
