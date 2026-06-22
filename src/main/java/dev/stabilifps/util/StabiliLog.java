package dev.stabilifps.util;

import dev.stabilifps.StabiliFPS;

/**
 * Small logging helper so subsystems do not all hold their own logger handle.
 */
public final class StabiliLog {
    private StabiliLog() {}

    public static void info(String fmt, Object... args) {
        StabiliFPS.LOGGER.info(format(fmt, args));
    }

    public static void warn(String fmt, Object... args) {
        StabiliFPS.LOGGER.warn(format(fmt, args));
    }

    public static void error(String fmt, Object... args) {
        StabiliFPS.LOGGER.error(format(fmt, args));
    }

    private static String format(String fmt, Object... args) {
        if (args == null || args.length == 0) return fmt;
        try {
            return String.format(fmt, args);
        } catch (Exception e) {
            return fmt;
        }
    }
}
