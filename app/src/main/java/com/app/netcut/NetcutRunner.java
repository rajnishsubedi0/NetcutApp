package com.app.netcut;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class NetcutRunner implements Closeable {

    private static final String TAG = "NetcutRunner";

    public interface LogListener { void onLog(String line); }

    public interface StateListener {
        void onStarted(int pid, LaunchMode mode);
        void onStopped();
        void onCrashed(String reason);
    }

    public enum LaunchMode { SETSID, NOHUP, PLAIN_BG }

    private final Context context;
    private final String sessionKey;
    private final LogListener logListener;
    private final StateListener stateListener;

    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);

    private PersistentRootShell shell;
    private final AtomicBoolean running = new AtomicBoolean(false);
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
                        String sessionKey,
                        LogListener logListener,
                        StateListener stateListener) {
        this.context = context.getApplicationContext();
        this.sessionKey = sessionKey;
        this.logListener = logListener;
        this.stateListener = stateListener;
    }

    public synchronized void init() throws Exception {
        if (shell != null && shell.isAlive()) return;

        shell = new PersistentRootShell(line -> {
            if (logListener != null) logListener.onLog("[" + sessionKey + "][su:stderr] " + line);
        });

        workDir = new File(context.getFilesDir(), "netcut_runtime_" + sessionKey);
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

        shell.exec(": > " + ShellUtils.shQ(logFile.getAbsolutePath()));
        shell.exec("rm -f " + ShellUtils.shQ(pidFile.getAbsolutePath()));

        launchMode = detectLaunchMode();
        log("Launch mode: " + launchMode);

        String cmd = buildLaunchCommand(
                netcutPath, args,
                logFile.getAbsolutePath(),
                pidFile.getAbsolutePath(),
                launchMode
        );

        PersistentRootShell.ShellResult result = shell.exec(cmd);
        if (result.exitCode != 0) {
            throw new IOException("Launch command failed rc=" + result.exitCode);
        }

        int pid = waitForPidFile(3000);
        if (pid <= 0) {
            throw new IOException("Failed to obtain pid");
        }

        netcutPid = pid;
        running.set(true);

        startLogTail();
        startCrashWatcher();

        if (stateListener != null) stateListener.onStarted(pid, launchMode);
        log("Started with pid=" + pid);
    }

    public synchronized void stop() {
        if (!running.get() && netcutPid <= 0) {
            if (stateListener != null) stateListener.onStopped();
            return;
        }

        stopping.set(true);
        try {
            stopInternalFast();
        } catch (Exception e) {
            log("stop failed: " + e.getMessage());
            forceKill();
        } finally {
            cleanupAfterStop();
            if (stateListener != null) stateListener.onStopped();
        }
    }

    public synchronized void emergencyStop() {
        try {
            forceKill();
            cleanupAfterStop();
        } catch (Exception e) {
            Log.e(TAG, "Emergency stop failed", e);
        }
    }

    private void stopInternalFast() throws Exception {
        if (shell == null || !shell.isAlive()) return;

        int pid = readPidFromFileOrMemory();
        if (pid <= 0) {
            forceKill();
            return;
        }

        shell.exec("kill -15 " + pid + " 2>/dev/null || true");
        shell.exec("pkill -15 -P " + pid + " 2>/dev/null || true");

        SystemClock.sleep(300);

        if (isPidAlive(pid)) {
            shell.exec("kill -9 " + pid + " 2>/dev/null || true");
            shell.exec("pkill -9 -P " + pid + " 2>/dev/null || true");
            if (launchMode == LaunchMode.SETSID) {
                shell.exec("kill -9 -- -" + pid + " 2>/dev/null || true");
            }
        }

        if (isPidAlive(pid)) {
            forceKill();
        }
    }

    private void forceKill() {
        try {
            int pid = readPidFromFileOrMemory();
            if (pid > 0 && shell != null && shell.isAlive()) {
                shell.exec("kill -9 " + pid + " 2>/dev/null || true");
                shell.exec("pkill -9 -P " + pid + " 2>/dev/null || true");
            }
        } catch (Exception ignored) {}
    }

    private void startLogTail() {
        tailFuture = ioExecutor.submit(() -> {
            long pos = 0;
            while (!Thread.currentThread().isInterrupted() && (running.get() || stopping.get())) {
                try {
                    if (!logFile.exists()) {
                        SystemClock.sleep(300);
                        continue;
                    }

                    long len = logFile.length();
                    if (len < pos) pos = 0;

                    if (len > pos) {
                        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                            raf.seek(pos);
                            byte[] buf = new byte[(int) (len - pos)];
                            raf.readFully(buf);
                            String chunk = new String(buf, StandardCharsets.UTF_8);
                            for (String line : chunk.split("\n")) {
                                if (!line.isEmpty() && logListener != null) {
                                    logListener.onLog("[" + sessionKey + "] " + line);
                                }
                            }
                            pos = raf.getFilePointer();
                        }
                    }

                    SystemClock.sleep(250);
                } catch (Exception e) {
                    SystemClock.sleep(500);
                }
            }
        });
    }

    private void startCrashWatcher() {
        crashWatchFuture = ioExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted() && running.get() && !stopping.get()) {
                try {
                    int pid = readPidFromFileOrMemory();
                    if (pid > 0 && !isPidAlive(pid)) {
                        log("Process died pid=" + pid);
                        running.set(false);
                        if (stateListener != null) {
                            stateListener.onCrashed("process exited unexpectedly");
                        }
                        return;
                    }
                    SystemClock.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    SystemClock.sleep(1000);
                }
            }
        });
    }

    private LaunchMode detectLaunchMode() throws Exception {
        if (commandExists("setsid")) return LaunchMode.SETSID;
        if (commandExists("nohup")) return LaunchMode.NOHUP;
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
                        + " >> " + logQ + " 2>&1' >/dev/null 2>&1 & echo $! > " + pidQ;
            case PLAIN_BG:
            default:
                return "sh -c '"
                        + fullInsideSingleQuotes
                        + " >> " + logQ + " 2>&1' >/dev/null 2>&1 & echo $! > " + pidQ;
        }
    }

    private int waitForPidFile(long timeoutMs) throws Exception {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            int pid = readPidFromFile();
            if (pid > 0) return pid;
            SystemClock.sleep(100);
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

    private void cleanupAfterStop() {
        running.set(false);
        stopping.set(false);
        netcutPid = -1;

        if (tailFuture != null) {
            tailFuture.cancel(true);
            tailFuture = null;
        }

        if (crashWatchFuture != null) {
            crashWatchFuture.cancel(true);
            crashWatchFuture = null;
        }

        try {
            if (shell != null && shell.isAlive() && pidFile != null) {
                shell.exec("rm -f " + ShellUtils.shQ(pidFile.getAbsolutePath()) + " 2>/dev/null || true");
            }
        } catch (Exception ignored) {}
    }

    private void log(String s) {
        Log.d(TAG, "[" + sessionKey + "] " + s);
        if (logListener != null) logListener.onLog("[runner][" + sessionKey + "] " + s);
    }

    @Override
    public synchronized void close() {
        destroy();
    }

    public synchronized void destroy() {
        emergencyStop();
        if (shell != null) {
            shell.close();
            shell = null;
        }
        ioExecutor.shutdownNow();
    }
}