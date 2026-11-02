package com.chatmonitor.core;

import com.chatmonitor.config.ConfigManager.Config;
import com.chatmonitor.util.Log;

import java.util.concurrent.ThreadLocalRandom;

/** 目标进程运行检测 + 连续失败退避自愈。 */
public final class ProcessGuard {

    private int consecutiveFailures = 0;
    private int consecutiveMisses = 0;
    private long missedSince = 0;

    /** 目标进程是否在运行（进程名来自当前配置，支持热切换）。 */
    public boolean isRunning(String processName) {
        boolean found = ProcessHandle.allProcesses()
                .anyMatch(ph -> ph.info().command().orElse("").toLowerCase().endsWith("/" + processName.toLowerCase()));
        if (!found) {
            if (consecutiveMisses == 0) {
                missedSince = System.currentTimeMillis();
            }
            consecutiveMisses++;
            long minutes = (System.currentTimeMillis() - missedSince) / 60000L;
            if (consecutiveMisses == 1 || consecutiveMisses % 30 == 0) {
                Log.warn("未检测到进程 [" + processName + "]，已持续 " + minutes + " 分钟");
            }
        } else {
            if (consecutiveMisses > 0) {
                Log.info("进程 [" + processName + "] 已恢复运行");
            }
            consecutiveMisses = 0;
        }
        return found;
    }

    /** 记录单次任务结果，连续失败达到阈值时给出退避时长。 */
    public void record(boolean success) {
        if (success) {
            if (consecutiveFailures >= 3) {
                Log.info("任务恢复正常，重置失败计数");
            }
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;
            Log.warn("任务失败，连续失败 " + consecutiveFailures + " 次");
        }
    }

    /** 连续失败 >= maxRetries 时，返回 [backoffMin, backoffMax] 秒的随机退避毫秒数，否则返回 0。 */
    public long backoffMillis(Config c) {
        if (consecutiveFailures < c.maxRetries()) {
            return 0;
        }
        int seconds = ThreadLocalRandom.current().nextInt(c.backoffMinSeconds(), c.backoffMaxSeconds() + 1);
        Log.warn("连续失败达到 " + c.maxRetries() + " 次，退避 " + seconds + " 秒");
        return seconds * 1000L;
    }
}
