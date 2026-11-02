package com.chatmonitor.core;

import com.chatmonitor.util.Log;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.util.concurrent.ThreadLocalRandom;

/** 拟人化鼠标：贝塞尔轨迹移动、坐标抖动、变速滚动与随机停顿；用户介入时中止。 */
public final class HumanoidMouse {

    /** 执行拟人操作期间检测到真人移动鼠标时抛出，中止本轮任务。 */
    public static final class UserActiveException extends RuntimeException {
        public UserActiveException() {
            super("检测到用户鼠标操作，让出控制权");
        }
    }

    private final Robot robot;
    private final MouseActivityMonitor monitor;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private volatile long taskStartedAt = 0;
    /** 滚动方向系数：自然滚动=1，传统模式=-1，保证 scrollDown 始终是"往下拉滚动条看最新"。 */
    private volatile int scrollCoeff = 1;

    public HumanoidMouse(MouseActivityMonitor monitor) throws AWTException {
        this.monitor = monitor;
        this.robot = new Robot();
        robot.setAutoDelay(0);
        refreshScrollDirection();
    }

    /** 读取 macOS 滚动方式设置（自然滚动/传统模式），自动适配滚动方向。设置变化时打印日志。 */
    public void refreshScrollDirection() {
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "com.apple.swipescrolldirection").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            int coeff = ("1".equals(out) || "true".equalsIgnoreCase(out)) ? 1 : -1;
            if (coeff != scrollCoeff) {
                scrollCoeff = coeff;
                Log.info("滚动方向设置: " + (coeff == 1
                        ? "自然滚动（已适配：往下拉看最新）"
                        : "传统模式（已自动反转）"));
            }
        } catch (Exception e) {
            Log.warn("读取滚动设置失败，使用默认方向: " + e.getMessage());
        }
    }

    /** 向下滚动一格 = 往下拉滚动条（向最新消息）。 */
    private void scrollDownOne() {
        robot.mouseWheel(scrollCoeff);
    }

    /** 向上滚动一格 = 往上拉滚动条（向历史消息）。 */
    private void scrollUpOne() {
        robot.mouseWheel(-scrollCoeff);
    }

    /** 记录本轮任务开始时刻，用于判定任务期间用户的介入。 */
    public void setTaskStart(long millis) {
        this.taskStartedAt = millis;
    }

    /** 任务期间检测到真人输入则抛出 UserActiveException。 */
    public void checkAbort() {
        if (taskStartedAt > 0 && monitor.humanInputSince(taskStartedAt)) {
            throw new UserActiveException();
        }
    }

    /** 沿贝塞尔曲线移动到目标点，终点加少量抖动。 */
    public void moveTo(int targetX, int targetY) {
        Point start = MouseInfo.getPointerInfo().getLocation();
        int endX = targetX + rnd.nextInt(-2, 3);
        int endY = targetY + rnd.nextInt(-2, 3);

        // 两个随机控制点，制造自然弯曲
        int cx1 = start.x + (endX - start.x) / 3 + rnd.nextInt(-60, 61);
        int cy1 = start.y + (endY - start.y) / 3 + rnd.nextInt(-60, 61);
        int cx2 = start.x + 2 * (endX - start.x) / 3 + rnd.nextInt(-60, 61);
        int cy2 = start.y + 2 * (endY - start.y) / 3 + rnd.nextInt(-60, 61);

        int steps = rnd.nextInt(20, 40);
        for (int i = 1; i <= steps; i++) {
            checkAbort();
            double t = (double) i / steps;
            double u = 1 - t;
            double x = u * u * u * start.x + 3 * u * u * t * cx1 + 3 * u * t * t * cx2 + t * t * t * endX;
            double y = u * u * u * start.y + 3 * u * u * t * cy1 + 3 * u * t * t * cy2 + t * t * t * endY;
            robot.mouseMove((int) Math.round(x), (int) Math.round(y));
            monitor.setProgramLocation((int) Math.round(x), (int) Math.round(y));
            sleep(rnd.nextInt(4, 14));
        }
    }

    /** 拟人点击：移动后短暂停顿，按下/释放间隔随机。 */
    public void click(int x, int y) {
        moveTo(x, y);
        monitor.beginProgramControl();
        try {
            checkAbort();
            sleep(rnd.nextInt(80, 250));
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            sleep(rnd.nextInt(40, 120));
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        } finally {
            monitor.endProgramControl();
        }
    }

    /** 按 Esc 关闭可能展开的面板（个人信息面板、选中态等）。 */
    public void pressEscape() {
        monitor.beginProgramControl();
        try {
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
            sleep(rnd.nextInt(40, 90));
            robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
        } finally {
            monitor.endProgramControl();
        }
    }

    /** 模拟人阅读式滚动：分段变速滚动，混合翻阅历史（向上）与看新消息（向下），段落间随机停顿。 */
    public void humanScroll(int totalSeconds) {
        long deadline = System.currentTimeMillis() + totalSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            checkAbort();
            monitor.beginProgramControl();
            try {
                // 70% 概率向上翻阅历史，30% 向下看新消息
                boolean up = rnd.nextInt(10) < 7;
                // 每段滚 2~6 格
                int ticks = rnd.nextInt(2, 7);
                for (int i = 0; i < ticks; i++) {
                    checkAbort();
                    if (up) {
                        scrollUpOne();
                    } else {
                        scrollDownOne();
                    }
                    sleep(rnd.nextInt(60, 200));
                }
                // 10% 概率反向回一小段（人阅读时的回看行为）
                if (rnd.nextInt(10) == 0) {
                    for (int i = 0; i < rnd.nextInt(2, 5); i++) {
                        if (up) {
                            scrollDownOne();
                        } else {
                            scrollUpOne();
                        }
                    }
                    sleep(rnd.nextInt(200, 500));
                }
            } finally {
                monitor.endProgramControl();
            }
            // 阅读停顿（此时若用户动鼠标，下一段 checkAbort 会中止）
            sleep(rnd.nextInt(300, 1800));
        }
    }

    /** 快速滚回聊天底部（最新消息），清除未读状态——模拟人看完历史拉回最新。 */
    public void returnToBottom(int totalTicks) {
        // 分 8 段快速向下滚，速度比浏览滚动快
        int segs = 8;
        int perSeg = Math.max(1, totalTicks / segs);
        for (int seg = 0; seg < segs; seg++) {
            checkAbort();
            monitor.beginProgramControl();
            try {
                for (int i = 0; i < perSeg; i++) {
                    checkAbort();
                    scrollDownOne();
                    sleep(rnd.nextInt(20, 50));
                }
            } finally {
                monitor.endProgramControl();
            }
            checkAbort();
            sleep(rnd.nextInt(150, 400));
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
