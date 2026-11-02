package com.chatmonitor;

import com.chatmonitor.config.ConfigManager;
import com.chatmonitor.config.ConfigManager.Config;
import com.chatmonitor.core.HelperClient;
import com.chatmonitor.core.HumanoidMouse;
import com.chatmonitor.core.MouseActivityMonitor;
import com.chatmonitor.core.ProcessGuard;
import com.chatmonitor.core.TaskExecutor;
import com.chatmonitor.core.TaskExecutor.TaskResult;
import com.chatmonitor.util.Log;

import java.awt.AWTException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {

    private static final long SHORT_WAIT_MS = 30_000;

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args.length > 0 ? args[0] : "config.properties").toAbsolutePath();
        ConfigManager configManager = new ConfigManager(configFile);
        HelperClient helper = new HelperClient(configManager.get().helperPath());
        MouseActivityMonitor monitor = new MouseActivityMonitor(configManager.get().helperPath());
        monitor.start();
        HumanoidMouse mouse;
        try {
            mouse = new HumanoidMouse(monitor);
        } catch (AWTException e) {
            Log.error("无法创建 Robot（无图形会话）: " + e.getMessage());
            return;
        }
        ProcessGuard guard = new ProcessGuard();
        TaskExecutor executor = new TaskExecutor(helper, mouse);

        checkPermissions(helper);
        Log.info("ChatMonitor 启动，配置文件: " + configFile);

        AtomicBoolean running = new AtomicBoolean(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            Log.info("收到退出信号，正在停止...");
        }));

        long totalRuns = 0, successRuns = 0, abortedRuns = 0;
        long lastHintedRemain = -1;
        while (running.get()) {
            configManager.reloadIfChanged();
            Config c = configManager.get();
            if (c == null) {
                sleep(SHORT_WAIT_MS);
                continue;
            }

            try {
                if (helper.isLocked()) {
                    sleep(SHORT_WAIT_MS);
                    continue;
                }
            } catch (IOException | InterruptedException e) {
                Log.error("锁屏检测失败: " + e.getMessage());
                sleep(SHORT_WAIT_MS);
                continue;
            }

            if (!guard.isRunning(c.processName())) {
                sleep(SHORT_WAIT_MS);
                continue;
            }

            // 系统级空闲判定（键盘+鼠标+滚轮，CGEventSource 查询）：
            // 真人闲置 idleSeconds 秒才接管；有人在操作就不抢
            long sysIdle;
            try {
                sysIdle = helper.idleSeconds();
            } catch (IOException | InterruptedException e) {
                Log.error("空闲检测失败: " + e.getMessage());
                sleep(SHORT_WAIT_MS);
                continue;
            }
            if (sysIdle < c.idleSeconds() || !monitor.idleFor(c.idleSeconds() * 1000L)) {
                // 剩余秒数变化时动态打印倒计时
                long remain = c.idleSeconds() - sysIdle;
                if (remain > 0 && remain != lastHintedRemain) {
                    Log.info("等待闲置接管，还剩 " + remain + " 秒");
                }
                lastHintedRemain = remain;
                sleep(1000);
                continue;
            }
            lastHintedRemain = -1;

            long backoff = guard.backoffMillis(c);
            if (backoff > 0) {
                sleep(backoff);
                continue;
            }

            Log.info("===== 开始本轮任务 =====");
            mouse.setTaskStart(System.currentTimeMillis());
            TaskResult result = executor.runOnce(c);
            switch (result) {
                case SUCCESS -> {
                    guard.record(true);
                    totalRuns++;
                    successRuns++;
                }
                case FAILED -> {
                    guard.record(false);
                    totalRuns++;
                }
                case ABORTED -> abortedRuns++;
            }
            Log.info("===== 本轮结束（成功 " + successRuns + "/" + totalRuns + "，中止 " + abortedRuns + "）=====");

            // 无固定间隔：回到循环顶部，等闲置 idleSeconds 后自动切换下一个目标
        }
        Log.info("已退出。共执行 " + totalRuns + " 轮，成功 " + successRuns + " 轮，中止 " + abortedRuns + " 轮");
    }

    /** 启动时检查两项权限，缺失时给出授权指引（仅首次自动打开设置面板）。 */
    private static void checkPermissions(HelperClient helper) {
        try {
            if (!helper.hasAccessibility()) {
                Log.warn("缺少「辅助功能」权限，鼠标/滚动操作将被系统拦截！");
                Log.warn("请到 系统设置 → 隐私与安全性 → 辅助功能，勾选你的终端应用");
                openAccessibilityPane();
            } else {
                Log.info("辅助功能权限: OK");
            }
            if (!helper.hasScreenCapture()) {
                Log.warn("缺少「屏幕录制」权限，无法截图识别文字！");
                Log.warn("请到 系统设置 → 隐私与安全性 → 屏幕录制，勾选你的终端应用");
                openScreenCapturePane();
            } else {
                Log.info("屏幕录制权限: OK");
            }
        } catch (IOException | InterruptedException e) {
            Log.warn("权限检测失败: " + e.getMessage());
        }
    }

    private static void openAccessibilityPane() throws IOException {
        new ProcessBuilder("open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility").start();
    }

    private static void openScreenCapturePane() throws IOException {
        new ProcessBuilder("open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture").start();
    }

    private static void sleep(long millis) {
        HumanoidMouse.sleep(millis);
    }
}
