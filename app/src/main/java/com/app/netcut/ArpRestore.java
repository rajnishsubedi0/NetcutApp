package com.app.netcut;

import static com.app.netcut.ShellUtils.shQ;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class ArpRestore {

    private static final String TAG = "ArpRestore";

    private static final long SHORT_CIRCUIT_MS = 50L;

    private static ArpRestore instance;

    private final Map<String, String> arpCache = new HashMap<>();
    private final Map<String, String> arpDevices = new HashMap<>();
    private final Object lock = new Object();


    private final AtomicBoolean isRestoring = new AtomicBoolean(false);
    private final ReentrantLock restoreLock = new ReentrantLock();

    private String interfaceName = "wlan0";
    private final RootShellManager shellManager;

    private long lastRestoreTime = 0;
    private boolean lastRestoreSuccess = false;

    private ArpRestore( ) {

        this.shellManager = RootShellManager.getInstance();
        detectInterface();
        loadArpCache();
    }

    public static synchronized ArpRestore getInstance(Context context) {
        if (instance == null) {
            instance = new ArpRestore();
        }
        return instance;
    }

    private void detectInterface() {
        try {
            String iface = System.getProperty("wifi.interface");
            if (iface != null && !iface.trim().isEmpty()) {
                interfaceName = iface.trim();
                Log.d(TAG, "Interface from system property: " + interfaceName);
                return;
            }

            if (shellManager != null && shellManager.isShellAvailable()) {
                List<String> lines = shellManager.executeCommandLines("ip link show");
                for (String line : lines) {
                    if (line != null && line.contains("wlan")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            interfaceName = parts[1].trim().split("\\s+")[0];
                            Log.d(TAG, "Interface from ip link: " + interfaceName);
                            return;
                        }
                    }
                }

                String result = shellManager.executeCommand("ls /sys/class/net/");
                if (result != null) {
                    for (String name : result.split("\\s+")) {
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            interfaceName = name;
                            Log.d(TAG, "Interface from /sys/class/net: " + interfaceName);
                            return;
                        }
                    }
                }
            }
            Log.w(TAG, "Could not detect interface, using default: " + interfaceName);
        } catch (Exception e) {
            Log.w(TAG, "Interface detection failed, using default: " + interfaceName, e);
        }
    }

    public void loadArpCache() {
        synchronized (lock) {
            arpCache.clear();
            arpDevices.clear();
            try {
                Log.d(TAG, "Loading ARP cache...");
                if (!loadArpWithIpNeigh()) {
                    Log.d(TAG, "ip neigh failed, trying /proc/net/arp");
                    loadArpWithProcFs();
                }
                Log.d(TAG, "Loaded " + arpCache.size() + " ARP entries");
            } catch (Exception e) {
                Log.e(TAG, "Failed to load ARP cache", e);
            }
        }
    }



    private boolean loadArpWithIpNeigh() {
        try {
            List<String> lines = shellManager.executeCommandLines("ip neigh show");
            if (lines == null || lines.isEmpty()) {
                return false;
            }

            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;

                String ip = parts[0];
                String mac = null;
                String dev = null;

                for (int i = 0; i < parts.length - 1; i++) {
                    if ("lladdr".equals(parts[i])) mac = parts[i + 1];
                    else if ("dev".equals(parts[i])) dev = parts[i + 1];
                }

                if (mac != null
                        && !"00:00:00:00:00:00".equalsIgnoreCase(mac)
                        && !"incomplete".equalsIgnoreCase(mac)
                        && !"failed".equalsIgnoreCase(mac)) {
                    arpCache.put(ip, mac);
                    if (dev != null && !dev.isEmpty()) {
                        arpDevices.put(ip, dev);
                        if (interfaceName == null || interfaceName.isEmpty()) {
                            interfaceName = dev;
                        }
                    }
                }
            }
            return !arpCache.isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "ip neigh failed", e);
            return false;
        }
    }

    private void loadArpWithProcFs() {
        try {
            List<String> lines = shellManager.executeCommandLines("cat /proc/net/arp");
            if (lines == null || lines.isEmpty()) {
                return;
            }

            boolean first = true;
            for (String line : lines) {
                if (first) { first = false; continue; }
                if (line == null || line.trim().isEmpty()) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) continue;

                String ip = parts[0];
                String mac = parts[3];
                String device = parts[5];

                if (!"00:00:00:00:00:00".equalsIgnoreCase(mac)
                        && !"incomplete".equalsIgnoreCase(mac)) {
                    arpCache.put(ip, mac);
                    arpDevices.put(ip, device);
                    if (interfaceName == null || interfaceName.isEmpty()) {
                        interfaceName = device;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ARP from /proc/net/arp", e);
        }
    }

   

    public boolean flushAndRestoreFast() {
        long now = System.currentTimeMillis();
        if (now - lastRestoreTime < SHORT_CIRCUIT_MS) {
            Log.d(TAG, "Using cached result (called within " + SHORT_CIRCUIT_MS + "ms)");
            return lastRestoreSuccess;
        }

        boolean locked = false;
        try {
            locked = restoreLock.tryLock(1, TimeUnit.SECONDS);
            if (!locked) return false;
            if (!isRestoring.compareAndSet(false, true)) return false;

            Map<String, String> cacheSnapshot;
            Map<String, String> devSnapshot;
            synchronized (lock) {
                cacheSnapshot = new HashMap<>(arpCache);
                devSnapshot = new HashMap<>(arpDevices);
            }

            int totalEntries = cacheSnapshot.size();
            if (totalEntries == 0) {
                loadArpCache();
                synchronized (lock) {
                    cacheSnapshot = new HashMap<>(arpCache);
                    devSnapshot = new HashMap<>(arpDevices);
                }
                totalEntries = cacheSnapshot.size();
                if (totalEntries == 0) {
                    lastRestoreSuccess = false;
                    lastRestoreTime = System.currentTimeMillis();
                    return false;
                }
            }

            // Flush - minimal wait
            boolean flushed = flushArpCacheFast();

            // Build single command for restore
            StringBuilder cmd = new StringBuilder();
            for (Map.Entry<String, String> entry : cacheSnapshot.entrySet()) {
                String ip = entry.getKey();
                String mac = entry.getValue();
                String dev = devSnapshot.get(ip);
                if (dev == null || dev.isEmpty()) dev = interfaceName;

                cmd.append("ip neigh replace ")
                        .append(shQ(ip)).append(" lladdr ")
                        .append(shQ(mac)).append(" dev ")
                        .append(shQ(dev)).append(" nud permanent 2>/dev/null; ");
            }

            boolean restored = shellManager.executeCommandBool(cmd.toString());

            lastRestoreTime = System.currentTimeMillis();
            lastRestoreSuccess = flushed && restored;
            Log.d(TAG, "Fast flush+restore: " + (lastRestoreSuccess ? "✅" : "❌"));
            return lastRestoreSuccess;

        } catch (Exception e) {
            Log.e(TAG, "Fast flush+restore failed", e);
            lastRestoreSuccess = false;
            return false;
        } finally {
            isRestoring.set(false);
            if (locked) {
                try { restoreLock.unlock(); }
                catch (IllegalMonitorStateException ignored) {}
            }
        }
    }

    private boolean flushArpCacheFast() {
        try {

            shellManager.executeCommandBool("ip neigh flush all 2>/dev/null");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to flush ARP cache", e);
            return false;
        }
    }


    public void refreshCache() { loadArpCache(); }

}