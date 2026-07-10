package com.app.netcut;

public final class ShellUtils {

    public static String shQ(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}