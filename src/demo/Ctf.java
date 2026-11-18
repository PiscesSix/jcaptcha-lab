package demo;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.captcha.Captcha;

/**
 * CTF "bug bounty" cho cơ chế captcha: level 1–8 là bản CỐ TÌNH CÓ LỖI của bước kiểm tra captcha;
 * level 9 kiểm tra đúng nhưng ảnh yếu (OCR phổ thông đọc được).
 * Bản đúng nằm ở Server.checkCaptcha(). Khai thác đúng lỗi -> response trả về flag.
 *
 *   GET  /ctf/levels             danh sách level + trạng thái đã giải (theo session)
 *   GET  /ctf/{n}/captcha        lấy ảnh captcha của level n
 *   POST /ctf/{n}/actions        gửi action (multipart / form) có j_captcha_response
 *   POST /ctf/{n}/reset          xóa trạng thái level n trong session
 *   POST /ctf/flag               nộp flag (field "flag")
 *
 * Mọi thứ chỉ tồn tại trong bộ nhớ của lab. KHÔNG dùng các kỹ thuật này lên hệ thống không được phép.
 */
final class Ctf {
    private Ctf() {}

    record Level(int n, String title, String scenario, String goal, String hint) {}

    static final List<Level> LEVELS = List.of(
        new Level(1, "Token lạ trong response",
            "Dev thêm header để \"trace\" request khi debug rồi quên gỡ.",
            "Đặt lịch thành công 5 lần trong vòng 10 giây.",
            "Xem kỹ toàn bộ response header của request lấy ảnh. Chuỗi trông như token có thật là token?"),
        new Level(2, "Captcha dùng mãi",
            "Server kiểm tra đáp án nhưng quên một bước sau khi kiểm tra.",
            "Đặt lịch thành công 5 lần mà chỉ lấy ảnh captcha đúng 1 lần.",
            "Giải đúng một lần, rồi gửi lại y nguyên request đó."),
        new Level(3, "Field không bắt buộc",
            "Bước kiểm tra chỉ chạy khi request có field captcha.",
            "Đặt lịch thành công mà KHÔNG lấy ảnh captcha lần nào.",
            "Nếu request không có field j_captcha_response thì sao?"),
        new Level(4, "Chuỗi \"null\"",
            "Đã sửa lỗi level 3: thiếu field là bị từ chối. Nhưng đáp án được đọc từ session bằng String.valueOf(...).",
            "Đặt lịch thành công mà KHÔNG lấy ảnh captcha lần nào.",
            "String.valueOf(null) trong Java trả về gì?"),
        new Level(5, "Ảnh đang bảo trì",
            "Endpoint ảnh báo lỗi 503 nhưng vẫn tạo đáp án trong session. Đáp án chỉ gồm 3 chữ số, đoán sai không bị xóa, không có rate limit.",
            "Đặt lịch thành công.",
            "1000 khả năng, không giới hạn số lần thử."),
        new Level(6, "Đáp án dùng chung",
            "Để \"tối ưu\", server lưu đáp án vào một biến chung thay vì theo session.",
            "Đặt lịch thành công bằng một request KHÔNG có session đã lấy ảnh (ví dụ không gửi cookie).",
            "Ai lấy ảnh cũng ghi vào cùng một chỗ. Lấy ảnh ở một nơi, gửi đáp án từ nơi khác."),
        new Level(7, "Race condition",
            "Đã sửa level 2: đúng thì xóa đáp án. Nhưng giữa lúc so đáp án và lúc xóa, server còn ghi hồ sơ mất khoảng 300ms.",
            "Đặt lịch thành công 3 lần mà chỉ lấy ảnh captcha đúng 1 lần.",
            "Gửi nhiều request cùng lúc, đừng chờ request trước trả về."),
        new Level(8, "Kiểm tra bằng cookie",
            "Server không lưu gì: nó gửi md5(đáp án) vào cookie, lúc kiểm tra thì so md5(câu trả lời) với cookie.",
            "Đặt lịch thành công mà KHÔNG lấy ảnh captcha lần nào.",
            "Cookie nằm ở phía ai? Ai được quyền sửa nó?"),
        new Level(9, "Captcha chữ \"sạch\" (Bursztein 2011, Goodfellow 2014)",
            "Người dùng kêu ảnh nhiễu khó đọc, dev đổi sang chữ rõ ràng trên nền trắng. Logic kiểm tra lần này ĐÚNG hoàn toàn: gắn session, dùng 1 lần, không lộ đáp án.",
            "Đặt lịch thành công 10 lần trong vòng 20 giây (người gõ tay không kịp).",
            "Không có lỗi logic nào cả. Thứ yếu là chính cái ảnh: một công cụ OCR có sẵn (tesseract) đọc được nó không?")
    );

    private static final SecureRandom R = new SecureRandom();
    private static final Map<Integer, String> FLAGS = new ConcurrentHashMap<>();
    static {
        for (Level l : LEVELS) {
            byte[] b = new byte[5];
            R.nextBytes(b);
            FLAGS.put(l.n(), "FLAG{captcha-lv" + l.n() + "-" + HexFormat.of().formatHex(b) + "}");
        }
    }

    /** CTF_TEST=1: ảnh kèm header X-Test-Answer để script test tự giải. Không bật khi chơi (lộ đáp án). */
    private static final boolean TEST = "1".equals(System.getenv("CTF_TEST"));

    /** Level 6: đáp án dùng chung cho mọi người (lỗi cố ý). */
    private static volatile String sharedAnswer;

    private static final Pattern PATH = Pattern.compile("/ctf/(\\d+)/(captcha|actions|reset)");

    static int handle(HttpExchange ex, String path, String method) throws IOException {
        if (method.equals("GET") && path.equals("/ctf/levels")) return levels(ex);
        if (method.equals("POST") && path.equals("/ctf/flag")) return submitFlag(ex);
        Matcher m = PATH.matcher(path);
        if (!m.matches()) return notFound(ex);
        int n = Integer.parseInt(m.group(1));
        if (n < 1 || n > LEVELS.size()) return notFound(ex);
        switch (m.group(2)) {
            case "captcha": if (method.equals("GET")) return captcha(ex, n); break;
            case "actions": if (method.equals("POST")) return action(ex, n); break;
            case "reset":   if (method.equals("POST")) return reset(ex, n); break;
        }
        return notFound(ex);
    }

    // ================================================================== lấy ảnh

    private static int captcha(HttpExchange ex, int n) throws IOException {
        SessionStore.Session s = Server.session(ex, true);
        s.set(k(n, "images"), images(s, n) + 1);

        if (n == 5) {                                        // 3 chữ số, ảnh "bảo trì"
            s.set(k(n, "answer"), String.format("%03d", R.nextInt(1000)));
            return Server.send(ex, 503, "application/json",
                    "{\"code\":\"IMAGE_SERVICE_MAINTENANCE\",\"message\":\"Dịch vụ ảnh đang bảo trì\"}");
        }

        if (n == 9) {                                        // chữ sạch: logic đúng, ảnh yếu
            String answer = cleanText();
            s.set(k(n, "answer"), answer);
            if (TEST) ex.getResponseHeaders().set("X-Test-Answer", answer);
            return Server.png(ex, cleanImage(answer));
        }

        Captcha c = SimpleCaptchaFactory.create(200, 60);
        String answer = c.getAnswer();
        if (TEST) ex.getResponseHeaders().set("X-Test-Answer", answer);
        switch (n) {
            case 1 -> {                                      // LỖI: lộ đáp án dưới dạng "token" base64
                s.set(k(n, "answer"), answer);
                ex.getResponseHeaders().set("X-Captcha-Token",
                        Base64.getEncoder().encodeToString(answer.getBytes(StandardCharsets.UTF_8)));
            }
            case 6 -> sharedAnswer = answer;                 // LỖI: không theo session
            case 8 -> ex.getResponseHeaders().add("Set-Cookie",  // LỖI: "lưu" đáp án ở phía client
                    "captcha_hash=" + md5(answer.toLowerCase()) + "; Path=/ctf/8");
            default -> s.set(k(n, "answer"), answer);
        }
        return Server.png(ex, c.getImage());
    }

    // ================================================================== kiểm tra (cố tình sai)

    private static int action(HttpExchange ex, int n) throws IOException {
        Map<String, String> f = Server.body(ex);
        SessionStore.Session s = Server.session(ex, false);
        String resp = f.get("j_captcha_response");
        boolean ok;

        switch (n) {
            case 1, 9 -> ok = checkCorrectly(s, n, resp);    // kiểm tra đúng; lv1 lỗi ở chỗ lộ đáp án, lv9 lỗi ở ảnh

            case 2 -> {                                      // LỖI: so xong không xóa đáp án
                String expected = s == null ? null : (String) s.get(k(n, "answer"));
                ok = expected != null && resp != null && expected.equalsIgnoreCase(resp.trim());
            }
            case 3 -> {                                      // LỖI: chỉ kiểm tra khi có field
                ok = true;
                if (f.containsKey("j_captcha_response")) ok = checkCorrectly(s, n, resp);
            }
            case 4 -> {                                      // LỖI: String.valueOf(null) == "null"
                if (resp == null || resp.isBlank()) { ok = false; break; }
                String expected = String.valueOf(s == null ? null : s.remove(k(n, "answer")));
                ok = expected.equalsIgnoreCase(resp.trim());
            }
            case 5 -> {                                      // LỖI: sai không xóa, không giới hạn số lần thử
                String expected = s == null ? null : (String) s.get(k(n, "answer"));
                ok = expected != null && expected.equals(resp == null ? "" : resp.trim());
                if (ok) s.remove(k(n, "answer"));
            }
            case 6 -> {                                      // LỖI: so với biến dùng chung
                String expected = sharedAnswer;
                ok = expected != null && resp != null && expected.equalsIgnoreCase(resp.trim());
                if (ok) sharedAnswer = null;
            }
            case 7 -> {                                      // LỖI: check-then-act không nguyên tử
                String expected = s == null ? null : (String) s.get(k(n, "answer"));
                ok = expected != null && resp != null && expected.equalsIgnoreCase(resp.trim());
                if (ok) {
                    sleep(300);                              // "ghi hồ sơ vào DB"
                    s.remove(k(n, "answer"));
                } else if (s != null) s.remove(k(n, "answer"));
            }
            case 8 -> {                                      // LỖI: tin dữ liệu client gửi lên
                String hash = Server.cookie(ex, "captcha_hash");
                ok = hash != null && resp != null && hash.equalsIgnoreCase(md5(resp.trim().toLowerCase()));
            }
            default -> ok = false;
        }

        if (!ok) return Server.send(ex, 403, "application/json",
                "{\"code\":\"CAPTCHA_INVALID\",\"message\":\"Mã xác thực không đúng\"}");
        return win(ex, s, n);
    }

    /** Cách kiểm tra đúng (giống Server.checkCaptcha): có session, lấy + xóa đáp án, so không phân biệt hoa thường. */
    private static boolean checkCorrectly(SessionStore.Session s, int n, String resp) {
        if (s == null) return false;
        String expected = (String) s.remove(k(n, "answer"));
        return expected != null && resp != null && !resp.isBlank() && expected.equalsIgnoreCase(resp.trim());
    }

    // ================================================================== điều kiện thắng

    private static int win(HttpExchange ex, SessionStore.Session s, int n) throws IOException {
        long now = System.currentTimeMillis();
        int imgs = s == null ? 0 : images(s, n);
        List<Long> wins = s == null ? new ArrayList<>() : successes(s, n);
        synchronized (wins) { wins.add(now); }

        boolean solved;
        String progress;
        switch (n) {
            case 1 -> {
                long recent;
                synchronized (wins) { recent = wins.stream().filter(t -> now - t <= 10_000).count(); }
                solved = recent >= 5;
                progress = recent + "/5 lần trong 10 giây";
            }
            case 9 -> {
                long recent;
                synchronized (wins) { recent = wins.stream().filter(t -> now - t <= 20_000).count(); }
                solved = recent >= 10;
                progress = recent + "/10 lần trong 20 giây";
            }
            case 2 -> { int c = size(wins); solved = c >= 5 && imgs == 1; progress = c + "/5 lần, đã lấy " + imgs + " ảnh (cần đúng 1)"; }
            case 7 -> { int c = size(wins); solved = c >= 3 && imgs == 1; progress = c + "/3 lần, đã lấy " + imgs + " ảnh (cần đúng 1)"; }
            case 3, 4, 6, 8 -> { solved = imgs == 0; progress = "session này đã lấy " + imgs + " ảnh (cần 0)"; }
            case 5 -> { solved = true; progress = "đoán trúng"; }
            default -> { solved = false; progress = ""; }
        }
        return Server.send(ex, 200, "application/json", "{\"status\":\"done\",\"level\":" + n
                + ",\"progress\":\"" + Server.esc(progress) + "\""
                + (solved ? ",\"flag\":\"" + FLAGS.get(n) + "\"" : "")
                + "}");
    }

    // ================================================================== flag / level / reset

    private static int submitFlag(HttpExchange ex) throws IOException {
        String flag = Server.body(ex).getOrDefault("flag", "").trim();
        for (var e : FLAGS.entrySet())
            if (e.getValue().equals(flag)) {
                SessionStore.Session s = Server.session(ex, true);
                solved(s).add(e.getKey());
                return Server.send(ex, 200, "application/json", "{\"ok\":true,\"level\":" + e.getKey() + "}");
            }
        return Server.send(ex, 200, "application/json", "{\"ok\":false}");
    }

    private static int levels(HttpExchange ex) throws IOException {
        SessionStore.Session s = Server.session(ex, true);
        Set<Integer> done = solved(s);
        StringBuilder sb = new StringBuilder("[");
        for (Level l : LEVELS) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"n\":").append(l.n())
              .append(",\"title\":\"").append(Server.esc(l.title())).append('"')
              .append(",\"scenario\":\"").append(Server.esc(l.scenario())).append('"')
              .append(",\"goal\":\"").append(Server.esc(l.goal())).append('"')
              .append(",\"hint\":\"").append(Server.esc(l.hint())).append('"')
              .append(",\"solved\":").append(done.contains(l.n()))
              .append('}');
        }
        return Server.send(ex, 200, "application/json", sb.append(']').toString());
    }

    private static int reset(HttpExchange ex, int n) throws IOException {
        SessionStore.Session s = Server.session(ex, false);
        if (s != null) for (String key : List.of("answer", "images", "wins")) s.remove(k(n, key));
        if (n == 6) sharedAnswer = null;
        return Server.send(ex, 200, "application/json", "{\"reset\":" + n + "}");
    }

    // ================================================================== level 9: ảnh chữ sạch

    private static final char[] CLEAN_CHARS = "ACDEFHJKLMNPRTWXY347".toCharArray();

    private static String cleanText() {
        char[] c = new char[5];
        for (int i = 0; i < c.length; i++) c[i] = CLEAN_CHARS[R.nextInt(CLEAN_CHARS.length)];
        return new String(c);
    }

    /** Chữ đen, font mono, nền trắng, không nhiễu — kiểu captcha chữ "thân thiện" mà OCR phổ thông cũng đọc được. */
    private static java.awt.image.BufferedImage cleanImage(String text) {
        var img = new java.awt.image.BufferedImage(200, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 200, 60);
        g.setColor(new java.awt.Color(0x22, 0x22, 0x22));
        g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 32));
        int x = 22;
        for (char ch : text.toCharArray()) {
            g.drawString(String.valueOf(ch), x, 42 + R.nextInt(5) - 2);
            x += 32;
        }
        g.dispose();
        return img;
    }

    // ================================================================== helpers

    private static String k(int n, String name) { return "ctf" + n + "." + name; }

    private static int images(SessionStore.Session s, int n) {
        Object v = s.get(k(n, "images"));
        return v == null ? 0 : (Integer) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> successes(SessionStore.Session s, int n) {
        synchronized (s) {
            Object v = s.get(k(n, "wins"));
            if (v == null) { v = new ArrayList<Long>(); s.set(k(n, "wins"), v); }
            return (List<Long>) v;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> solved(SessionStore.Session s) {
        synchronized (s) {
            Object v = s.get("ctf.solved");
            if (v == null) { v = ConcurrentHashMap.newKeySet(); s.set("ctf.solved", v); }
            return (Set<Integer>) v;
        }
    }

    private static int size(List<Long> l) { synchronized (l) { return l.size(); } }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, d));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static int notFound(HttpExchange ex) throws IOException {
        return Server.send(ex, 404, "application/json", "{\"error\":\"not_found\"}");
    }
}
