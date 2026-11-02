package com.chatmonitor.config;

import com.chatmonitor.util.Log;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 基于 properties 文件的配置，按 mtime 轮询热加载。 */
public final class ConfigManager {

    public record Config(
            String processName,
            String targets,
            String personKeyword,
            long idleSeconds,
            long switchAlertSeconds,
            int scrollMinSeconds,
            int scrollMaxSeconds,
            int scrollBackTicks,
            int maxRetries,
            int backoffMinSeconds,
            int backoffMaxSeconds,
            String helperPath) {

        /** 逗号分隔的多个目标（群聊或联系人）。 */
        public String[] targetList() {
            String[] parts = targets.split(",");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            return parts;
        }
    }

    private final Path file;
    private long lastMtime = -1;
    private volatile Config config;

    public ConfigManager(Path file) {
        this.file = file;
        this.config = load();
    }

    public Config get() {
        return config;
    }

    /** 文件 mtime 变化时重载。 */
    public void reloadIfChanged() {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime != lastMtime) {
                lastMtime = mtime;
                Config loaded = load();
                if (loaded != null) {
                    config = loaded;
                    Log.info("配置已热加载: " + file);
                }
            }
        } catch (IOException e) {
            Log.warn("读取配置失败: " + e.getMessage());
        }
    }

    private Config load() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException e) {
            Log.error("加载配置文件失败: " + e.getMessage());
            return null;
        }
        try {
            String targets = p.getProperty("targets", "").trim();
            if (targets.isEmpty()) {
                targets = p.getProperty("groupKeyword", "").trim(); // 兼容旧配置字段
            }
            return new Config(
                    p.getProperty("processName", "Telegram").trim(),
                    targets,
                    p.getProperty("personKeyword", "").trim(),
                    Long.parseLong(p.getProperty("idleSeconds", "10").trim()),
                    Long.parseLong(p.getProperty("switchAlertSeconds", "600").trim()),
                    Integer.parseInt(p.getProperty("scrollMinSeconds", "8").trim()),
                    Integer.parseInt(p.getProperty("scrollMaxSeconds", "15").trim()),
                    Integer.parseInt(p.getProperty("scrollBackTicks", "160").trim()),
                    Integer.parseInt(p.getProperty("maxRetries", "3").trim()),
                    Integer.parseInt(p.getProperty("backoffMinSeconds", "60").trim()),
                    Integer.parseInt(p.getProperty("backoffMaxSeconds", "300").trim()),
                    p.getProperty("helperPath", "bin/ocr_helper").trim());
        } catch (NumberFormatException e) {
            Log.error("配置数值解析失败: " + e.getMessage() + "，使用上次配置");
            return null;
        }
    }
}
