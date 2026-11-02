#!/bin/bash
# 编译 ocr_helper（Swift）与 Java 主程序，产物输出到 bin/
# Swift 二进制固定 targeting macOS 13.0，避免在旧系统上因缺失新 Swift 运行时库而无法启动
set -e
cd "$(dirname "$0")"

MACOS_TARGET=${MACOS_TARGET:-macos13.0}
ARCH=$(uname -m)

echo "==> 编译 ocr_helper (Swift, target ${ARCH}-apple-${MACOS_TARGET})"
swiftc -O -target "$ARCH-apple-$MACOS_TARGET" scripts/OcrHelper.swift -o bin/ocr_helper

echo "==> 编译 Java 主程序"
rm -rf bin/classes
mkdir -p bin/classes
javac -encoding UTF-8 -d bin/classes $(find src/main/java -name '*.java')

echo "==> 构建完成"
