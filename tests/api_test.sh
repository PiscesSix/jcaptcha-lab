#!/usr/bin/env bash
# Test API captcha bằng curl. Server phải chạy với DEBUG=1 (để lấy đáp án cho case "nhập đúng").
# Dùng: ./tests/api_test.sh [base_url]
set -uo pipefail
B=${1:-http://localhost:8080}
PASS=0; FAIL=0
check() { # check "tên" "giá trị thực" "giá trị mong đợi"
  if [ "$2" == "$3" ]; then echo "  ✅ $1"; PASS=$((PASS+1)); else echo "  ❌ $1 — nhận: $2 | mong đợi: $3"; FAIL=$((FAIL+1)); fi
}
jget() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }
new_id() { curl -s "$B/rest/v2/applicants/jcaptcha" | jget id; }
verify() { curl -s -X POST --data-urlencode "id=$1" --data-urlencode "answer=$2" "$B/rest/v2/applicants/jcaptcha/verify" | jget reason; }
answer() { curl -s "$B/api/debug/answer/$1" | jget answer; }

echo "== Health";        check "GET /health" "$(curl -s $B/health)" "ok"

echo "== Ảnh"
ID=$(new_id)
H=$(curl -s -D - -o /tmp/cap.png "$B/$ID")
check "status 200"            "$(echo "$H" | head -1 | awk '{print $2}')" "200"
check "Content-Type png"      "$(echo "$H" | grep -i '^content-type' | tr -d '\r' | awk '{print $2}')" "image/png"
check "Cache-Control no-store" "$(echo "$H" | grep -i '^cache-control' | tr -d '\r' | awk '{print $2}')" "no-store"
check "kích thước 200x60"     "$(python3 -c "import struct;d=open('/tmp/cap.png','rb').read(24);print('%dx%d'%struct.unpack('>II',d[16:24]))")" "200x60"
check "UUID sai định dạng -> 404" "$(curl -s -o /dev/null -w '%{http_code}' $B/not-a-uuid)" "404"

echo "== Verify"
ID=$(new_id); curl -s -o /dev/null "$B/$ID"; A=$(answer "$ID")
check "đáp án dài 6 ký tự"    "${#A}" "6"
check "nhập đúng -> correct"  "$(verify "$ID" "$A")" "correct"
check "dùng lại id -> unknown_id" "$(verify "$ID" "$A")" "unknown_id"

ID=$(new_id); curl -s -o /dev/null "$B/$ID"; A=$(answer "$ID")
check "không phân biệt hoa thường" "$(verify "$ID" "$(echo "$A" | tr 'a-zA-Z' 'A-Za-z')")" "correct"

ID=$(new_id); curl -s -o /dev/null "$B/$ID"
check "nhập sai -> wrong"     "$(verify "$ID" "zzzzzz")" "wrong"
check "sai rồi thử lại -> unknown_id (1 lần duy nhất)" "$(verify "$ID" "zzzzzz")" "unknown_id"

ID=$(new_id)
check "id chưa gọi ảnh -> unknown_id" "$(verify "$ID" "abcdef")" "unknown_id"
check "thiếu answer -> missing_fields" "$(curl -s -X POST -d "id=$ID" $B/rest/v2/applicants/jcaptcha/verify | jget reason)" "missing_fields"

echo "== Tải lại ảnh cùng id -> đáp án cũ mất hiệu lực"
ID=$(new_id); curl -s -o /dev/null "$B/$ID"; A1=$(answer "$ID")
curl -s -o /dev/null "$B/$ID"; A2=$(answer "$ID")
if [ "$A1" != "$A2" ]; then
  check "đáp án cũ -> wrong" "$(verify "$ID" "$A1")" "wrong"
else
  echo "  (2 lần sinh trùng đáp án, bỏ qua)"
fi

echo "== JSON body"
ID=$(new_id); curl -s -o /dev/null "$B/$ID"; A=$(answer "$ID")
check "verify bằng JSON" "$(curl -s -X POST -H 'Content-Type: application/json' -d "{\"id\":\"$ID\",\"answer\":\"$A\"}" $B/rest/v2/applicants/jcaptcha/verify | jget reason)" "correct"

echo; echo "Kết quả: $PASS pass, $FAIL fail"
[ $FAIL -eq 0 ]
