package com.app.netcut;


import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of the external netcut binary.
 *
 * Changes vs. previous version:
 *  - Uses ShellUtils.shQ() everywhere (removed local shQ/sq duplicates).
 *  - emergencyStop() / broadKill() delegate to NetcutKiller — single source
 *    of truth for "kill netcut".
 *  - startLogTail() now uses BufferedReader with UTF-8 instead of the
 *    fragile ISO-8859-1 → UTF-8 byte hack.
 *  - ioExecutor is now a fixed-size pool (was CachedThreadPool which could
 *    spawn unbounded threads).
 */
public class NetcutRunner {

    private static final String TAG = "NetcutRunnerV3";

    public interface LogListener   { void onLog(String line); }
    public interface StateListener {
        void onStarted(int pid, LaunchMode mode);
        void onStopped();
        void onCrashed(String reason);
    }

    public enum LaunchMode { SETSID, NOHUP, PLAIN_BG }

    private final Context context;
    private final LogListener logListener;
    private final StateListener stateListener;

    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(3);

    private PersistentRootShell shell;
    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private volatile Future<?> tailFuture;
    private volatile Future<?> crashWatchFuture;

    private volatile File workDir;
    private volatile File logFile;
    private volatile File pidFile;
    private volatile String netcutPath;
    private volatile int netcutPid = -1;
    private volatile LaunchMode launchMode;

    public NetcutRunner(Context context,
                        LogListener logListener,
                        StateListener stateListener) {
        this.context       = context.getApplicationContext();
        this.logListener   = logListener;
        this.stateListener = stateListener;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void init() throws Exception {
        if (shell != null && shell.isAlive()) return;

        shell = new PersistentRootShell(line -> {
            if (logListener != null) logListener.onLog("[su:stderr] " + line);
        });

        workDir = new File(context.getFilesDir(), "netcut_runtime");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Failed to create work dir: " + workDir);
        }

        logFile = new File(workDir, "netcut.log");
        pidFile = new File(workDir, "netcut.pid");

        netcutPath = findNetcutBinary();
        if (netcutPath == null) {
            throw new FileNotFoundException("netcut binary not found");
        }
        shell.exec("chmod 755 " + ShellUtils.shQ(netcutPath));
    }

    public synchronized void start(String args) throws Exception {
        if (running.get()) {
            log("Already running");
            return;
        }
        init();
        stopping.set(false);

        // cleanup stale files
        shell.exec(": > " + ShellUtils.shQ(logFile.getAbsolutePath()));
        shell.exec("rm -f " + ShellUtils.shQ(pidFile.getAbsolutePath()));

        launchMode = detectLaunchMode();
        log("Launch mode: " + launchMode);

        String cmd = buildLaunchCommand(
                netcutPath, args,
                logFile.getAbsolutePath(),
                pidFile.getAbsolutePath(),
                launchMode);

        PersistentRootShell.ShellResult result = shell.exec(cmd);
        if (result.exitCode != 0) {
            throw new IOException("Launch command failed rc=" + result.exitCode
                    + " out=" + result.joinedStdout());
        }

        int pid = waitForPidFile(4000);
        if (pid <= 0) {
            throw new IOException("Failed to obtain netcut pid from pid file");
        }

        netcutPid = pid;
        running.set(true);

        startLogTail();
        startCrashWatcher();

        if (stateListener != null) stateListener.onStarted(pid, launchMode);
        log("Started with pid=" + pid);
    }

    public synchronized void emergencyStop() {
        Log.d(TAG, "Emergency stop called");
        try {
            // Delegate to the centralized killer.
            RootShellManager rsm = RootShellManager.getInstance();
            NetcutKiller.killAllAndFlushArp(rsm);

            // Also try via our persistent shell (in case libsu shell is down
            // but our private su session is still alive).
            if (shell != null && shell.isAlive()) {
                shell.exec("pkill -9 -f netcut 2>/dev/null || true");
                shell.exec("ip neigh flush all 2>/dev/null || true");
            }

            restoreArpQuietly();

            running.set(false);
            stopping.set(false);
            netcutPid = -1;

            if (shell != null) {
                shell.close();
                shell = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Emergency stop failed: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!running.get() && netcutPid <= 0) {
            restoreArpQuietly();
            if (stateListener != null) stateListener.onStopped();
            return;
        }
        stopping.set(true);
        try {
            stopInternal(false);
        } catch (Exception e) {
            log("stop failed: " + e.getMessage());
            try {
                stopInternal(true);
            } catch (Exception e2) {
                log("force stop failed: " + e2.getMessage());
            }
        } finally {
            cleanupAfterStop();
            restoreArpQuietly();
            if (stateListener != null) stateListener.onStopped();
        }
    }

    public synchronized void forceStop() {
        stopping.set(true);
        try {
            stopInternal(true);
        } catch (Exception e) {
            log("forceStop exception: " + e.getMessage());
        } finally {
            cleanupAfterStop();
            restoreArpQuietly();
            if (stateListener != null) stateListener.onStopped();
        }
    }

    public synchronized void destroy() {
        forceStop();
        if (shell != null) {
            shell.close();
            shell = null;
        }
        ioExecutor.shutdownNow();
    }

    public boolean isRunning()      { return running.get(); }
    public int getPid()             { return netcutPid; }
    public LaunchMode getLaunchMode() { return launchMode; }

    // ------------------------------------------------------------------
    // Internal stop logic
    // ------------------------------------------------------------------

    private void stopInternal(boolean force) throws Exception {
        if (shell == null || !shell.isAlive()) return;

        int pid = readPidFromFileOrMemory();
        if (pid <= 0) {
            log("No valid pid found; using broad fallback");
            broadKill(force);
            return;
        }

        int sig = force ? 9 : 15;
        switch (launchMode) {
            case SETSID:
                shell.exec("kill -" + sig + " -- -" + pid + " 2>/dev/null || true");
                shell.exec("kill -" + sig + " " + pid + " 2>/dev/null || true");
                break;
            case NOHUP:
            case PLAIN_BG:
            default:
                shell.exec("kill -" + sig + " " + pid + " 2>/dev/null || true");
                shell.exec("pkill -" + sig + " -P " + pid + " 2>/dev/null || true");
                break;
        }

        waitForExit(pid, force ? 2000 : 3500);

        if (isPidAlive(pid)) {
            log("pid still alive after primary stop, escalating");
            shell.exec("kill -9 " + pid + " 2>/dev/null || true");
            shell.exec("pkill -9 -P " + pid + " 2>/dev/null || true");
            shell.exec("kill -9 -- -" + pid + " 2>/dev/null || true");
            waitForExit(pid, 2000);
        }

        if (isPidAlive(pid)) {
            broadKill(true);
        }
    }

    private void broadKill(boolean force) throws Exception {
        if (shell == null || !shell.isAlive()) return;
        int sig = force ? 9 : 15;
        String binName = new File(netcutPath).getName();
        shell.exec("pkill -" + sig + " -f " + ShellUtils.shQ(netcutPath) + " 2>/dev/null || true");
        shell.exec("pkill -" + sig + " "   + ShellUtils.shQ(binName)   + " 2>/dev/null || true");
    }

    private void cleanupAfterStop() {
        running.set(false);
        stopping.set(false);
        netcutPid = -1;

        if (tailFuture != null)       { tailFuture.cancel(true);       tailFuture = null; }
        if (crashWatchFuture != null) { crashWatchFuture.cancel(true); crashWatchFuture = null; }

        try {
            if (shell != null && shell.isAlive()) {
                shell.exec("rm -f " + ShellUtils.shQ(pidFile.getAbsolutePath())
                        + " 2>/dev/null || true");
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Log tailing (UTF-8 safe)
    // ------------------------------------------------------------------

    private void startLogTail() {
        tailFuture = ioExecutor.submit(() -> {
            long pos = 0;
            while (!Thread.currentThread().isInterrupted()
                    && (running.get() || stopping.get())) {
                try {
                    if (!logFile.exists()) {
                        SystemClock.sleep(300);
                        continue;
                    }
                    long len = logFile.length();
                    if (len < pos) pos = 0; // truncated

                    if (len > pos) {
                        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                            raf.seek(pos);
                            // Read raw bytes and decode as UTF-8
                            byte[] buf = new byte[(int) (len - pos)];
                            raf.readFully(buf);
                            String chunk = new String(buf, StandardCharsets.UTF_8);

                            // Split into lines and deliver
                            for (String line : chunk.split("\n")) {
                                if (!line.isEmpty() && logListener != null) {
                                    logListener.onLog(line);
                                }
                            }
                            pos = raf.getFilePointer();
                        }
                    }
                    SystemClock.sleep(250);
                } catch (Exception e) {
                    log("tail error: " + e.getMessage());
                    SystemClock.sleep(500);
                }
            }
        });
    }

    private void startCrashWatcher() {
        crashWatchFuture = ioExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()
                    && running.get() && !stopping.get()) {
                try {
                    int pid = readPidFromFileOrMemory();
                    if (pid > 0 && !isPidAlive(pid)) {
                        log("Crash watcher: netcut died pid=" + pid);
                        running.set(false);
                        restoreArpQuietly();
                        if (stateListener != null) {
                            stateListener.onCrashed("netcut exited unexpectedly");
                        }
                        return;
                    }
                    SystemClock.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log("crash watcher error: " + e.getMessage());
                    SystemClock.sleep(1000);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Launch helpers
    // ------------------------------------------------------------------

    private LaunchMode detectLaunchMode() throws Exception {
        if (commandExists("setsid")) return LaunchMode.SETSID;
        if (commandExists("nohup"))  return LaunchMode.NOHUP;
        return LaunchMode.PLAIN_BG;
    }

    private boolean commandExists(String cmd) throws Exception {
        PersistentRootShell.ShellResult r =
                shell.exec("command -v " + ShellUtils.shQ(cmd) + " >/dev/null 2>&1");
        return r.exitCode == 0;
    }

    private String buildLaunchCommand(String binary, String args,
                                      String logPath, String pidPath,
                                      LaunchMode mode) {
        String full = ShellUtils.shQ(binary)
                + (args == null || args.trim().isEmpty() ? "" : " " + args.trim());

        // We need a version of `full` that is safe to embed inside a
        // single-quoted sh -c '…' wrapper.
        String fullInsideSingleQuotes = full.replace("'", "'\\''");
        String logQ = ShellUtils.shQ(logPath);
        String pidQ = ShellUtils.shQ(pidPath);

        switch (mode) {
            case SETSID:
                return "setsid sh -c '"
                        + fullInsideSingleQuotes
                        + " >> " + logQ + " 2>&1 & echo $! > " + pidQ + "'";
            case NOHUP:
                return "nohup sh -c '"
                        + fullInsideSingleQuotes
                        + " >> " + logQ + " 2>&1' >/dev/null 2>&1 & "
                        + "echo $! > " + pidQ;
            case PLAIN_BG:
            default:
                return "sh -c '"
                        + fullInsideSingleQuotes
                        + " >> " + logQ + " 2>&1' >/dev/null 2>&1 & "
                        + "echo $! > " + pidQ;
        }
    }

    // ------------------------------------------------------------------
    // PID helpers
    // ------------------------------------------------------------------

    private int waitForPidFile(long timeoutMs) throws Exception {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            int pid = readPidFromFile();
            if (pid > 0) return pid;
            SystemClock.sleep(150);
        }
        return -1;
    }

    private int readPidFromFileOrMemory() {
        int filePid = readPidFromFile();
        if (filePid > 0) {
            netcutPid = filePid;
            return filePid;
        }
        return netcutPid;
    }

    private int readPidFromFile() {
        try {
            if (pidFile == null || !pidFile.exists()) return -1;
            try (BufferedReader br = new BufferedReader(new FileReader(pidFile))) {
                String s = br.readLine();
                if (s == null) return -1;
                return Integer.parseInt(s.trim());
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isPidAlive(int pid) throws Exception {
        if (pid <= 0 || shell == null || !shell.isAlive()) return false;
        PersistentRootShell.ShellResult r =
                shell.exec("kill -0 " + pid + " >/dev/null 2>&1");
        return r.exitCode == 0;
    }

    private void waitForExit(int pid, long timeoutMs) throws Exception {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            if (!isPidAlive(pid)) return;
            SystemClock.sleep(150);
        }
    }

    private String findNetcutBinary() throws Exception {
        String[] candidates = {
                new File(context.getFilesDir(), "netcut").getAbsolutePath(),
                new File(context.getFilesDir(), "bin/netcut").getAbsolutePath(),
                "/data/local/tmp/netcut",
                "/system/bin/netcut",
                "/system/xbin/netcut"
        };
        for (String path : candidates) {
            PersistentRootShell.ShellResult r =
                    shell.exec("[ -f " + ShellUtils.shQ(path) + " ] && echo OK || true");
            if (r.joinedStdout().contains("OK")) return path;
        }
        return null;
    }

    private void restoreArpQuietly() {
        try {
            ArpRestore arpRestore = ArpRestore.getInstance(context);
            boolean ok = arpRestore.restoreAllArpEntries();
            log("ARP restore result: " + ok);
        } catch (Exception e) {
            log("restoreArp failed: " + e.getMessage());
        }
    }

    private void log(String s) {
        Log.d(TAG, s);
        if (logListener != null) logListener.onLog("[runner] " + s);
    }
}