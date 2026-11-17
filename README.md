# JCaptcha Lab

Dựng lại captcha kiểu "chữ màu + chùm đường xám + nền xước" (200×60) bằng **JCaptcha 2.0**, kèm trang web để test captcha, test ảnh và test request.

## Chạy

Cần JDK 17+ và git.

```bash
./build.sh                                  # clone JCaptcha + lib phụ, compile
DEBUG=1 java -cp out demo.Server            # http://localhost:8080
./tests/api_test.sh http://localhost:8080   # 16 test API (cần DEBUG=1)
java -cp out demo.Samples 10                # sinh ảnh mẫu ra thư mục hiện tại
```

Hoặc chạy bằng Docker:

```bash
docker build -t jcaptcha-lab .
docker run -p 8080:8080 -e DEBUG=1 jcaptcha-lab
```

Biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | 8080 | cổng |
| `DEBUG` | 0 | `1` = bật `/api/debug/answer/<id>` để xem đáp án (chỉ dùng khi test) |
| `RATE_LIMIT` | 60 | số request ảnh + verify / phút / IP, vượt thì trả 429 |

## API

| Method | Path | Trả về |
|---|---|---|
| GET | `/rest/v2/applicants/jcaptcha` | `{"id":"<uuid>"}` |
| GET | `/<uuid>` | `image/png` 200×60, `Cache-Control: no-store` |
| POST | `/rest/v2/applicants/jcaptcha/verify` | body form hoặc JSON `id`, `answer` → `{"ok":bool,"reason":"correct\|wrong\|unknown_id\|missing_fields"}` |
| GET | `/api/stats` | thống kê của JCaptcha service |
| GET | `/api/debug/answer/<uuid>` | đáp án (chỉ khi `DEBUG=1`) |
| GET | `/health` | `ok` |

Hành vi của JCaptcha cần nhớ:
- Gọi ảnh lần 2 với cùng id thì JCaptcha **sinh captcha mới**, đáp án cũ mất hiệu lực.
- Verify **luôn xóa** captcha, dù đúng hay sai, nên mỗi captcha chỉ thử được 1 lần.
- Id chưa từng gọi ảnh, đã verify, hoặc đã bị dọn đều trả `unknown_id`.
- So đáp án không phân biệt hoa thường.

## Luồng tạo ảnh

```
Service (DefaultManageableImageCaptchaService + FastHashMapCaptchaStore)   lưu id → captcha, tự dọn
 └─ SiteLikeEngine
     └─ GimpyFactory = WordGenerator + WordToImage
         1. RandomWordGenerator        sinh 6 ký tự (đáp án)
         2. RandomFontGenerator        font sans đậm 28–36px, chọn riêng cho từng ký tự
         3. ScratchWavyBackground *    nền: xước + bụi + 2 đường sin cố định + đường lượn   (dưới chữ)
         4. GlyphsPaster               dán chữ, mỗi chữ 1 màu
              visitors: lệch dọc, xoay ±10°, giãn cách, đặt vị trí
              decorator WavyBandDecorator *  chùm đường xám ngang giữa ảnh                  (đè lên chữ)
         5. Deformation                làm mờ 3×3
```
`*` = tự viết, cắm vào JCaptcha qua interface `BackgroundGenerator` / `GlyphsDecorator`. Các khối còn lại là của JCaptcha.

## File

| File | Vai trò |
|---|---|
| `src/demo/SiteLikeEngine.java` | lắp engine: chỉnh màu, font, số đường nhiễu ở đây |
| `src/demo/ScratchWavyBackground.java` | nền |
| `src/demo/WavyBandDecorator.java` | chùm đường đè lên chữ |
| `src/demo/WavyLines.java` | hàm vẽ đường lượn (tổng 2 sóng sin) |
| `src/demo/RecordingWordGenerator.java` | ghi lại đáp án cho chế độ DEBUG (production không cần) |
| `src/demo/Server.java` | HTTP server, rate limit, log request |
| `web/index.html` | trang lab |
| `tests/api_test.sh` | test API bằng curl |
| `.github/workflows/ci.yml` | mỗi lần push: build, chạy test, upload ảnh mẫu thành artifact |
