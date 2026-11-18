#!/usr/bin/env bash
# Test luồng captcha gắn session bằng curl (cookie jar giả làm trình duyệt).
# Server phải chạy với DEBUG=1 để đọc được đáp án qua /api/session.
# Dùng: ./tests/api_test.sh [base_url]
set -uo pipefail
B=${1:-http://localhost:8080}
PASS=0; FAIL=0
check() { if [ "$2" == "$3" ]; then echo "  ✅ $1"; PASS=$((PASS+1)); else echo "  ❌ $1 — nhận: $2 | mong đợi: $3"; FAIL=$((FAIL+1)); fi; }
jget() { python3 -c "import sys,json
try: print(json.load(sys.stdin).get('$1',''))
except Exception: print('')"; }
J=$(mktemp)   # cookie jar = "trình duyệt" 1
J2=$(mktemp)  # cookie jar = "trình duyệt" 2
trap 'rm -f $J $J2 /tmp/cap.png' EXIT

cap()    { curl -s -b "$1" -c "$1" -o /tmp/cap.png -w '%{http_code}' "$B/o/rest/v2/applicants/$2"; }
answer() { curl -s -b "$1" "$B/api/session" | jget answer; }
act()    { # act <jar|none> <captcha|-> : gửi multipart giống form gốc, in "status reason"
  local jar=$1 cap=$2 args=(-s -o /tmp/act.json -w '%{http_code}' -F actionCode=1600 -F payment= -F assignUsers= -F actionNote=
        -F 'payload={"userClass":"C","bookingDate":"18/11/2026","bookingTime":"09:00-10:00"}')
  [ "$cap" != "-" ] && args+=(-F "j_captcha_response=$cap")
  [ "$jar" != "none" ] && args+=(-b "$jar" -c "$jar")
  local code; code=$(curl "${args[@]}" "$B/o/rest/v2/dossiers/701013/actions")
  echo "$code $(jget reason < /tmp/act.json)"
}

echo "== Health";  check "GET /health" "$(curl -s $B/health)" "ok"

echo "== Ảnh simplecaptchanew"
H=$(curl -s -D - -o /tmp/cap.png -c "$J" "$B/o/rest/v2/applicants/simplecaptchanew")
check "status 200"                  "$(echo "$H" | head -1 | awk '{print $2}')" "200"
check "Content-Type image/png"      "$(echo "$H" | grep -i '^content-type' | tr -d '\r' | awk '{print $2}')" "image/png"
check "Content-Disposition attachment; filename=<millis>.png" \
      "$(echo "$H" | grep -i '^content-disposition' | tr -d '\r' | grep -cE 'attachment; filename="[0-9]{13}\.png"')" "1"
check "Set-Cookie JSESSIONID HttpOnly" "$(echo "$H" | grep -i '^set-cookie' | grep -c 'JSESSIONID=.*HttpOnly')" "1"
check "kích thước mặc định 200x60"  "$(python3 -c "import struct;d=open('/tmp/cap.png','rb').read(24);print('%dx%d'%struct.unpack('>II',d[16:24]))")" "200x60"
curl -s -o /tmp/cap.png "$B/o/rest/v2/applicants/simplecaptchanew?width=180&height=40"
check "?width=180&height=40"        "$(python3 -c "import struct;d=open('/tmp/cap.png','rb').read(24);print('%dx%d'%struct.unpack('>II',d[16:24]))")" "180x40"

for E in simplecaptchanew jcaptcha; do
  echo "== Luồng đặt lịch với $E"
  : > "$J"; : > "$J2"
  cap "$J" $E >/dev/null; A=$(answer "$J")
  check "[$E] đáp án 6 ký tự"                         "${#A}" "6"
  check "[$E] nhập đúng -> 200"                        "$(act "$J" "$A")" "200 "
  check "[$E] replay cùng đáp án -> 403 no_captcha"    "$(act "$J" "$A")" "403 no_captcha"

  cap "$J" $E >/dev/null; A=$(answer "$J")
  check "[$E] chữ hoa/thường đảo -> 200"               "$(act "$J" "$(echo "$A" | tr 'a-zA-Z' 'A-Za-z')")" "200 "

  cap "$J" $E >/dev/null
  check "[$E] sai -> 403 wrong"                        "$(act "$J" zzzzzz)" "403 wrong"

  cap "$J" $E >/dev/null; A=$(answer "$J")
  check "[$E] đúng đáp án nhưng KHÔNG gửi cookie -> 403 no_session" "$(act none "$A")" "403 no_session"
  check "[$E] (sau đó) đáp án vẫn còn trong session 1 -> 200"        "$(act "$J" "$A")" "200 "

  cap "$J" $E >/dev/null; A=$(answer "$J"); cap "$J2" $E >/dev/null
  check "[$E] đáp án của session 1 gửi bằng session 2 -> 403"        "$(act "$J2" "$A" | cut -d' ' -f1)" "403"

  cap "$J" $E >/dev/null; A1=$(answer "$J"); cap "$J" $E >/dev/null; A2=$(answer "$J")
  if [ "$A1" != "$A2" ]; then check "[$E] lấy ảnh mới -> đáp án ảnh cũ hết hiệu lực" "$(act "$J" "$A1")" "403 wrong"; fi

  cap "$J" $E >/dev/null
  check "[$E] thiếu j_captcha_response -> 403 missing" "$(act "$J" -)" "403 missing"
done

echo; echo "Kết quả: $PASS pass, $FAIL fail"
[ $FAIL -eq 0 ]
