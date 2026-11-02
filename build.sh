#!/bin/bash
# 编译 ocr_helper（Swift）与 Java 主程序，产物输出到 bin/
set -e
cd "$(dirname "$0")"

echo "==> 编译 ocr_helper (Swift)"
swiftc -O scripts/OcrHelper.swift -o bin/ocr_helper

echo "==> 编译 Java 主程序"
rm -rf bin/classes
mkdir -p bin/classes
javac -encoding UTF-8 -d bin/classes $(find src/main/java -name '*.java')

echo "==> 构建完成"
