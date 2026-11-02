package com.chatmonitor.core;

import com.chatmonitor.config.ConfigManager.Config;
import com.chatmonitor.core.HumanoidMouse.UserActiveException;
import com.chatmonitor.util.Log;

import javax.imageio.ImageIO;
import java.awt.Dimension;
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
            // 各阶段前检查真人输入：用户在任务间隙操作则立即中止，不抢焦点
            mouse.checkAbort();
            if (!activateApp(c.processName())) {
                Log.warn("激活应用失败: " + c.processName());
                return TaskResult.FAILED;
            }
            sleep(rnd.nextInt(1000, 2000));

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

            // 按顺序轮流切换下一个目标（round-robin），在屏幕左侧 35% 区域找，取最上方一条
            String[] targets = c.targetList();
            String target = targets[nextTargetIndex % targets.length];
            nextTargetIndex++;
            List<HelperClient.OcrText> targetFound = filterByRegion(shot.texts(), target,
                    0, 0.35 * shot.width());
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

            // 点击后重截，在右侧消息区（x >= 屏宽 30%）找目标人的消息，取最下方一条
            if (!c.personKeyword().isEmpty()) {
                mouse.checkAbort();
                shot = captureFullScreen();
                List<HelperClient.OcrText> found = filterByRegion(shot.texts(), c.personKeyword(),
                        0.3 * shot.width(), shot.width());
                if (found.isEmpty()) {
                    Log.warn("未找到目标人 [" + c.personKeyword() + "] 的消息，仍继续滚动");
                } else {
                    HelperClient.OcrText person = found.stream()
                            .max(Comparator.comparingInt(HelperClient.OcrText::y)).orElse(null);
                    Log.info("点击消息 [" + person.text() + "] @(" + person.x() + "," + person.y() + ")");
                    mouse.checkAbort();
                    mouse.click((int) Math.round(centerX(person) * scaleX),
                            (int) Math.round(centerY(person) * scaleY));
                    sleep(rnd.nextInt(500, 1000));
                }
            }

            mouse.checkAbort();
            int scrollSeconds = rnd.nextInt(c.scrollMinSeconds(), c.scrollMaxSeconds() + 1);
            Log.info("拟人滚动 " + scrollSeconds + " 秒");
            mouse.humanScroll(scrollSeconds);
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
                                                      double xMin, double xMax) {
        String normKeyword = normalize(keyword);
        return texts.stream()
                .filter(t -> t.x() >= xMin && t.x() <= xMax)
                .filter(t -> normalize(t.text()).contains(normKeyword))
                .sorted(Comparator.comparingInt(HelperClient.OcrText::y))
                .toList();
    }

    /** OCR 常把数字与汉字间加空格（如"2607 班"），匹配时忽略所有空格。 */
    private static String normalize(String s) {
        return s.replace(" ", "");
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
