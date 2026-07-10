package com.app.netcut;



import android.util.Log;

/**
 * Centralized "kill every netcut-like process" logic.
 *
 * Previously this same block was copy-pasted in:
 *   - MainActivity.forceKillNetcut()
 *   - MainActivity.killNetcutDirectly()
 *   - MainActivity.fixNetwork()
 *   - NetcutRunner.emergencyStop()
 *   - NetcutRunner.broadKill()
 *
 * Now there is ONE source of truth.
 */
public final class NetcutKiller {

    private static final String TAG = "NetcutKiller";

    /** Commands that aggressively kill anything named "netcut" / "arp-poison". */
    private static final String[] KILL_CMDS = {
            "pkill -9 -f netcut 2>/dev/null || true",
            "killall -9 netcut 2>/dev/null || true",
            "pkill -9 -f arp-poison 2>/dev/null || true",
            "pkill -9 -f arpoison 2>/dev/null || true"
    };

    private NetcutKiller() { /* utility class */ }

    /**
     * Kill all netcut processes using the given root shell.
     * Does NOT flush ARP — caller decides whether to flush.
     *
     * @return true if at least one kill command returned 0.
     */
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

    /**
     * Same as {@link #killAll(RootShellManager)} but also flushes the ARP
     * neighbour table afterwards. Convenience wrapper for "nuke everything".
     */
    public static boolean killAllAndFlushArp(RootShellManager shell) {
        boolean killed = killAll(shell);
        if (shell != null && shell.isShellAvailable()) {
            shell.executeCommandBool("ip neigh flush all 2>/dev/null || true");
            shell.executeCommandBool("ip -s neigh flush all 2>/dev/null || true");
        }
        return killed;
    }
}