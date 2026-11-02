#!/bin/bash
# 启动 ChatMonitor（后台运行），日志输出到 chatmonitor.log
cd "$(dirname "$0")"
if [ ! -x bin/ocr_helper ] || [ ! -f bin/chatmonitor.jar ]; then
    echo "尚未构建，先执行 ./build.sh"
    exit 1
fi
nohup java -jar bin/chatmonitor.jar config.properties > chatmonitor.log 2>&1 &
echo "ChatMonitor 已后台启动 (PID $!)，日志: chatmonitor.log，停止: kill $!"
