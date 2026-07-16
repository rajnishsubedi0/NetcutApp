package com.app.netcut;

import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class RootShellManager {

    private static final String TAG = "RootShellManager";
    private static volatile RootShellManager instance;
    private static final Object lock = new Object();

    private Shell shell;
    private boolean isInitialized = false;
    private final AtomicBoolean isShellAvailable = new AtomicBoolean(false);

    private RootShellManager() {
        initializeShell();
    }

    public static RootShellManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new RootShellManager();
                }
            }
        }
        return instance;
    }


    private synchronized void initializeShell() {
        try {
            if (shell != null && shell.isAlive()) {
                isShellAvailable.set(true);
                isInitialized = true;
                Log.d(TAG, "Root shell already initialized and alive");
                return;
            }

            Shell.Builder builder = Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(30);
            shell = builder.build();

            if (shell != null && shell.isAlive()) {
                isShellAvailable.set(true);
                isInitialized = true;
                Log.d(TAG, "Root shell initialized successfully");
            } else {
                shell = null;
                isInitialized = false;
                isShellAvailable.set(false);
                Log.e(TAG, "Shell initialization failed");
            }
        } catch (Exception e) {
            shell = null;
            isInitialized = false;
            isShellAvailable.set(false);
            Log.e(TAG, "Error initializing root shell", e);
        }
    }

    public boolean isShellAvailable() {
        try {
            if (isInitialized && shell != null) {
                boolean alive = shell.isAlive();
                if (!alive) {
                    isShellAvailable.set(false);
                    isInitialized = false;
                    Log.w(TAG, "Shell died, will reinitialize on next call");
                } else {
                    isShellAvailable.set(true);
                }
                return alive && isShellAvailable.get();
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking shell availability", e);
            return false;
        }
    }

    public List<String> executeCommandLines(String command) {
        if (!isShellAvailable()) {
            if (!isInitialized) {
                Log.d(TAG, "Shell not initialized, trying to reinitialize...");
                initializeShell();
                if (!isShellAvailable()) {
                    Log.w(TAG, "Root shell still not available after reinitialization");
                    return new ArrayList<>();
                }
            } else {
                Log.w(TAG, "Root shell not available");
                return new ArrayList<>();
            }
        }

        try {
            Shell.Result result = Shell.cmd(command).exec();
            if (result.isSuccess()) {
                return new ArrayList<>(result.getOut());
            }
            if (!result.getErr().isEmpty()) {
                Log.w(TAG, "Command error output: " + result.getErr());
                return new ArrayList<>(result.getErr());
            }
            return new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + command, e);
            isShellAvailable.set(false);
            isInitialized = false;
            return new ArrayList<>();
        }
    }

    public boolean executeCommandBool(String command) {
        if (!isShellAvailable()) {
            if (!isInitialized) {
                initializeShell();
                if (!isShellAvailable()) {
                    Log.w(TAG, "Root shell not available");
                    return false;
                }
            } else {
                Log.w(TAG, "Root shell not available");
                return false;
            }
        }

        try {
            Shell.Result result = Shell.cmd(command).exec();
            return result.isSuccess();
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + command, e);
            isShellAvailable.set(false);
            isInitialized = false;
            return false;
        }
    }

    public boolean hasRootAccess() {
        if (!isShellAvailable()) {
            if (!isInitialized) {
                initializeShell();
                if (!isShellAvailable()) {
                    Log.w(TAG, "Cannot check root access - shell not available");
                    return false;
                }
            } else {
                Log.w(TAG, "Cannot check root access - shell not available");
                return false;
            }
        }

        try {
            Shell.Result result = Shell.cmd("id").exec();
            if (!result.isSuccess()) {
                Log.w(TAG, "id command failed");
                return false;
            }
            String output = String.join("", result.getOut());
            boolean hasRoot = output.contains("uid=0") || output.contains("root");
            Log.d(TAG, "Root check result: " + hasRoot + " output=" + output);
            return hasRoot;
        } catch (Exception e) {
            Log.e(TAG, "Root check error", e);
            isShellAvailable.set(false);
            isInitialized = false;
            return false;
        }
    }

    public synchronized void close() {
        closeInternal();
    }

    private void closeInternal() {
        if (shell != null) {
            try {
                shell.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing shell", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error closing shell", e);
            }
            shell = null;
        }
        isInitialized = false;
        isShellAvailable.set(false);
    }

}