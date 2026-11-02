package com.chatmonitor.core;

import com.chatmonitor.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 调用 swift 编译的 ocr_helper 二进制，输出为 "|" 分隔的行协议。 */
public final class HelperClient {

    /** 窗口信息，坐标为屏幕点坐标（与 Robot 坐标系一致）。 */
    public record Window(int windowId, int pid, int x, int y, int width, int height, String owner, String title) {}

    /** OCR 文本块，坐标为图片像素。 */
    public record OcrText(int x, int y, int width, int height, String text) {}

    private final String helperPath;

    public HelperClient(String helperPath) {
        this.helperPath = helperPath;
    }

    /** 列出所有在屏窗口（含 pid/owner，供 Java 端按进程过滤）。 */
    public List<Window> listWindows() throws IOException, InterruptedException {
        List<String> lines = run("list");
        List<Window> windows = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\\|", 8);
            if (f.length < 8) continue;
            try {
                windows.add(new Window(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]), f[6], f[7]));
            } catch (NumberFormatException ignored) {
            }
        }
        return windows;
    }

    /** 应用当前聚焦窗口 bounds（屏幕点坐标），拿不到返回 null。 */
    public Rect frontWindow(long pid) throws IOException, InterruptedException {
        List<String> lines = run("frontwindow", String.valueOf(pid));
        if (lines.isEmpty()) return null;
        String[] f = lines.get(0).trim().split("\\|", 4);
        if (f.length < 4) return null;
        try {
            return new Rect(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]), Integer.parseInt(f[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 最小化应用当前聚焦窗口。 */
    public void minimize(long pid) throws IOException, InterruptedException {
        run("minimize", String.valueOf(pid));
    }

    /** 屏幕点坐标矩形。 */
    public record Rect(int x, int y, int width, int height) {}

    public List<OcrText> ocr(String pngPath) throws IOException, InterruptedException {
        List<String> lines = run("ocr", pngPath);
        List<OcrText> texts = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\\|", 5);
            if (f.length < 5) continue;
            try {
                texts.add(new OcrText(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4]));
            } catch (NumberFormatException ignored) {
            }
        }
        return texts;
    }

    public boolean isLocked() throws IOException, InterruptedException {
        List<String> lines = run("locked");
        return !lines.isEmpty() && "true".equals(lines.get(0).trim());
    }

    public boolean hasAccessibility() throws IOException, InterruptedException {
        List<String> lines = run("accessibility");
        return !lines.isEmpty() && "true".equals(lines.get(0).trim());
    }

    public boolean hasScreenCapture() throws IOException, InterruptedException {
        List<String> lines = run("screenaccess");
        return !lines.isEmpty() && "true".equals(lines.get(0).trim());
    }

    /** 距上次键盘/鼠标输入的系统空闲秒数。 */
    public long idleSeconds() throws IOException, InterruptedException {
        List<String> lines = run("idleseconds");
        try {
            return lines.isEmpty() ? 0 : Long.parseLong(lines.get(0).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> run(String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = helperPath;
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.add(line);
            }
        }
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ocr_helper 超时: " + String.join(" ", args));
        }
        if (process.exitValue() != 0) {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    Log.error("ocr_helper: " + line);
                }
            }
            throw new IOException("ocr_helper 退出码 " + process.exitValue());
        }
        return out;
    }
}
