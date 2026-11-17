package demo;

import java.util.Locale;

import com.octo.captcha.component.word.wordgenerator.WordGenerator;

/**
 * Bọc một WordGenerator của JCaptcha và ghi lại từ vừa sinh vào ThreadLocal.
 * CHỈ để test: service của JCaptcha không trả đáp án ra ngoài, nên muốn test tự động
 * nhánh "nhập đúng" thì phải bắt đáp án ngay lúc sinh. Production không cần class này.
 */
public class RecordingWordGenerator implements WordGenerator {
    static final ThreadLocal<String> LAST = new ThreadLocal<>();
    private final WordGenerator inner;

    public RecordingWordGenerator(WordGenerator inner) { this.inner = inner; }

    @Override
    public String getWord(Integer length) {
        String w = inner.getWord(length);
        LAST.set(w);
        return w;
    }

    @Override
    public String getWord(Integer length, Locale locale) {
        String w = inner.getWord(length, locale);
        LAST.set(w);
        return w;
    }
}
