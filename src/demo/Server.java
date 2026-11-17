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
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * JCaptcha Lab — server test captcha, mô phỏng web gốc.
 *
 *   GET  /                                     trang lab
 *   GET  /rest/v2/applicants/jcaptcha          -> {"id":"<uuid>"}
 *   GET  /<uuid>                               -> image/png
 *   POST /rest/v2/applicants/jcaptcha/verify   form (id, answer) hoặc JSON {"id","answer"}
 *                                              -> {"ok":bool,"reason":"correct|wrong|unknown_id|missing_fields"}
 *   GET  /api/stats                            thống kê của JCaptcha service
 *   GET  /api/debug/answer/<uuid>              đáp án (CHỈ khi DEBUG=1)
 *   GET  /health                               -> ok
 *
 * Biến môi trường: PORT (8080), DEBUG (0/1), RATE_LIMIT (số request / phút / IP cho ảnh + verify, mặc định 60),
 *                  TRUST_PROXY (0/1: chỉ bật khi chạy sau reverse proxy — Codespaces, Render, nginx...)
 */
public class Server {
    private static final int PORT = Integer.parseInt(env("PORT", "8080"));
    private static final boolean DEBUG = env("DEBUG", "0").equals("1");
    private static final int RATE_LIMIT = Integer.parseInt(env("RATE_LIMIT", "60"));
    private static final boolean TRUST_PROXY = env("TRUST_PROXY", "0").equals("1");

    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern IMAGE_PATH = Pattern.compile("/(" + UUID_RE + ")");
    private static final Pattern DEBUG_PATH = Pattern.compile("/api/debug/answer/(" + UUID_RE + ")");

    // Service của JCaptcha: lưu id -> captcha, tự dọn captcha cũ.
    //   FastHashMapCaptchaStore: store an toàn khi nhiều luồng cùng truy cập
    //   180    = giữ captcha ít nhất 180 giây
    //   100000 = tối đa 100k captcha trong store
    //   75000  = store vượt 75k thì dọn các captcha quá hạn
    private static final DefaultManageableImageCaptchaService CAPTCHA = new DefaultManageableImageCaptchaService(
            new FastHashMapCaptchaStore(), new SiteLikeEngine(), 180, 100000, 75000);

    /** Chỉ dùng khi DEBUG=1: id -> đáp án, để test tự động nhánh "nhập đúng". */
    private static final Map<String, String> DEBUG_ANSWERS = new ConcurrentHashMap<>();

    /** Rate limit đơn giản: IP -> thời điểm các request trong 60 giây gần nhất. */
    private static final Map<String, Deque<Long>> HITS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
        s.createContext("/", Server::handle);
        s.setExecutor(Executors.newFixedThreadPool(16));
        s.start();
        System.out.printf("JCaptcha Lab: http://localhost:%d  (DEBUG=%s, RATE_LIMIT=%d req/min/IP, TRUST_PROXY=%s)%n", PORT, DEBUG, RATE_LIMIT, TRUST_PROXY);
    }

    // ---------------------------------------------------------------- routing

    private static void handle(HttpExchange ex) {
        long t0 = System.nanoTime();
        int status;
        try {
            status = route(ex);
        } catch (Exception e) {
            e.printStackTrace();
            status = trySend(ex, 500, "application/json", "{\"error\":\"internal\"}");
        }
        System.out.printf("%s %-15s %-4s %-50s %d %4dms%n",
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), ip(ex),
                ex.getRequestMethod(), ex.getRequestURI(), status, (System.nanoTime() - t0) / 1_000_000);
        ex.close();
    }

    private static int route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String m = ex.getRequestMethod();
        if (m.equals("HEAD")) m = "GET";   // HEAD xử lý như GET, send() bỏ phần body (Render/uptime checker hay gửi HEAD)
        Matcher img = IMAGE_PATH.matcher(path);
        Matcher dbg = DEBUG_PATH.matcher(path);

        if (m.equals("GET") && path.equals("/")) return page(ex);
        if (m.equals("GET") && path.equals("/health")) return send(ex, 200, "text/plain", "ok");
        if (m.equals("GET") && path.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); return 204; }
        if (m.equals("GET") && path.equals("/rest/v2/applicants/jcaptcha")) return newId(ex);
        if (m.equals("POST") && path.equals("/rest/v2/applicants/jcaptcha/verify")) return limited(ex) ? 429 : verify(ex);
        if (m.equals("GET") && path.equals("/api/stats")) return stats(ex);
        if (m.equals("GET") && dbg.matches()) return debugAnswer(ex, dbg.group(1));
        if (m.equals("GET") && img.matches()) return limited(ex) ? 429 : image(ex, img.group(1));
        return send(ex, 404, "application/json", "{\"error\":\"not_found\"}");
    }

    // ---------------------------------------------------------------- handlers

    /** Bước 1: cấp id. Chưa sinh captcha — JCaptcha chỉ sinh khi ảnh được gọi lần đầu. */
    private static int newId(HttpExchange ex) throws IOException {
        return send(ex, 200, "application/json", "{\"id\":\"" + UUID.randomUUID() + "\"}");
    }

    /**
     * Bước 2: trả ảnh.
     * Lần đầu gọi với id -> engine sinh captcha mới, lưu (id -> captcha) vào store.
     * Gọi lại cùng id    -> JCaptcha sinh captcha MỚI thay cái cũ (đáp án cũ mất hiệu lực).
     */
    private static int image(HttpExchange ex, String id) throws IOException {
        long t0 = System.nanoTime();
        RecordingWordGenerator.LAST.remove();
        BufferedImage img = CAPTCHA.getImageChallengeForID(id);
        long genMs = (System.nanoTime() - t0) / 1_000_000;
        if (DEBUG) {
            String answer = RecordingWordGenerator.LAST.get();
            if (answer != null) {
                if (DEBUG_ANSWERS.size() > 10_000) DEBUG_ANSWERS.clear();
                DEBUG_ANSWERS.put(id, answer);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Generate-Ms", String.valueOf(genMs));
        return send(ex, 200, "image/png", bos.toByteArray());
    }

    /** Bước 3: kiểm tra. JCaptcha so đáp án rồi XÓA captcha, dù đúng hay sai -> mỗi captcha thử 1 lần. */
    private static int verify(HttpExchange ex) throws IOException {
        Map<String, String> f = body(ex);
        String id = f.getOrDefault("id", "").trim();
        String answer = f.getOrDefault("answer", "").trim();
        if (id.isEmpty() || answer.isEmpty())
            return send(ex, 400, "application/json", "{\"ok\":false,\"reason\":\"missing_fields\"}");
        DEBUG_ANSWERS.remove(id);
        String reason;
        try {
            reason = CAPTCHA.validateResponseForID(id, answer) ? "correct" : "wrong";
        } catch (CaptchaServiceException e) {
            reason = "unknown_id";   // id chưa từng gọi ảnh / đã verify / đã bị dọn
        }
        return send(ex, 200, "application/json",
                "{\"ok\":" + reason.equals("correct") + ",\"reason\":\"" + reason + "\"}");
    }

    private static int stats(HttpExchange ex) throws IOException {
        String json = "{"
                + "\"storeSize\":" + CAPTCHA.getCaptchaStoreSize()
                + ",\"generated\":" + CAPTCHA.getNumberOfGeneratedCaptchas()
                + ",\"correct\":" + CAPTCHA.getNumberOfCorrectResponses()
                + ",\"wrong\":" + CAPTCHA.getNumberOfUncorrectResponses()
                + ",\"garbageCollected\":" + CAPTCHA.getNumberOfGarbageCollectedCaptcha()
                + ",\"minStorageSeconds\":" + CAPTCHA.getMinGuarantedStorageDelayInSeconds()
                + ",\"debug\":" + DEBUG
                + ",\"rateLimitPerMinute\":" + RATE_LIMIT
                + "}";
        return send(ex, 200, "application/json", json);
    }

    private static int debugAnswer(HttpExchange ex, String id) throws IOException {
        if (!DEBUG) return send(ex, 404, "application/json", "{\"error\":\"not_found\"}");
        String a = DEBUG_ANSWERS.get(id);
        if (a == null) return send(ex, 404, "application/json", "{\"error\":\"no_answer\"}");
        return send(ex, 200, "application/json", "{\"id\":\"" + id + "\",\"answer\":\"" + a + "\"}");
    }

    private static int page(HttpExchange ex) throws IOException {
        try (InputStream in = Server.class.getResourceAsStream("/web/index.html")) {
            if (in == null) return send(ex, 500, "text/plain", "web/index.html not found in classpath");
            return send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    // ---------------------------------------------------------------- helpers

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

    /** Đọc body: form-urlencoded hoặc JSON phẳng {"id":"..","answer":".."}. */
    private static Map<String, String> body(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        String ct = String.valueOf(ex.getRequestHeaders().getFirst("Content-Type"));
        if (ct.contains("json")) {
            Matcher kv = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").matcher(raw);
            while (kv.find()) m.put(kv.group(1), kv.group(2));
        } else {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                                 URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    /**
     * IP dùng cho rate limit.
     * Chỉ tin X-Forwarded-For khi TRUST_PROXY=1 (app nằm sau proxy, client không gửi thẳng tới app được).
     * Nếu không, ai cũng tự gửi header này với IP bịa mỗi lần -> né được rate limit.
     */
    private static String ip(HttpExchange ex) {
        if (TRUST_PROXY) {
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static int send(HttpExchange ex, int code, String type, String body) throws IOException {
        return send(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static int send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (ex.getRequestMethod().equals("HEAD")) {   // HEAD: chỉ header, không body
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
