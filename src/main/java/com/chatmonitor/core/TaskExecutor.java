package com.chatmonitor.core;

import com.chatmonitor.config.ConfigManager.Config;
import com.chatmonitor.core.HumanoidMouse.UserActiveException;
import com.chatmonitor.util.Log;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 单次任务：激活应用 → 全屏截图 OCR → 随机选一个目标（群/联系人）点击 → 可选点人 → 拟人滚动。 */
public final class TaskExecutor {

    public enum TaskResult { SUCCESS, FAILED, ABORTED }

    private final HelperClient helper;
    private final HumanoidMouse mouse;
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();
    private int nextTargetIndex = 0;

    public TaskExecutor(HelperClient helper, HumanoidMouse mouse) {
        this.helper = helper;
        this.mouse = mouse;
    }

    /** 截图结果：OCR 文本 + 图片像素尺寸。 */
    private record Shot(List<HelperClient.OcrText> texts, int width, int height) {}

    /** 执行一次完整任务。ABORTED 表示用户介入中止，不计失败。 */
    public TaskResult runOnce(Config c) {
        try {
            // 每轮刷新滚动方向适配（用户可能刚切换了自然滚动/传统模式）
            mouse.refreshScrollDirection();
            // 各阶段前检查真人输入：用户在任务间隙操作则立即中止，不抢焦点
            mouse.checkAbort();
            if (!activateApp(c.processName())) {
                Log.warn("激活应用失败: " + c.processName());
                return TaskResult.FAILED;
            }
            sleep(rnd.nextInt(1000, 2000));

            // 关闭可能展开的面板（如个人信息面板），避免遮挡界面干扰 OCR 判断
            mouse.checkAbort();
            mouse.pressEscape();
            sleep(rnd.nextInt(300, 600));

            // 按顺序轮流切换下一个目标（round-robin），在屏幕左侧 35% 区域找，取最上方一条
            String[] targets = c.targetList();
            String target = targets[nextTargetIndex % targets.length];
            nextTargetIndex++;

            // 目标应用窗口 bounds（屏幕点坐标），用于把鼠标定位到其消息区
            HelperClient.Window chatWin = null;
            try {
                chatWin = findChatWindow(c);
            } catch (Exception e) {
                Log.warn("获取窗口位置失败，退回全屏定位: " + e.getMessage());
            }
            if (chatWin == null) {
                Log.warn("未找到窗口 [" + c.processName() + "]，退回全屏定位");
            } else {
                Log.info("窗口 [" + chatWin.owner() + "] @(" + chatWin.x() + "," + chatWin.y() + ") "
                        + chatWin.width() + "x" + chatWin.height());
            }

            mouse.checkAbort();
            // 全屏截图 OCR，像素坐标按比例换算成屏幕逻辑坐标
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            Shot shot = captureFullScreen();
            if (shot.width() == 0 || shot.texts().isEmpty()) {
                Log.warn("OCR 未识别到任何文字（检查屏幕录制权限）");
                return TaskResult.FAILED;
            }
            double scaleX = screen.width / (double) shot.width();
            double scaleY = screen.height / (double) shot.height();

            // 标题栏检测：当前已打开该聊天则跳过点击列表
            // （否则可能点到消息内容上出现的同名文字，弹出框扰乱界面）
            String title = titleText(shot);
            if (title != null && normalize(title).contains(normalize(target))) {
                Log.info("已在该聊天 [" + target + "]，跳过点击");
            } else {
                List<HelperClient.OcrText> targetFound = filterByRegion(shot.texts(), target,
                        0, 0.35 * shot.width(), 0);
                if (targetFound.isEmpty()) {
                    Log.warn("未找到目标 [" + target + "]，跳过本轮");
                    return TaskResult.FAILED;
                }
                HelperClient.OcrText targetText = targetFound.get(0);
                Log.info("点击目标 [" + targetText.text() + "] @(" + targetText.x() + "," + targetText.y() + ")");
                mouse.checkAbort();
                mouse.click((int) Math.round(centerX(targetText) * scaleX),
                        (int) Math.round(centerY(targetText) * scaleY));
                sleep(rnd.nextInt(1500, 2500));
            }

            // 聊天可能停在上次离开的历史位置，先滚到最底部的最新消息
            mouse.checkAbort();
            Point msgArea = messageArea(chatWin, screen);
            Log.info("消息区落点 @(" + msgArea.x + "," + msgArea.y + ")");
            mouse.moveTo(msgArea.x, msgArea.y);
            sleep(rnd.nextInt(200, 500));
            Log.info("滚到最新消息");
            mouse.returnToBottom(c.scrollBackTicks());
            sleep(rnd.nextInt(500, 1000));

            // 点击后重截，在右侧消息区（x >= 屏宽 30%，y >= 屏高 12%）找目标人的消息，取最下方一条。
            // y 下限排除聊天标题栏的联系人名字（点击会展开个人信息面板遮挡消息区）
            if (!c.personKeyword().isEmpty()) {
                mouse.checkAbort();
                shot = captureFullScreen();
                List<HelperClient.OcrText> found = filterByRegion(shot.texts(), c.personKeyword(),
                        0.3 * shot.width(), shot.width(), 0.12 * shot.height());
                if (found.isEmpty()) {
                    Log.warn("未找到目标人 [" + c.personKeyword() + "] 的消息，仍继续滚动");
                } else {
                    HelperClient.OcrText person = found.stream()
                            .max(Comparator.comparingInt(HelperClient.OcrText::y)).orElse(null);
                    // 只悬停不点击：点击消息内容会弹出操作框，扰乱界面
                    Log.info("悬停消息 [" + person.text() + "] @(" + person.x() + "," + person.y() + ")");
                    mouse.checkAbort();
                    mouse.moveTo((int) Math.round(centerX(person) * scaleX),
                            (int) Math.round(centerY(person) * scaleY));
                    sleep(rnd.nextInt(500, 1000));
                }
            }

            // 滚轮作用于鼠标悬停区域：先把鼠标拟人移到右侧消息区，否则滚的是联系人列表
            mouse.checkAbort();
            mouse.moveTo(msgArea.x, msgArea.y);
            sleep(rnd.nextInt(200, 500));

            mouse.checkAbort();
            int scrollSeconds = rnd.nextInt(c.scrollMinSeconds(), c.scrollMaxSeconds() + 1);
            Log.info("拟人滚动 " + scrollSeconds + " 秒");
            mouse.humanScroll(scrollSeconds);

            // 滚动会翻到历史位置，结束后拉回底部清除未读（红点/数量）
            Log.info("回到底部清除未读");
            mouse.returnToBottom(c.scrollBackTicks());
            return TaskResult.SUCCESS;
        } catch (UserActiveException e) {
            Log.info(e.getMessage() + "，本轮中止");
            return TaskResult.ABORTED;
        } catch (IOException | InterruptedException e) {
            Log.error("任务执行异常: " + e.getMessage());
            return TaskResult.FAILED;
        }
    }

    /** 按进程路径找 .app 路径，如 /Applications/微信.app。 */
    private String findAppPath(String processName) {
        return ProcessHandle.allProcesses()
                .map(ph -> ph.info().command().orElse(""))
                .filter(cmd -> cmd.toLowerCase().endsWith("/" + processName.toLowerCase()))
                .filter(cmd -> cmd.contains(".app/"))
                .map(cmd -> cmd.substring(0, cmd.indexOf(".app/") + 4))
                .findFirst()
                .orElse(null);
    }

    /** 目标应用的进程 PID 集合（进程 command 末尾匹配进程名）。 */
    private java.util.Set<Long> targetPids(Config c) {
        return ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command().orElse("").toLowerCase()
                        .endsWith("/" + c.processName().toLowerCase()))
                .map(ProcessHandle::pid)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 目标应用在屏幕上的聊天窗口（点坐标）：优先应用当前聚焦窗口，退回按 PID 取面积最大；找不到返回 null。 */
    private HelperClient.Window findChatWindow(Config c) throws IOException, InterruptedException {
        // 按进程 PID 匹配窗口（窗口 owner 显示名可能是本地化名称，如微信显示"微信"）
        java.util.Set<Long> pids = targetPids(c);
        // 应用刚被激活，聚焦窗口就是聊天主窗口（准确排除图片预览等浮窗）
        for (long pid : pids) {
            HelperClient.Rect r = helper.frontWindow(pid);
            if (r != null && r.width() > 50 && r.height() > 50) {
                return new HelperClient.Window(0, (int) pid, r.x(), r.y(), r.width(), r.height(), c.processName(), "");
            }
        }
        // 回退：该进程的所有在屏窗口取面积最大
        return helper.listWindows().stream()
                .filter(w -> pids.contains((long) w.pid()))
                .max(Comparator.comparingInt(w -> w.width() * w.height()))
                .orElse(null);
    }

    /** 任务完成后最小化聊天窗口（失败仅告警，不影响主流程）。 */
    public void minimizeChat(Config c) {
        try {
            for (long pid : targetPids(c)) {
                helper.minimize(pid);
                return;
            }
        } catch (Exception e) {
            Log.warn("最小化窗口失败: " + e.getMessage());
        }
    }

    /** 消息区落点：窗口内右侧 65% 宽、45% 高处；窗口未知时退回全屏比例。 */
    private static Point messageArea(HelperClient.Window win, Dimension screen) {
        if (win != null) {
            return new Point(win.x() + (int) (win.width() * 0.65),
                    win.y() + (int) (win.height() * 0.45));
        }
        return new Point((int) (screen.width * 0.65), (int) (screen.height * 0.45));
    }

    private boolean activateApp(String processName) throws IOException, InterruptedException {
        // 优先按 .app 路径激活（应用名可能是中文，如"微信"，open -a 英文名会失败）
        String appPath = findAppPath(processName);
        Process p = appPath != null
                ? new ProcessBuilder("open", appPath).start()
                : new ProcessBuilder("open", "-a", processName).start();
        return p.waitFor() == 0;
    }

    private Shot captureFullScreen() throws IOException, InterruptedException {
        String png = System.getProperty("java.io.tmpdir") + "/chatmonitor_full.png";
        new ProcessBuilder("screencapture", "-x", png).start().waitFor();
        BufferedImage img = ImageIO.read(new File(png));
        if (img == null) {
            return new Shot(List.of(), 0, 0);
        }
        return new Shot(helper.ocr(png), img.getWidth(), img.getHeight());
    }

    private List<HelperClient.OcrText> filterByRegion(List<HelperClient.OcrText> texts, String keyword,
                                                      double xMin, double xMax, double yMin) {
        String normKeyword = normalize(keyword);
        return texts.stream()
                .filter(t -> t.x() >= xMin && t.x() <= xMax)
                .filter(t -> t.y() >= yMin)
                .filter(t -> normalize(t.text()).contains(normKeyword))
                .sorted(Comparator.comparingInt(HelperClient.OcrText::y))
                .toList();
    }

    /** OCR 常把数字与汉字间加空格（如"2607 班"），匹配时忽略所有空格。 */
    private static String normalize(String s) {
        return s.replace(" ", "");
    }

    /** 提取聊天标题栏文字：屏幕顶部 12% 且右侧 25% 宽的区域，拼接所有 OCR 文本。 */
    private String titleText(Shot shot) {
        StringBuilder sb = new StringBuilder();
        for (HelperClient.OcrText t : shot.texts()) {
            if (t.y() < 0.12 * shot.height() && t.x() >= 0.25 * shot.width()) {
                sb.append(t.text());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int centerX(HelperClient.OcrText t) {
        return t.x() + t.width() / 2;
    }

    private static int centerY(HelperClient.OcrText t) {
        return t.y() + t.height() / 2;
    }

    private static void sleep(long millis) {
        HumanoidMouse.sleep(millis);
    }
}
