# ⚠️ SPOILER — Writeup 10 level CTF captcha

Tự chơi ở `/ctf` trước khi đọc. Mỗi level gồm: lỗi, nguyên nhân gốc trong code (`src/demo/Ctf.java`), cách khai thác, tác động và cách sửa. Bản kiểm tra đúng để đối chiếu là `Server.checkCaptcha()`:

```java
if (s == null) return "no_session";             // phải có session
Object type = s.remove(ATTR_TYPE);              // lấy VÀ xóa trong cùng một bước
String expected = (String) s.remove(ATTR_SIMPLE);
if (type == null) return "no_captcha";          // chưa lấy ảnh / đã dùng
if (response == null || response.isBlank()) return "missing";
return expected != null && expected.equalsIgnoreCase(response.trim()) ? "ok" : "wrong";
```

---

## Level 1 — Đáp án lộ trong header

**Lỗi:** response ảnh có `X-Captcha-Token: NGtoRExo`. Trông giống token nhưng thực ra là `base64(đáp án)`.

**Khai thác:** `echo NGtoRExo | base64 -d` ra `4khDlh`. Viết script: lấy ảnh → đọc header → giải base64 → gửi. Làm 5 lần trong 10 giây.

**Tác động:** captcha mất tác dụng hoàn toàn, bot không cần OCR. Mức **High**, vì vượt được kiểm soát chống bot của toàn bộ chức năng.

**Sửa:** đáp án chỉ được nằm ở server. Rà mọi chỗ response có thể lộ dữ liệu: header, tên file, cookie, JSON, log, metadata của ảnh. Encode (base64, hex) không phải là mã hóa.

## Level 2 — Không xóa đáp án sau khi đúng (replay)

**Lỗi:** so đáp án xong không gọi `remove`.

**Khai thác:** giải đúng 1 lần, rồi gửi lại y nguyên request đó 4 lần nữa (Burp Repeater hoặc lặp curl).

**Tác động:** giải 1 lần, dùng không giới hạn. Bot thuê người giải 1 captcha rồi đặt hàng trăm lịch. Mức **High**.

**Sửa:** lấy và xóa đáp án ngay khi kiểm tra (`remove` thay cho `get`), áp dụng cho cả trường hợp đúng lẫn sai.

## Level 3 — Bước kiểm tra chỉ chạy khi có field

**Lỗi:** `if (f.containsKey("j_captcha_response")) ok = check(...)`. Không có field thì `ok` giữ nguyên giá trị `true`.

**Khai thác:** xóa field `j_captcha_response` khỏi form (`curl -F actionCode=1600 ...` mà không có field captcha).

**Tác động:** bỏ qua captcha hoàn toàn. Mức **High**. Đây là lỗi rất hay gặp ngoài thực tế: frontend luôn gửi field nên dev không nghĩ tới trường hợp request không có nó.

**Sửa:** mặc định là từ chối (fail closed). Thiếu field, field rỗng, hay chưa có captcha trong session đều phải bị từ chối.

## Level 4 — Chuỗi "null"

**Lỗi:** `String.valueOf(session.get(...))`. Khi session chưa có đáp án, giá trị `null` biến thành **chuỗi** `"null"`, và `"null".equalsIgnoreCase("null")` là đúng.

**Khai thác:** không lấy ảnh, gửi `j_captcha_response=null` (không kèm cookie, hoặc dùng session mới).

**Tác động:** bỏ qua captcha. Mức **High**. Lỗi kiểu này (chuyển null thành chuỗi, ép kiểu lỏng) cũng có ở các ngôn ngữ khác, ví dụ so sánh `==` lỏng trong PHP hay JS.

**Sửa:** kiểm tra `expected != null` trước khi so; không dùng `String.valueOf` hoặc ép chuỗi cho dữ liệu dùng để xác thực.

## Level 5 — Đáp án ngắn + không giới hạn số lần thử

**Lỗi:** 3 chữ số (1000 khả năng), đoán sai không xóa đáp án, không có rate limit.

**Khai thác:** lấy "ảnh" (503) để server tạo đáp án, rồi thử lần lượt 000–999 bằng cùng một cookie:
```bash
seq -w 0 999 | xargs -P 16 -I{} curl -s -b "JSESSIONID=$SID" -F actionCode=1600 -F j_captcha_response={} $B/ctf/5/actions
```

**Tác động:** vượt captcha bằng brute-force trong vài giây. Mức **Medium–High**.

**Sửa:** đoán sai là xóa đáp án (dùng 1 lần); đáp án đủ dài với bộ ký tự đủ lớn (6 ký tự từ 36 ký tự là khoảng 2 tỷ khả năng); rate limit theo IP và theo session; khóa tạm sau N lần sai.

## Level 6 — Đáp án dùng chung

**Lỗi:** đáp án lưu ở biến `static` dùng chung, không gắn với session.

**Khai thác:** lấy ảnh bằng trình duyệt A và đọc mã, rồi gửi đáp án bằng một request không có cookie. Trên hệ thống thật, đáp án người khác vừa giải cũng dùng được cho mình, và nhiều người dùng cùng lúc thì ghi đè đáp án của nhau.

**Tác động:** 1 người (hoặc 1 dịch vụ giải captcha) giải, nhiều bot cùng dùng. Người dùng thật còn bị báo sai liên tục vì đáp án bị ghi đè. Mức **Medium–High**.

**Sửa:** đáp án luôn gắn với session hoặc với một token riêng cho từng captcha, không dùng biến toàn cục.

## Level 7 — Race condition (TOCTOU)

**Lỗi:** `get` (kiểm tra) → mất 300ms ghi DB → `remove` (xóa). Mọi request đến trong khoảng 300ms đó đều thấy đáp án còn.

**Khai thác:** giải 1 lần, rồi gửi 6 request song song với cùng một đáp án:
```bash
for i in 1 2 3 4 5 6; do curl ... -F j_captcha_response=$A $B/ctf/7/actions & done; wait
```
Burp Suite có tính năng "Send group in parallel" (single-packet attack) dùng đúng cho trường hợp này.

**Tác động:** 1 captcha đổi được nhiều action. Mức **Medium**. Lỗi khó phát hiện vì test tuần tự thì không bao giờ thấy.

**Sửa:** lấy và xóa trong **một thao tác nguyên tử** (`map.remove(key)` trả về giá trị cũ; với Redis dùng `GETDEL`), rồi mới làm việc tốn thời gian.

## Level 8 — Kiểm tra bằng dữ liệu phía client

**Lỗi:** server gửi `captcha_hash = md5(đáp án)` vào cookie, rồi kiểm tra bằng cách so `md5(câu trả lời)` với chính cookie đó. Mà cookie là thứ client tự sửa được.

**Khai thác:** tự đặt cookie `captcha_hash = md5("abc")` rồi gửi `j_captcha_response=abc`, không cần lấy ảnh. Cách khác: lấy ảnh rồi tra md5 của 6 ký tự (brute-force offline rất nhanh).

**Tác động:** bỏ qua captcha. Mức **High**.

**Sửa:** không tin dữ liệu xác thực do client gửi lên. Nếu bắt buộc phải lưu trạng thái phía client thì phải ký bằng HMAC với khóa bí mật, có thời hạn và nonce chỉ dùng một lần. Đơn giản nhất là lưu đáp án ở server.

## Level 9 — Captcha chữ "sạch" (lỗi nằm ở tấm ảnh)

**Lỗi:** logic kiểm tra đúng hoàn toàn (`checkCorrectly`: gắn session, dùng 1 lần, không lộ đáp án). Nhưng ảnh chỉ là chữ đen, font mono, nền trắng, không nhiễu, nên OCR phổ thông đọc được.

**Khai thác:** dùng một công cụ OCR có sẵn, không cần huấn luyện gì:
```bash
curl -s -b j -c j -o c.png $B/ctf/9/captcha
tesseract c.png stdout --psm 7 -c tessedit_char_whitelist=ACDEFHJKLMNPRTWXY347
```
Lặp 10 lần trong 20 giây. Đo trong lab: tesseract đọc đúng **39/40** ảnh level 9, mỗi ảnh khoảng 110 ms. Với ảnh nhiễu kiểu web gốc (level 1–8), cùng lệnh đó đọc đúng **0/40**.

**Liên hệ nghiên cứu:** đây là thời kỳ trước 2011, khi captcha chữ đơn giản bị OCR đọc thẳng. Ảnh nhiễu chặn được OCR phổ thông (0/40), nhưng các nghiên cứu sau đó (Bursztein 2011, Goodfellow 2014, George 2017, Ye 2018) cho thấy model huấn luyện riêng vẫn đọc được loại nhiễu. Ye 2018 còn chỉ cần một bộ sinh captcha giả và vài trăm ảnh thật. Lab này cố ý **không** làm level huấn luyện model giải ảnh nhiễu.

**Tác động:** captcha chỉ còn là hình thức; bot đi qua với tốc độ của máy. Mức **Medium**: bản thân logic không sai, nhưng mục đích chống bot không đạt.

**Sửa:** không có bản "sửa ảnh" nào bền lâu (xem mục nghiên cứu trên trang `/ctf`). Hướng đúng là **nhiều lớp**: giữ logic server đúng như level 1–8; rate limit theo IP *và* theo tài khoản; dùng tín hiệu hành vi và danh tiếng trình duyệt (Turnstile, reCAPTCHA v3); giới hạn số lượt mỗi người cho tài nguyên khan hiếm như lịch hẹn; xác minh danh tính (OTP, tài khoản định danh) cho thao tác giá trị cao.

## Level 10 — Lỗi thiết kế ảnh, giải bằng TOÁN THUẦN

**Lỗi:** logic đúng, ảnh có nhiễu nên OCR phổ thông trượt. Nhưng tấm ảnh phạm đúng ba "lỗi thiết kế chết người" mà Yan & El Ahmad (ACSAC 2007) mô tả:
1. **Nhiễu phân biệt được với chữ bằng thống kê:** nhiễu là nét xám (bão hòa ~0), chữ có màu (bão hòa cao).
2. **Ký tự không dính nhau:** mỗi ký tự là một vùng pixel tách rời.
3. **Font cố định:** nhận dạng được bằng khớp mẫu, không cần học.

**Khai thác — pipeline toán cổ điển, không OCR, không học máy** (`tests/solve_level10.py`, chỉ dùng numpy + scipy.ndimage + PIL):

| Bước | Toán | Làm gì |
|---|---|---|
| 1. Tách chữ/nhiễu | `S = (max−min)/max` trong HSV | Giữ pixel bão hòa > 0.35 → rụng sạch nhiễu xám + nền + chấm |
| 2. Tách ký tự | Gán nhãn thành phần liên thông 8-hướng (`scipy.ndimage.label`) | Mỗi vùng pixel nối nhau = 1 ký tự; bỏ vùng < 60 px (chấm sót); xếp trái→phải theo tâm x |
| 3. Chuẩn hóa | Cắt sát + resize giữ tỉ lệ về ô 40×40 | Đưa mọi ký tự về cùng khung để so |
| 4. Nhận dạng | NCC `Σ(a−ā)(b−b̄) / √(Σ(a−ā)²·Σ(b−b̄)²)` | Khớp với glyph tự render (DejaVu Sans Bold) ở 3 góc xoay −6°,0°,+6° để chịu xoay nhẹ; chọn điểm cao nhất |

**Kết quả đo trong lab:** bộ giải đọc đúng **40/40** ảnh level 10; chạy end-to-end (tự lấy ảnh → đặt lịch) lấy flag sau 5 lần, khoảng **0.2 giây**. Cùng pipeline này **không** giải được ảnh của level 1–9 (nhiễu cùng màu/độ dày với chữ, ký tự dính nhau).

**Tại sao toán ăn được mà vẫn "không còn thử thách như OCR":** OCR phổ thông (tesseract) đọc trượt ảnh này vì nó không tự tách nhiễu và tách ký tự. Phần việc đó mình làm bằng toán (lọc theo saturation + thành phần liên thông). Đây đúng là luận điểm của các nghiên cứu "toán thuần":
- **Mori & Malik, CVPR 2003** — shape context + so khớp χ²: đọc đúng từ EZ-Gimpy (Yahoo) 92%.
- **Chellapilla & Simard, NIPS 2004** — chứng minh *segmentation* (tách ký tự) mới là phần khó; nhận dạng từng ký tự thì máy làm tốt bằng hoặc hơn người.
- **Yan & El Ahmad, ACSAC 2007** — chỉ dùng nhận dạng mẫu đơn giản (đếm pixel, so khớp) + khai thác lỗi thiết kế, phá 99–100% nhiều loại captcha mà OCR chất lượng cao chịu thua.
- **Gao, Yan và cộng sự, NDSS 2016** — bộ lọc Log-Gabor tách nét theo hướng/tần số, phá hàng loạt captcha của Google/Microsoft/Yahoo/Amazon.

**Sửa:** làm cho nhiễu và chữ **không phân biệt được bằng bất kỳ đại lượng thống kê nào** — cùng màu, cùng độ dày nét, nhiễu cũng là những đoạn cong giống nét chữ; và để các ký tự **dính/đè nhau** để không tách rời bằng thành phần liên thông. Đổi lại người dùng cũng khó đọc hơn. Nhưng kể cả làm đúng hết, model học riêng (CNN+CTC, GAN của Ye 2018) vẫn đọc được — nên kết luận cuối vẫn là: ảnh không phải hàng rào chính, phải dựa thêm rate limit + tín hiệu hành vi + định danh (xem mục nghiên cứu).

---

## Bài học chung

| Nguyên tắc | Level vi phạm |
|---|---|
| Đáp án chỉ ở server, không bao giờ trong response | 1, 8 |
| Mỗi captcha dùng đúng 1 lần, xóa ngay khi kiểm tra | 2, 5, 7 |
| Mặc định từ chối khi thiếu hoặc lạ dữ liệu | 3, 4 |
| Gắn đáp án với đúng người, đúng session | 6 |
| Lấy và xóa nguyên tử | 7 |
| Giới hạn số lần thử, đáp án đủ dài | 5 |
| Không tin dữ liệu client gửi lên | 8 |
| Ảnh captcha không phải hàng rào chính: cần thêm rate limit, tín hiệu hành vi, định danh | 9, 10 |
| Nhiễu phải không phân biệt được với chữ (màu/độ dày/hướng); ký tự nên dính nhau | 10 |
