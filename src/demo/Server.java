package demo;

import com.octo.captcha.service.CaptchaServiceException;
import com.octo.captcha.service.captchastore.FastHashMapCaptchaStore;
import com.octo.captcha.service.image.DefaultManageableImageCaptchaService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import nl.captcha.Captcha;

/**
 * Captcha Lab — mô phỏng luồng captcha của một hệ thống kiểu OpenCPS (Liferay):
 * captcha gắn với SESSION (cookie JSESSIONID), ảnh trả thẳng PNG, đáp án gửi kèm form qua field j_captcha_response.
 *
 *   GET  /o/rest/v2/applicants/simplecaptchanew[?width=&height=]  PNG (SimpleCaptcha), đáp án lưu vào session
 *   GET  /o/rest/v2/applicants/jcaptcha[?width=&height=]          PNG (JCaptcha), captcha id = session id
 *   POST /o/rest/v2/dossiers/{id}/actions                         form-urlencoded hoặc multipart:
 *                                                                 actionCode, payment, assignUsers, actionNote, payload, j_captcha_response
 *   GET  /api/session      trạng thái session hiện tại (DEBUG=1: kèm đáp án)
 *   GET  /api/stats        thống kê
 *   GET  /health           ok
 *
 * Biến môi trường: PORT (8080), DEBUG (0/1), RATE_LIMIT (req/phút/IP cho ảnh + action, mặc định 60),
 *                  TRUST_PROXY (0/1: chỉ bật khi chạy sau reverse proxy), SESSION_MINUTES (30),
 *                  CTF (1/0: bật các level cố tình có lỗi ở /ctf — xem Ctf.java)
 */
public class Server {
    private static final int PORT = Integer.parseInt(env("PORT", "8080"));
    private static final boolean DEBUG = env("DEBUG", "0").equals("1");
    private static final int RATE_LIMIT = Integer.parseInt(env("RATE_LIMIT", "60"));
    private static final boolean TRUST_PROXY = env("TRUST_PROXY", "0").equals("1");
    private static final boolean CTF = env("CTF", "1").equals("1");

    private static final Pattern ACTION_PATH = Pattern.compile("/o/rest/v2/dossiers/(\\d+)/actions");

    // Tên thuộc tính trong session (giống cách OpenCPS lưu "_SIMPLE_CAPTCHA")
    private static final String ATTR_TYPE = "_CAPTCHA_TYPE";
    private static final String ATTR_SIMPLE = "_SIMPLE_CAPTCHA";
    private static final String ATTR_DEBUG_ANSWER = "_DEBUG_ANSWER";

    static final SessionStore SESSIONS = new SessionStore(Long.parseLong(env("SESSION_MINUTES", "30")));

    // JCaptcha: dùng chính session id làm captcha id (OpenCPS: captchaId = request.getSession().getId())
    private static final DefaultManageableImageCaptchaService JCAPTCHA = new DefaultManageableImageCaptchaService(
            new FastHashMapCaptchaStore(), new SiteLikeEngine(), 180, 100000, 75000);

    /** Hồ sơ giả lập: dossierId -> các action đã thực hiện. */
    private static final Map<String, List<String>> DOSSIERS = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> HITS = new ConcurrentHashMap<>();
    private static final AtomicLong GEN_SIMPLE = new AtomicLong(), GEN_J = new AtomicLong(),
            OK = new AtomicLong(), FAIL = new AtomicLong();

    public static void main(String[] args) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
        s.createContext("/", Server::handle);
        s.setExecutor(Executors.newFixedThreadPool(16));
        s.start();
        System.out.printf("Captcha Lab: http://localhost:%d  (DEBUG=%s, RATE_LIMIT=%d req/min/IP, TRUST_PROXY=%s, CTF=%s)%n",
                PORT, DEBUG, RATE_LIMIT, TRUST_PROXY, CTF);
    }

    // ================================================================== routing

    private static void handle(HttpExchange ex) {
        long t0 = System.nanoTime();
        int status;
        try {
            status = route(ex);
        } catch (Exception e) {
            e.printStackTrace();
            status = trySend(ex, 500, "application/json", "{\"error\":\"internal\"}");
        }
        System.out.printf("%s %-15s %-4s %-48s %d %4dms%n",
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), ip(ex),
                ex.getRequestMethod(), ex.getRequestURI(), status, (System.nanoTime() - t0) / 1_000_000);
        ex.close();
    }

    private static int route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String m = ex.getRequestMethod();
        if (m.equals("HEAD")) m = "GET";   // HEAD xử lý như GET, send() bỏ body
        Matcher action = ACTION_PATH.matcher(path);

        if (m.equals("GET") && path.equals("/")) return page(ex, "/web/index.html");
        if (CTF && m.equals("GET") && (path.equals("/ctf") || path.equals("/ctf/"))) return page(ex, "/web/ctf.html");
        if (CTF && path.startsWith("/ctf/")) return Ctf.handle(ex, path, m);   // các level cố tình có lỗi
        if (m.equals("GET") && path.equals("/health")) return send(ex, 200, "text/plain", "ok");
        if (m.equals("GET") && path.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); return 204; }
        if (m.equals("GET") && path.equals("/o/rest/v2/applicants/simplecaptchanew")) return limited(ex) ? 429 : simpleCaptcha(ex);
        if (m.equals("GET") && path.equals("/o/rest/v2/applicants/jcaptcha")) return limited(ex) ? 429 : jCaptcha(ex);
        if (m.equals("POST") && action.matches()) return limited(ex) ? 429 : dossierAction(ex, action.group(1));
        if (m.equals("GET") && path.equals("/api/session")) return sessionInfo(ex);
        if (m.equals("GET") && path.equals("/api/stats")) return stats(ex);
        return send(ex, 404, "application/json", "{\"error\":\"not_found\"}");
    }

    // ================================================================== captcha

    /**
     * SimpleCaptcha: sinh ảnh, lưu đáp án vào session (ghi đè đáp án cũ), trả PNG dạng file đính kèm.
     * Trình duyệt chỉ nhận ảnh + cookie; đáp án ở lại server.
     */
    private static int simpleCaptcha(HttpExchange ex) throws IOException {
        SessionStore.Session s = session(ex, true);
        Captcha c = SimpleCaptchaFactory.create(param(ex, "width", 200, 100, 400), param(ex, "height", 60, 30, 150));
        s.set(ATTR_TYPE, "simple");
        s.set(ATTR_SIMPLE, c.getAnswer());
        GEN_SIMPLE.incrementAndGet();
        return png(ex, c.getImage());
    }

    /**
     * JCaptcha: captcha id = session id. Gọi lại trong cùng session -> JCaptcha sinh captcha mới thay cái cũ.
     */
    private static int jCaptcha(HttpExchange ex) throws IOException {
        SessionStore.Session s = session(ex, true);
        RecordingWordGenerator.LAST.remove();
        BufferedImage img = JCAPTCHA.getImageChallengeForID(s.id());   // lưu (sessionId -> captcha) trong store của JCaptcha
        s.set(ATTR_TYPE, "jcaptcha");
        if (DEBUG) s.set(ATTR_DEBUG_ANSWER, RecordingWordGenerator.LAST.get());
        GEN_J.incrementAndGet();
        return png(ex, img);
    }

    /** Kiểm tra j_captcha_response với đáp án trong session. Luôn xóa captcha sau khi kiểm tra (dùng 1 lần). */
    private static String checkCaptcha(SessionStore.Session s, String response) {
        if (s == null) return "no_session";                          // không có cookie / session hết hạn
        Object type = s.remove(ATTR_TYPE);
        String expected = (String) s.remove(ATTR_SIMPLE);
        s.remove(ATTR_DEBUG_ANSWER);
        if (type == null) return "no_captcha";                        // chưa lấy ảnh, hoặc đã dùng rồi
        if (response == null || response.isBlank()) return "missing";
        if ("simple".equals(type))
            return expected != null && expected.equalsIgnoreCase(response.trim()) ? "ok" : "wrong";
        try {
            return JCAPTCHA.validateResponseForID(s.id(), response.trim()) ? "ok" : "wrong";
        } catch (CaptchaServiceException e) {
            return "no_captcha";
        }
    }

    // ================================================================== dossier action

    /**
     * Giả lập bước "thực hiện hành động trên hồ sơ" (ví dụ đặt lịch hẹn).
     * Server lấy session từ cookie, so j_captcha_response với đáp án trong session, đúng mới xử lý tiếp.
     */
    private static int dossierAction(HttpExchange ex, String dossierId) throws IOException {
        Map<String, String> f = body(ex);
        SessionStore.Session s = session(ex, false);
        String captcha = checkCaptcha(s, f.get("j_captcha_response"));
        if (!captcha.equals("ok")) {
            FAIL.incrementAndGet();
            return send(ex, 403, "application/json",
                    "{\"code\":\"CAPTCHA_INVALID\",\"reason\":\"" + captcha + "\",\"message\":\"Mã xác thực không đúng\"}");
        }
        String actionCode = f.getOrDefault("actionCode", "").trim();
        if (actionCode.isEmpty())
            return send(ex, 400, "application/json", "{\"code\":\"BAD_REQUEST\",\"message\":\"Thiếu actionCode\"}");
        String payload = f.getOrDefault("payload", "");
        DOSSIERS.computeIfAbsent(dossierId, k -> new ArrayList<>()).add(actionCode + " " + payload);
        OK.incrementAndGet();
        return send(ex, 200, "application/json", "{"
                + "\"dossierId\":" + dossierId
                + ",\"actionCode\":\"" + esc(actionCode) + "\""
                + ",\"status\":\"done\""
                + ",\"payload\":\"" + esc(payload) + "\""
                + ",\"actionsOnDossier\":" + DOSSIERS.get(dossierId).size()
                + "}");
    }

    // ================================================================== info

    private static int sessionInfo(HttpExchange ex) throws IOException {
        SessionStore.Session s = session(ex, false);
        if (s == null) return send(ex, 200, "application/json", "{\"session\":false}");
        Object type = s.get(ATTR_TYPE);
        String answer = DEBUG ? (String) ("simple".equals(type) ? s.get(ATTR_SIMPLE) : s.get(ATTR_DEBUG_ANSWER)) : null;
        String masked = s.id().substring(0, 6) + "…" + s.id().substring(s.id().length() - 4);
        return send(ex, 200, "application/json", "{\"session\":true,\"sessionId\":\"" + masked + "\""
                + ",\"captchaType\":" + (type == null ? "null" : "\"" + type + "\"")
                + (DEBUG ? ",\"answer\":" + (answer == null ? "null" : "\"" + answer + "\"") : "")
                + "}");
    }

    private static int stats(HttpExchange ex) throws IOException {
        return send(ex, 200, "application/json", "{"
                + "\"sessions\":" + SESSIONS.size()
                + ",\"generatedSimple\":" + GEN_SIMPLE.get()
                + ",\"generatedJcaptcha\":" + GEN_J.get()
                + ",\"accepted\":" + OK.get()
                + ",\"rejected\":" + FAIL.get()
                + ",\"debug\":" + DEBUG
                + ",\"rateLimitPerMinute\":" + RATE_LIMIT
                + "}");
    }

    private static int page(HttpExchange ex, String resource) throws IOException {
        try (InputStream in = Server.class.getResourceAsStream(resource)) {
            if (in == null) return send(ex, 500, "text/plain", resource + " not found in classpath");
            return send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    // ================================================================== session / cookie

    /** Đọc session từ cookie JSESSIONID; create=true thì tạo mới và Set-Cookie nếu chưa có. */
    static SessionStore.Session session(HttpExchange ex, boolean create) {
        String id = cookie(ex, "JSESSIONID");
        SessionStore.Session s = SESSIONS.find(id);
        if (s == null && create) {
            s = SESSIONS.create();
            boolean https = TRUST_PROXY && "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
            // HttpOnly: JavaScript trên trang không đọc được cookie này
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=" + s.id() + "; Path=/; HttpOnly; SameSite=Lax" + (https ? "; Secure" : ""));
        }
        return s;
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers)
            for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        return null;
    }

    // ================================================================== helpers

    static int png(HttpExchange ex, BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + System.currentTimeMillis() + ".png\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        return send(ex, 200, "image/png", bos.toByteArray());
    }

    private static int param(HttpExchange ex, String name, int def, int min, int max) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return def;
        for (String kv : q.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(name)) {
                try { return Math.max(min, Math.min(max, Integer.parseInt(p[1]))); } catch (NumberFormatException e) { return def; }
            }
        }
        return def;
    }

    private static boolean limited(HttpExchange ex) throws IOException {
        long now = System.currentTimeMillis();
        Deque<Long> q = HITS.computeIfAbsent(ip(ex), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= RATE_LIMIT) {
                ex.getResponseHeaders().set("Retry-After", String.valueOf(1 + (60_000 - (now - q.peekFirst())) / 1000));
                send(ex, 429, "application/json", "{\"error\":\"rate_limited\"}");
                return true;
            }
            q.addLast(now);
        }
        return false;
    }

    /** Đọc body: application/x-www-form-urlencoded hoặc multipart/form-data (chỉ field text). */
    static Map<String, String> body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        String ct = String.valueOf(ex.getRequestHeaders().getFirst("Content-Type"));
        Map<String, String> m = new HashMap<>();
        if (ct.startsWith("multipart/form-data")) {
            Matcher b = Pattern.compile("boundary=\"?([^\";]+)\"?").matcher(ct);
            if (!b.find()) return m;
            String s = new String(raw, StandardCharsets.ISO_8859_1);
            for (String part : s.split("--" + Pattern.quote(b.group(1)))) {
                int sep = part.indexOf("\r\n\r\n");
                if (sep < 0) continue;
                Matcher n = Pattern.compile("name=\"([^\"]*)\"").matcher(part.substring(0, sep));
                if (!n.find()) continue;
                String v = part.substring(sep + 4);
                if (v.endsWith("\r\n")) v = v.substring(0, v.length() - 2);
                m.put(n.group(1), new String(v.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
        } else {
            for (String kv : new String(raw, StandardCharsets.UTF_8).split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                                 URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    /**
     * IP cho rate limit. Chỉ tin X-Forwarded-For khi TRUST_PROXY=1 (app nằm sau proxy);
     * nếu không, client tự gửi IP giả mỗi lần để né rate limit.
     */
    static String ip(HttpExchange ex) {
        if (TRUST_PROXY) {
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    static int send(HttpExchange ex, int code, String type, String body) throws IOException {
        return send(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    static int send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(code, -1);
            return code;
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        return code;
    }

    private static int trySend(HttpExchange ex, int code, String type, String body) {
        try { return send(ex, code, type, body); } catch (IOException ignored) { return code; }
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
