package demo;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mô phỏng HttpSession của servlet container (Liferay/Tomcat):
 * server cấp cookie JSESSIONID ngẫu nhiên, mọi dữ liệu (kể cả đáp án captcha) nằm ở server theo id đó.
 * Trình duyệt chỉ giữ cookie, không bao giờ thấy đáp án.
 */
public final class SessionStore {
    public static final class Session {
        final String id;
        final Map<String, Object> attrs = new ConcurrentHashMap<>();
        volatile long lastAccess = System.currentTimeMillis();
        Session(String id) { this.id = id; }
        public String id() { return id; }
        public Object get(String k) { return attrs.get(k); }
        public void set(String k, Object v) { attrs.put(k, v); }
        public Object remove(String k) { return attrs.remove(k); }
    }

    private static final SecureRandom R = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMs;

    public SessionStore(long ttlMinutes) {
        this.ttlMs = TimeUnit.MINUTES.toMillis(ttlMinutes);
        Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "session-gc"); t.setDaemon(true); return t; })
                .scheduleAtFixedRate(() -> {
                    long now = System.currentTimeMillis();
                    sessions.values().removeIf(s -> now - s.lastAccess > ttlMs);
                }, 1, 1, TimeUnit.MINUTES);
    }

    /** Lấy session theo cookie; không có / hết hạn thì trả null. */
    public Session find(String id) {
        if (id == null) return null;
        Session s = sessions.get(id);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.lastAccess > ttlMs) { sessions.remove(id); return null; }
        s.lastAccess = System.currentTimeMillis();
        return s;
    }

    public Session create() {
        byte[] b = new byte[16];
        R.nextBytes(b);
        Session s = new Session(HexFormat.of().withUpperCase().formatHex(b));
        sessions.put(s.id, s);
        return s;
    }

    public int size() { return sessions.size(); }
}
