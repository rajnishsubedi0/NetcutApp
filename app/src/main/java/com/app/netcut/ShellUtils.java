package com.app.netcut;

public final class ShellUtils {

    private ShellUtils() { /* utility class */ }

    public static String shQ(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}