package com.app.netcut;



import android.util.Log;



public final class NetcutKiller {

    private static final String TAG = "NetcutKiller";

    /** Commands that aggressively kill anything named "netcut" / "arp-poison". */
    private static final String[] KILL_CMDS = {
            "pkill -9 -f netcut 2>/dev/null || true",
            "killall -9 netcut 2>/dev/null || true",
            "pkill -9 -f arp-poison 2>/dev/null || true",
            "pkill -9 -f arpoison 2>/dev/null || true"
    };


    public static boolean killAll(RootShellManager shell) {
        if (shell == null || !shell.isShellAvailable()) {
            Log.w(TAG, "Root shell not available — cannot kill netcut");
            return false;
        }
        boolean anySuccess = false;
        for (String cmd : KILL_CMDS) {
            int rc = shell.executeCommandWithCode(cmd);
            if (rc == 0) anySuccess = true;
        }
        Log.d(TAG, "killAll result=" + anySuccess);
        return anySuccess;
    }



}