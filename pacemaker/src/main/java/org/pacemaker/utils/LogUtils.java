package org.pacemaker.utils;


import org.slf4j.Logger;

public class LogUtils {

    LogUtils() {
    }

    public static <T> T peekAndInfoLog(T x, Logger log, String message, Object... params) {
        log.info(message, params);
        return x;
    }

    public static <T> T peekAndDebugLog(T x, Logger log, String message, Object... params) {
        log.debug(message, params);
        return x;
    }

    public static <T> T peekAndTraceLog(T x, Logger log, String message, Object... params) {
        log.trace(message, params);
        return x;
    }
}
