#!/usr/bin/env bash
# Build JCaptcha 2.0 từ source + thư viện phụ, rồi compile code lab. Cần JDK 17+ và git.
# JCaptcha 2.0 không có trên Maven Central nên build từ source.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p deps
clone() { [ -d "deps/$1" ] || git clone -q --depth 1 ${3:+--branch "$3"} "$2" "deps/$1"; }
clone jcaptcha    https://github.com/svn2github/jcaptcha.git
clone slf4j       https://github.com/qos-ch/slf4j.git                  v_1.7.36
clone collections https://github.com/apache/commons-collections.git    collections-3.2.2
clone jhlabs      https://github.com/ajmas/JH-Labs-Java-Image-Filters.git

SP=deps/jcaptcha/jcaptcha/src/main/java:deps/jcaptcha/api/src/main/java
SP=$SP:deps/slf4j/slf4j-nop/src/main/java:deps/slf4j/slf4j-api/src/main/java
SP=$SP:deps/collections/src/java:deps/jhlabs/src

rm -rf out && mkdir -p out/web
# -sourcepath: javac chỉ compile những class thư viện mà code lab thực sự dùng
javac -nowarn -encoding UTF-8 -d out -sourcepath "$SP" src/demo/*.java
cp -r deps/jcaptcha/jcaptcha/src/main/resources/* out/   # .properties JCaptcha cần lúc chạy
cp web/index.html out/web/
echo "OK."
echo "  Chạy lab:      DEBUG=1 java -cp out demo.Server        -> http://localhost:8080"
echo "  Sinh ảnh mẫu:  java -cp out demo.Samples 10"
echo "  Test API:      ./tests/api_test.sh http://localhost:8080   (cần server chạy với DEBUG=1)"
