#!/usr/bin/env python3
"""
Bộ giải mẫu cho CTF level 10 — TOÁN THUẦN, không OCR, không học máy.

Khai thác đúng các "lỗi thiết kế" của ảnh:
  1. Nhiễu xám (bão hòa ~0), chữ có màu (bão hòa cao)  -> lọc theo saturation là rụng nhiễu.
  2. Các ký tự KHÔNG dính nhau                         -> gán nhãn thành phần liên thông, mỗi vùng = 1 ký tự.
  3. Font cố định (DejaVu Sans Bold)                   -> nhận dạng bằng khớp mẫu NCC với glyph tự render.

Chỉ dùng: numpy (đại số), scipy.ndimage (gán nhãn + xoay), PIL (đọc ảnh + render glyph mẫu).

Dùng:  python3 solve_level10.py <ảnh.png>         -> in chuỗi đoán được
       python3 solve_level10.py --serve <base>    -> tự lấy ảnh + đặt lịch 5 lần, lấy flag
"""
import sys, io, urllib.request, urllib.parse, http.cookiejar
import numpy as np
from scipy import ndimage
from PIL import Image, ImageDraw, ImageFont

ALPHABET = "ABCDEFGHKMNPRTWXY347"
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
BOX = 40  # kích thước ô chuẩn hóa để khớp mẫu

# ---------------------------------------------------------------- bước 1: tách chữ khỏi nhiễu bằng màu

def saturation(rgb):
    """S trong HSV, tính bằng đại số trên mảng: (max-min)/max."""
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = np.maximum(np.maximum(r, g), b).astype(float)
    mn = np.minimum(np.minimum(r, g), b).astype(float)
    return np.where(mx > 0, (mx - mn) / np.maximum(mx, 1), 0.0)

def char_mask(rgb):
    """Giữ pixel BÃO HÒA cao và không quá sáng -> nét chữ có màu; bỏ nhiễu xám + nền."""
    sat = saturation(rgb)
    val = rgb.max(axis=2) / 255.0
    return (sat > 0.35) & (val < 0.92)

# ---------------------------------------------------------------- bước 2: gán nhãn thành phần liên thông

def components(mask, min_area=60):
    """Mỗi vùng pixel nối nhau (8-liên thông) là một ký tự. Bỏ vùng quá nhỏ (chấm nhiễu sót)."""
    lab, n = ndimage.label(mask, structure=np.ones((3, 3)))
    out = []
    for i in range(1, n + 1):
        ys, xs = np.where(lab == i)
        if xs.size < min_area:
            continue
        out.append((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1, xs.mean()))
    out.sort(key=lambda c: c[4])          # xếp trái -> phải theo tâm x
    return [(c[0], c[1], c[2], c[3]) for c in out]

def normalize(binary):
    """Cắt sát, đưa về ô BOXxBOX giữ tỉ lệ -> vector nhị phân để so khớp."""
    ys, xs = np.where(binary)
    if xs.size == 0:
        return np.zeros((BOX, BOX))
    crop = binary[ys.min():ys.max() + 1, xs.min():xs.max() + 1].astype(float)
    im = Image.fromarray((crop * 255).astype(np.uint8))
    s = (BOX - 6) / max(crop.shape)
    im = im.resize((max(1, int(crop.shape[1] * s)), max(1, int(crop.shape[0] * s))), Image.BILINEAR)
    canvas = Image.new("L", (BOX, BOX), 0)
    canvas.paste(im, ((BOX - im.width) // 2, (BOX - im.height) // 2))
    return np.asarray(canvas, float) / 255.0

# ---------------------------------------------------------------- bước 3: nhận dạng bằng khớp mẫu NCC

def ncc(a, b):
    """Tương quan chuẩn hóa: bất biến với độ sáng, đo mức khớp hình dạng."""
    a = a - a.mean(); b = b - b.mean()
    d = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / d) if d > 0 else 0.0

def build_templates():
    """Tự render glyph mẫu cho từng ký tự (cùng font) ở vài góc xoay để chịu được xoay nhẹ của ảnh."""
    font = ImageFont.truetype(FONT_PATH, 40)
    templates = {}
    for ch in ALPHABET:
        variants = []
        for ang in (-6, 0, 6):
            im = Image.new("L", (56, 56), 0)
            d = ImageDraw.Draw(im)
            bb = d.textbbox((0, 0), ch, font=font)
            d.text(((56 - (bb[2] - bb[0])) / 2 - bb[0], (56 - (bb[3] - bb[1])) / 2 - bb[1]), ch, 255, font=font)
            if ang:
                im = im.rotate(ang, resample=Image.BILINEAR, center=(28, 28))
            variants.append(normalize(np.asarray(im) > 64))
        templates[ch] = variants
    return templates

def recognize(binary, templates):
    v = normalize(binary)
    best, score = "?", -2
    for ch, variants in templates.items():
        sc = max(ncc(v, t) for t in variants)
        if sc > score:
            best, score = ch, sc
    return best

# ---------------------------------------------------------------- ghép lại: ảnh -> chuỗi

def solve(png_bytes, templates=None):
    templates = templates or build_templates()
    rgb = np.asarray(Image.open(io.BytesIO(png_bytes)).convert("RGB"))
    mask = char_mask(rgb)
    out = ""
    for (x0, y0, x1, y1) in components(mask):
        out += recognize(mask[y0:y1, x0:x1], templates)
    return out

# ---------------------------------------------------------------- chạy end-to-end với server

def run_server(base):
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    templates = build_templates()
    import json, time
    t0 = time.time(); solved = 0; flag = None
    for attempt in range(1, 13):
        png = op.open(base + "/ctf/10/captcha").read()
        guess = solve(png, templates)
        body = urllib.parse.urlencode({
            "actionCode": "1600",
            "payload": '{"userClass":"C","bookingDate":"18/11/2026","bookingTime":"09:00-10:00"}',
            "j_captcha_response": guess,
        }).encode()
        r = json.loads(op.open(base + "/ctf/10/actions", body).read())
        ok = r.get("status") == "done"
        solved += ok
        print(f"  lần {attempt}: đoán '{guess}' -> {'OK' if ok else 'sai'}  ({r.get('progress','')})")
        if r.get("flag"):
            flag = r["flag"]; break
    print(f"\n{solved} lần đúng, {time.time()-t0:.1f}s. Flag: {flag or '(chưa đủ điều kiện)'}")
    return flag

if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--serve":
        run_server(sys.argv[2].rstrip("/"))
    elif len(sys.argv) >= 2:
        print(solve(open(sys.argv[1], "rb").read()))
    else:
        print(__doc__)
