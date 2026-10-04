#!/usr/bin/env bash
# AdSkip 一键构建脚本（无需 Android Studio / Gradle）
# 工具链：aapt2 + javac + d8 + zipalign + apksigner
set -euo pipefail

TOOLCHAIN="/c/Users/25193/android-build-tools"
BT="$TOOLCHAIN/build-tools/34.0.0"
PLAT="$TOOLCHAIN/platforms/android-36/android.jar"
JDK="$TOOLCHAIN/jdk/jdk-17.0.20.1+1/bin"
PROJ="$(cd "$(dirname "$0")" && pwd)"

# Windows 原生工具需要 C:/ 风格路径；bash 的 PATH 需要 /c/ 风格
BT_W="$(cygpath -m "$BT")"
PLAT_W="$(cygpath -m "$PLAT")"
export PATH="$JDK:$PATH"

cd "$PROJ"
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 编译资源..."
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/6] aapt2 链接资源 + 生成 R.java..."
"$BT/aapt2.exe" link -o build/app-unsigned.apk \
    -I "$PLAT_W" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 36 --target-sdk-version 36 \
    --java build/gen \
    build/res.zip

echo "[3/6] javac 编译 Java 源码..."
find build/gen src -name "*.java" > build/sources.txt
javac --release 8 -encoding UTF-8 -classpath "$PLAT_W" \
    -d build/classes @build/sources.txt

echo "[4/6] d8 转换 dex..."
find build/classes -name "*.class" > build/classlist.txt
"$BT/d8.bat" --release --lib "$PLAT_W" --min-api 31 --output build/dex $(cat build/classlist.txt)
test -f build/dex/classes.dex

echo "[5/6] 合并 dex + zipalign..."
(cd build/dex && jar -uf ../app-unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "[6/6] 签名..."
if [ ! -f keystore.jks ]; then
    keytool -genkeypair -keystore keystore.jks -alias adskip \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass adskip2024 -keypass adskip2024 \
        -dname "CN=AdSkip, OU=Personal, O=Local, C=CN"
    echo "  （首次运行已生成本地签名密钥 keystore.jks）"
fi
"$BT/apksigner.bat" sign --ks keystore.jks --ks-key-alias adskip \
    --ks-pass pass:adskip2024 --key-pass pass:adskip2024 \
    --out ReadLock.apk build/app-aligned.apk

"$BT/apksigner.bat" verify --print-certs ReadLock.apk | head -3
echo ""
echo "=== 构建成功：$PROJ/ReadLock.apk ==="
