# Captcha Lab

Mô phỏng cách một hệ thống kiểu **OpenCPS** (dịch vụ công trên Liferay, Java) dùng captcha: **captcha gắn với session**, ảnh trả thẳng PNG, đáp án gửi kèm form qua field `j_captcha_response`. Có cả 2 engine: **SimpleCaptcha** (`simplecaptchanew`) và **JCaptcha** (`jcaptcha`), cùng một trang web để test captcha, test ảnh và test request.

> Dữ liệu (hồ sơ, lịch hẹn) đều là giả lập trong bộ nhớ. Lab này để học cách captcha + session hoạt động.

## Luồng hoạt động

```
Trình duyệt                                   Server
───────────                                   ──────
click ↻ → makeImageCap()
GET /o/rest/v2/applicants/simplecaptchanew ─▶ lấy session theo cookie JSESSIONID
                                              (chưa có → tạo mới, Set-Cookie: JSESSIONID=…; HttpOnly)
                                              SimpleCaptcha sinh ảnh + đáp án
                                              session["_SIMPLE_CAPTCHA"] = đáp án
           ◀───────────────────────────────── 200 image/png
                                              Content-Disposition: attachment; filename="<millis>.png"
URL.createObjectURL(blob)
→ <img src="blob:https://host/<uuid>">        (UUID do trình duyệt sinh, server không biết)

người dùng gõ mã
POST /o/rest/v2/dossiers/701013/actions ────▶ lấy session theo cookie
  multipart: actionCode=1600, payment,        lấy + XÓA đáp án trong session
  assignUsers, actionNote,                    so j_captcha_response (không phân biệt hoa thường)
  payload={"userClass":"C",…},                đúng → xử lý action, sai → 403 CAPTCHA_INVALID
  j_captcha_response=pxjnd
  (cookie JSESSIONID tự gửi kèm)
           ◀───────────────────────────────── 200 {"status":"done",…}  |  403 {"code":"CAPTCHA_INVALID","reason":…}
```

Với `jcaptcha`, captcha id chính là session id (`getImageChallengeForID(sessionId)`, giống OpenCPS), đáp án nằm trong store của JCaptcha thay vì thuộc tính session.

Hệ quả của thiết kế này:
- **Đáp án không bao giờ rời server.** Trình duyệt chỉ có ảnh và cookie; cookie HttpOnly nên JavaScript cũng không đọc được.
- **Ảnh và form phải cùng session.** Không gửi cookie → `no_session`. Đáp án của session A gửi bằng session B → sai.
- **Mỗi lần lấy ảnh ghi đè đáp án cũ.** Bấm ↻ thì đáp án của ảnh trước mất hiệu lực.
- **Dùng 1 lần.** Kiểm tra xong (đúng hay sai) đáp án bị xóa; gửi lại (replay) → `no_captcha`.
- **Tải ảnh không cần đăng nhập** là bình thường: captcha phục vụ người chưa có tài khoản. Thứ cần bảo vệ là đáp án và bước kiểm tra.

## Chạy

Cần JDK 17+ và git.

```bash
./build.sh                                  # clone SimpleCaptcha, JCaptcha + lib phụ, compile
DEBUG=1 java -cp out demo.Server            # http://localhost:8080
./tests/api_test.sh http://localhost:8080   # 27 test bằng curl + cookie jar (cần DEBUG=1)
java -cp out demo.Samples 6                 # ảnh mẫu simple_*.png, jcaptcha_*.png
```

Docker: `docker build -t captcha-lab . && docker run -p 8080:8080 -e DEBUG=1 captcha-lab`

Online: Render (`render.yaml`, gói free) hoặc GitHub Codespaces (`.devcontainer`).

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | 8080 | cổng |
| `DEBUG` | 0 | `1` = `/api/session` trả kèm đáp án đang lưu trong session (chỉ dùng khi test) |
| `RATE_LIMIT` | 60 | request ảnh + action / phút / IP, vượt thì 429 |
| `TRUST_PROXY` | 0 | `1` = lấy IP từ `X-Forwarded-For`. Chỉ bật khi chạy sau reverse proxy |
| `SESSION_MINUTES` | 30 | session không dùng quá thời gian này thì bị xóa |
| `CTF` | 1 | `0` = tắt các level cố tình có lỗi ở `/ctf` |
| `CTF_TEST` | 0 | `1` = ảnh CTF kèm header đáp án, chỉ dùng cho `tests/ctf_test.sh`. Bật khi chơi là lộ đáp án |

## API

| Method | Path | Ghi chú |
|---|---|---|
| GET | `/o/rest/v2/applicants/simplecaptchanew[?width=&height=]` | PNG (SimpleCaptcha), mặc định 200×60, đáp án vào session |
| GET | `/o/rest/v2/applicants/jcaptcha[?width=&height=]` | PNG (JCaptcha), captcha id = session id |
| POST | `/o/rest/v2/dossiers/{id}/actions` | multipart hoặc form-urlencoded: `actionCode`, `payment`, `assignUsers`, `actionNote`, `payload`, `j_captcha_response` |
| GET | `/api/session` | session hiện tại (`DEBUG=1`: kèm đáp án) |
| GET | `/api/stats` | thống kê |
| GET | `/health` | `ok` |

Lý do từ chối (`reason` trong body 403): `wrong` sai mã · `no_captcha` session chưa có captcha hoặc đã dùng · `no_session` không có cookie / session hết hạn · `missing` thiếu `j_captcha_response`.

## Cách ảnh được tạo

**SimpleCaptcha** (`SimpleCaptchaFactory.java`) — khung `Captcha.Builder` của thư viện, 4 khối tự viết cắm vào:
```
Captcha.Builder(200, 60)
  .addText(RandomTextProducer, UprightWordRenderer)  sinh 6 ký tự; vẽ chữ đứng, mỗi chữ 1 màu/1 cỡ, xếp sát theo bề rộng thật
  .addNoise(ThickBandNoise)                          chùm đường xám dày đè LÊN chữ
  .addBackground(SpeckleArcBackground)               nền lấm tấm, vết xước, 2 đường cố định, cung tròn (DƯỚI chữ)
  .build()                                           dán lớp chữ+nhiễu lên lớp nền
```
`DefaultTextProducer` của SimpleCaptcha có lỗi: ký tự đầu tiên của bộ ký tự không bao giờ được chọn, nên lab dùng producer riêng.

**JCaptcha** (`SiteLikeEngine.java`) — `GimpyFactory = WordGenerator + WordToImage`; `WordToImage = FontGenerator + BackgroundGenerator + TextPaster(visitors, decorators) + Deformation`. Nền (`ScratchWavyBackground`) và decorator (`WavyBandDecorator`) tự viết.

Cả hai bỏ ký tự dễ nhầm (`0 o O 1 l i I j 2 z 5 s 6 b 8 B 9 g q u v r`).

## CTF bug bounty (`/ctf`)

10 level. Level 1–8 là các phiên bản **cố tình có lỗi** của bước kiểm tra captcha; level 9–10 có logic đúng nhưng tấm ảnh yếu. Khai thác đúng lỗi thì response trả về `FLAG{…}`. Flag được sinh ngẫu nhiên mỗi lần khởi động server.

| Level | Chủ đề |
|---|---|
| 1 | Token lạ trong response |
| 2 | Captcha dùng mãi |
| 3 | Field không bắt buộc |
| 4 | Chuỗi "null" |
| 5 | Ảnh đang bảo trì |
| 6 | Đáp án dùng chung |
| 7 | Race condition |
| 8 | Kiểm tra bằng cookie |
| 9 | Captcha chữ "sạch": tesseract đọc đúng 39/40 ảnh (ảnh nhiễu: 0/40) |
| 10 | Lỗi thiết kế ảnh: giải bằng TOÁN THUẦN (màu + thành phần liên thông + khớp mẫu NCC), không OCR/ML — bộ giải mẫu đọc đúng 40/40 |

Trang `/ctf` có gợi ý từng level, mẫu viết báo cáo bug bounty và tóm tắt các nghiên cứu phá captcha từ 2011 đến 2026 (Bursztein, Goodfellow, George, Ye, Searles, Plesner, Halligan, Open CaptchaWorld, COGNITION, Broken Gates). Lời giải: `docs/CTF_WRITEUP.md` và `tests/ctf_test.sh` (**spoiler**). Chạy test: `CTF_TEST=1 DEBUG=1 java -cp out demo.Server` rồi `./tests/ctf_test.sh`.

Các kỹ thuật trong CTF chỉ dùng trên lab của mình hoặc hệ thống cho phép bằng văn bản.

## File

| File | Vai trò |
|---|---|
| `src/demo/Server.java` | HTTP server: session/cookie, 2 endpoint captcha, dossier action, rate limit, log |
| `src/demo/SessionStore.java` | mô phỏng HttpSession: id ngẫu nhiên, thuộc tính, hết hạn |
| `src/demo/SimpleCaptchaFactory.java` | engine SimpleCaptcha |
| `src/demo/SiteLikeEngine.java` + `ScratchWavyBackground` + `WavyBandDecorator` | engine JCaptcha |
| `src/demo/WavyLines.java` | vẽ đường lượn (tổng 2 sóng sin) |
| `src/demo/RecordingWordGenerator.java` | ghi lại đáp án JCaptcha cho chế độ DEBUG |
| `web/index.html` | trang lab: makeImageCap → blob URL, form multipart, thử replay / không cookie / thiếu field |
| `tests/api_test.sh` | 27 test luồng session bằng curl |
| `src/demo/Ctf.java` | 9 level CTF |
| `web/ctf.html` | trang CTF: level, gợi ý, nộp flag, mẫu báo cáo, tóm tắt nghiên cứu |
| `tests/ctf_test.sh` | lời giải tự động 10 level (spoiler) |
| `tests/solve_level10.py` | bộ giải toán thuần cho level 10: saturation → scipy.ndimage.label → NCC (spoiler) |
| `docs/CTF_WRITEUP.md` | writeup: lỗi, nguyên nhân, khai thác, tác động, cách sửa (spoiler) |
