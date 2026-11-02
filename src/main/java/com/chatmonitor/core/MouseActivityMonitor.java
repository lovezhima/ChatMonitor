package com.chatmonitor.core;

import com.chatmonitor.util.Log;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 监控真人输入，区分程序自控与真人操作：
 * - 鼠标移动：轮询位置，程序移动期间以程序目标位置为参照，偏离超过阈值视为真人移动
 * - 按键/滚轮/点击：helper 的 watchkeys 事件监听（tap 回调），程序自控滚动/点击期间过滤
 */
public final class MouseActivityMonitor {

    private static final int SAMPLE_MS = 200;
    private static final double USER_MOVE_THRESHOLD = 8;

    private final String helperPath;
    private volatile boolean programControlled = false;
    private volatile Point programExpected = null;
    private volatile long lastHumanInput = System.currentTimeMillis();
    private volatile boolean stopped = false;
    private Process inputWatcher;
    private final Thread watcher;

    public MouseActivityMonitor(String helperPath) {
        this.helperPath = helperPath;
        watcher = new Thread(this::watchMouse, "mouse-watcher");
        watcher.setDaemon(true);
    }

    public void start() {
        watcher.start();
        startInputWatcher();
    }

    public void stop() {
        stopped = true;
        if (inputWatcher != null) {
            inputWatcher.destroyForcibly();
        }
    }

    private void watchMouse() {
        Point prev = null;
        while (!stopped) {
            try {
                PointerInfo pi = MouseInfo.getPointerInfo();
                if (pi != null) {
                    Point cur = pi.getLocation();
                    if (prev != null && cur.distance(prev) > 1) {
                        Point expected = programExpected;
                        boolean programMove = expected != null && cur.distance(expected) <= USER_MOVE_THRESHOLD;
                        if (!programMove) {
                            lastHumanInput = System.currentTimeMillis();
                        }
                    }
                    prev = cur;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** 启动 helper 的 watchkeys 子进程监听按键/滚轮/点击；失败则退化为仅鼠标移动检测。 */
    private void startInputWatcher() {
        try {
            inputWatcher = new ProcessBuilder(helperPath, "watchkeys")
                    .redirectErrorStream(true)
                    .start();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(inputWatcher.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String event = line.trim();
                        // 按键一定是真人（程序不敲键盘）；滚轮/点击在程序自控期间过滤
                        if ("k".equals(event) || ((("s".equals(event) || "c".equals(event))) && !programControlled)) {
                            lastHumanInput = System.currentTimeMillis();
                        }
                    }
                } catch (IOException ignored) {
                }
                if (!stopped) {
                    Log.warn("输入监听已退出，退化为仅鼠标移动检测（如需按键/滚轮检测，请授权「输入监控」权限）");
                }
            }, "input-watcher");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            Log.warn("输入监听启动失败，退化为仅鼠标移动检测: " + e.getMessage());
        }
    }

    /** 真人已闲置至少 millis 毫秒（无移动、按键、滚轮、点击）。 */
    public boolean idleFor(long millis) {
        return System.currentTimeMillis() - lastHumanInput >= millis;
    }

    /** 自 t 时刻以来真人是否有过输入。 */
    public boolean humanInputSince(long t) {
        return lastHumanInput > t;
    }

    /** 记录程序控制的鼠标落点，用于区分真人移动与程序移动。 */
    public void setProgramLocation(int x, int y) {
        programExpected = new Point(x, y);
    }

    /** 程序正在执行滚动/点击期间，过滤其自产事件。 */
    public void beginProgramControl() {
        programControlled = true;
    }

    public void endProgramControl() {
        programControlled = false;
    }
}
