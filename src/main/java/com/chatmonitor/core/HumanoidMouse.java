package com.chatmonitor.core;

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

    public HumanoidMouse(MouseActivityMonitor monitor) throws AWTException {
        this.monitor = monitor;
        this.robot = new Robot();
        robot.setAutoDelay(0);
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

    /** 模拟人阅读式滚动：分段变速滚动，段落间随机停顿（停顿期间不占控制权），偶尔小幅回滚。 */
    public void humanScroll(int totalSeconds) {
        long deadline = System.currentTimeMillis() + totalSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            checkAbort();
            monitor.beginProgramControl();
            try {
                // 每段滚 2~6 格
                int ticks = rnd.nextInt(2, 7);
                for (int i = 0; i < ticks; i++) {
                    checkAbort();
                    robot.mouseWheel(1);
                    sleep(rnd.nextInt(60, 200));
                }
                // 10% 概率往回翻一小段（人阅读时的回看行为）
                if (rnd.nextInt(10) == 0) {
                    robot.mouseWheel(-rnd.nextInt(2, 5));
                    sleep(rnd.nextInt(200, 500));
                }
            } finally {
                monitor.endProgramControl();
            }
            // 阅读停顿（此时若用户动鼠标，下一段 checkAbort 会中止）
            sleep(rnd.nextInt(300, 1800));
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
