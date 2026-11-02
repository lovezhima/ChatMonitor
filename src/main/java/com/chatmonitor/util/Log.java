package com.chatmonitor.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {}

    public static void info(String msg) {
        System.out.printf("[%s] INFO  %s%n", LocalDateTime.now().format(FMT), msg);
    }

    public static void warn(String msg) {
        System.out.printf("[%s] WARN  %s%n", LocalDateTime.now().format(FMT), msg);
    }

    public static void error(String msg) {
        System.err.printf("[%s] ERROR %s%n", LocalDateTime.now().format(FMT), msg);
    }
}
