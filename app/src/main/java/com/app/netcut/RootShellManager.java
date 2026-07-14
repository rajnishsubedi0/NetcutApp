package com.app.netcut;


import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


public class RootShellManager {

    private static final String TAG = "RootShellManager";

    private static RootShellManager instance;

    private Shell shell;
    private boolean isInitialized = false;
    private final AtomicBoolean isShellAvailable = new AtomicBoolean(false);

    private RootShellManager() {
        initializeShell();
    }

    public static synchronized RootShellManager getInstance() {
        if (instance == null) {
            instance = new RootShellManager();
        }
        return instance;
    }



    private synchronized void initializeShell() {
        try {
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
            return isInitialized && shell != null && shell.isAlive() && isShellAvailable.get();
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> executeCommandLines(String command) {
        if (!isShellAvailable()) {
            Log.w(TAG, "Root shell not available");
            return new ArrayList<>();
        }
        try {
            Shell.Result result = Shell.cmd(command).exec();
            if (result.isSuccess()) {
                return new ArrayList<>(result.getOut());
            }
            if (!result.getErr().isEmpty()) {
                return new ArrayList<>(result.getErr());
            }
            return new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + command, e);
            return new ArrayList<>();
        }
    }


    public boolean executeCommandBool(String command) {
        if (!isShellAvailable()) {
            Log.w(TAG, "Root shell not available");
            return false;
        }
        try {
            return Shell.cmd(command).exec().isSuccess();
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + command, e);
            return false;
        }
    }


    public boolean hasRootAccess() {
        if (!isShellAvailable()) return false;
        try {
            Shell.Result result = Shell.cmd("id").exec();
            if (!result.isSuccess()) return false;
            String output = String.join("", result.getOut());
            boolean hasRoot = output.contains("uid=0") || output.contains("root");
            Log.d(TAG, "Root check result: " + hasRoot + " output=" + output);
            return hasRoot;
        } catch (Exception e) {
            Log.e(TAG, "Root check error", e);
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