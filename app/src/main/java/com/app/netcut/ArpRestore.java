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
    private static final int BATCH_SIZE = 20;
    private static final long SHORT_CIRCUIT_MS = 100L;

    private static ArpRestore instance;

    private final Map<String, String> arpCache = new HashMap<>();
    private final Map<String, String> arpDevices = new HashMap<>();
    private final Object lock = new Object();

    private final Context context;
    private final AtomicBoolean isRestoring = new AtomicBoolean(false);
    private final ReentrantLock restoreLock = new ReentrantLock();

    private String interfaceName = "wlan0";
    private final RootShellManager shellManager;

    private long lastRestoreTime = 0;
    private boolean lastRestoreSuccess = false;

    private ArpRestore(Context context) {
        this.context = context.getApplicationContext();
        this.shellManager = RootShellManager.getInstance();
        detectInterface();
        loadArpCache();
    }

    public static synchronized ArpRestore getInstance(Context context) {
        if (instance == null) {
            instance = new ArpRestore(context);
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Interface detection (improved)
    // ------------------------------------------------------------------

    private void detectInterface() {
        try {
            // Method 1: System property
            String iface = System.getProperty("wifi.interface");
            if (iface != null && !iface.trim().isEmpty()) {
                interfaceName = iface.trim();
                Log.d(TAG, "Interface from system property: " + interfaceName);
                return;
            }

            // Method 2: Check via ip link
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

                // Method 3: Check for eth0 (some devices use this)
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

    // ------------------------------------------------------------------
    // Cache loading (improved)
    // ------------------------------------------------------------------

    public void loadArpCache() {
        synchronized (lock) {
            arpCache.clear();
            arpDevices.clear();
            try {
                Log.d(TAG, "Loading ARP cache...");

                // Try ip neigh first
                if (!loadArpWithIpNeigh()) {
                    Log.d(TAG, "ip neigh failed, trying /proc/net/arp");
                    loadArpWithProcFs();
                }

                Log.d(TAG, "Loaded " + arpCache.size() + " ARP entries");
                if (arpCache.isEmpty()) {
                    Log.w(TAG, "ARP cache is empty! Restore may not work.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load ARP cache", e);
            }
        }
    }

    private boolean loadArpWithIpNeigh() {
        try {
            List<String> lines = shellManager.executeCommandLines("ip neigh show");
            if (lines == null || lines.isEmpty()) {
                Log.w(TAG, "ip neigh show returned no lines");
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
                Log.w(TAG, "/proc/net/arp returned no lines");
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

    // ------------------------------------------------------------------
    // Single-entry restore
    // ------------------------------------------------------------------

    public boolean restoreArpEntry(String ip) {
        synchronized (lock) {
            String mac = arpCache.get(ip);
            if (mac == null) {
                Log.w(TAG, "No cached ARP entry for " + ip);
                return false;
            }
            String dev = arpDevices.get(ip);
            return restoreArpEntryInternal(ip, mac, dev);
        }
    }

    public boolean restoreArpEntry(String ip, String mac) {
        synchronized (lock) {
            String dev = arpDevices.get(ip);
            return restoreArpEntryInternal(ip, mac, dev);
        }
    }

    private boolean restoreArpEntryInternal(String ip, String mac, String dev) {
        try {
            if (dev == null || dev.isEmpty()) dev = interfaceName;
            Log.d(TAG, "Restoring ARP: " + ip + " -> " + mac + " dev=" + dev);

            String cmd = "ip neigh replace " + shQ(ip)
                    + " lladdr " + shQ(mac)
                    + " dev " + shQ(dev)
                    + " nud permanent";

            boolean success = shellManager.executeCommandBool(cmd);
            if (success) {
                Log.d(TAG, "ARP restored via ip neigh: " + ip);
                return true;
            }

            // Fallback for very old kernels
            cmd = "arp -s " + shQ(ip) + " " + shQ(mac);
            success = shellManager.executeCommandBool(cmd);
            if (success) {
                Log.d(TAG, "ARP restored via arp: " + ip);
                return true;
            }

            Log.e(TAG, "Failed to restore ARP for " + ip);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error restoring ARP for " + ip, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Bulk restore (batched)
    // ------------------------------------------------------------------

    public boolean restoreAllArpEntries() {
        boolean locked = false;
        try {
            locked = restoreLock.tryLock(2, TimeUnit.SECONDS);
            if (!locked) {
                Log.w(TAG, "Could not acquire lock for ARP restoration");
                return false;
            }
            if (!isRestoring.compareAndSet(false, true)) {
                Log.w(TAG, "Already restoring ARP");
                return false;
            }

            final Map<String, String> cacheSnapshot;
            final Map<String, String> devSnapshot;
            synchronized (lock) {
                cacheSnapshot = new HashMap<>(arpCache);
                devSnapshot = new HashMap<>(arpDevices);
            }

            int totalEntries = cacheSnapshot.size();
            Log.d(TAG, "Restoring all ARP entries... (" + totalEntries + " entries)");

            if (totalEntries == 0) {
                Log.w(TAG, "ARP cache is empty! Nothing to restore.");
                lastRestoreSuccess = false;
                lastRestoreTime = System.currentTimeMillis();
                return false;
            }

            // Build a single shell script that runs many `ip neigh replace`
            StringBuilder batch = new StringBuilder();
            int batchSize = 0;
            int successCount = 0;

            for (Map.Entry<String, String> entry : cacheSnapshot.entrySet()) {
                String ip = entry.getKey();
                String mac = entry.getValue();
                String dev = devSnapshot.get(ip);
                if (dev == null || dev.isEmpty()) dev = interfaceName;

                batch.append("ip neigh replace ")
                        .append(shQ(ip)).append(" lladdr ")
                        .append(shQ(mac)).append(" dev ")
                        .append(shQ(dev)).append(" nud permanent;");

                if (++batchSize >= BATCH_SIZE) {
                    if (shellManager.executeCommandBool(batch.toString())) {
                        successCount += batchSize;
                    }
                    batch.setLength(0);
                    batchSize = 0;
                }
            }
            if (batch.length() > 0) {
                if (shellManager.executeCommandBool(batch.toString())) {
                    successCount += batchSize;
                }
            }

            boolean success = successCount > 0
                    && successCount >= Math.ceil(totalEntries * 0.7);
            lastRestoreSuccess = success;
            lastRestoreTime = System.currentTimeMillis();
            Log.d(TAG, "Restored " + successCount + "/" + totalEntries
                    + " ARP entries (success=" + success + ")");
            return success;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while waiting for restore lock", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error restoring ARP entries", e);
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

    // ------------------------------------------------------------------
    // Flush + restore (improved)
    // ------------------------------------------------------------------

    public boolean flushAndRestore() {
        long now = System.currentTimeMillis();
        // Reduced from 500ms to 100ms — only skip if called in rapid succession
        if (now - lastRestoreTime < 100) {
            Log.d(TAG, "Using cached restoration result (called within 100ms)");
            return lastRestoreSuccess;
        }

        boolean locked = false;
        try {
            locked = restoreLock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                Log.w(TAG, "Could not acquire lock for flushAndRestore");
                return false;
            }
            if (!isRestoring.compareAndSet(false, true)) {
                Log.w(TAG, "Already restoring ARP");
                return false;
            }

            // Take a snapshot of the cache
            Map<String, String> cacheSnapshot;
            Map<String, String> devSnapshot;
            synchronized (lock) {
                cacheSnapshot = new HashMap<>(arpCache);
                devSnapshot = new HashMap<>(arpDevices);
            }

            int totalEntries = cacheSnapshot.size();
            Log.d(TAG, "flushAndRestore: snapshot size=" + totalEntries);

            // If cache is empty, try to repopulate from live system
            if (totalEntries == 0) {
                Log.w(TAG, "ARP cache is empty! Reloading from system...");
                loadArpCache();
                synchronized (lock) {
                    cacheSnapshot = new HashMap<>(arpCache);
                    devSnapshot = new HashMap<>(arpDevices);
                }
                totalEntries = cacheSnapshot.size();
                Log.d(TAG, "After reload, cache size=" + totalEntries);

                if (totalEntries == 0) {
                    Log.e(TAG, "ARP cache still empty. Nothing to restore.");
                    lastRestoreSuccess = false;
                    lastRestoreTime = System.currentTimeMillis();
                    return false;
                }
            }

            // Flush the poisoned ARP cache
            Log.d(TAG, "Flushing ARP cache...");
            boolean flushed = flushArpCache();
            Log.d(TAG, "Flush result: " + flushed);

            // Wait for kernel to process the flush
            try { Thread.sleep(500); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastRestoreSuccess = false;
                return false;
            }

            // Restore entries in batches
            Log.d(TAG, "Restoring " + totalEntries + " ARP entries...");
            StringBuilder batch = new StringBuilder();
            int batchSize = 0;
            int successCount = 0;

            for (Map.Entry<String, String> entry : cacheSnapshot.entrySet()) {
                String ip = entry.getKey();
                String mac = entry.getValue();
                String dev = devSnapshot.get(ip);
                if (dev == null || dev.isEmpty()) dev = interfaceName;

                batch.append("ip neigh replace ")
                        .append(shQ(ip)).append(" lladdr ")
                        .append(shQ(mac)).append(" dev ")
                        .append(shQ(dev)).append(" nud permanent;");

                if (++batchSize >= 20) {
                    if (shellManager.executeCommandBool(batch.toString())) {
                        successCount += batchSize;
                    }
                    batch.setLength(0);
                    batchSize = 0;
                }
            }
            if (batch.length() > 0) {
                if (shellManager.executeCommandBool(batch.toString())) {
                    successCount += batchSize;
                }
            }

            boolean restored = successCount > 0
                    && successCount >= Math.ceil(totalEntries * 0.7);

            lastRestoreTime = System.currentTimeMillis();
            lastRestoreSuccess = flushed && restored;

            Log.d(TAG, "flushAndRestore complete: flushed=" + flushed
                    + ", restored=" + restored
                    + " (" + successCount + "/" + totalEntries + ")");
            return lastRestoreSuccess;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed", e);
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

    private boolean flushArpCache() {
        try {
            Log.d(TAG, "Attempting to flush ARP via ip neigh flush all");
            if (shellManager.executeCommandBool("ip neigh flush all")) {
                Log.d(TAG, "✅ Flushed ARP via ip neigh flush all");
                return true;
            }

            Log.d(TAG, "Attempting to flush ARP via ip -s neigh flush all");
            if (shellManager.executeCommandBool("ip -s neigh flush all")) {
                Log.d(TAG, "✅ Flushed ARP via ip -s neigh flush all");
                return true;
            }

            Log.d(TAG, "Flushing ARP via individual deletion");
            Map<String, String> snapshot;
            synchronized (lock) { snapshot = new HashMap<>(arpCache); }
            for (String ip : snapshot.keySet()) {
                shellManager.executeCommandBool(
                        "ip neigh del " + shQ(ip));
            }
            Thread.sleep(200);
            Log.d(TAG, "✅ Flushed ARP via individual deletion");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to flush ARP cache", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getCachedMac(String ip) {
        synchronized (lock) { return arpCache.get(ip); }
    }

    public void refreshCache() { loadArpCache(); }

    public boolean hasCachedEntry(String ip) {
        synchronized (lock) { return arpCache.containsKey(ip); }
    }

    public int getCacheSize() {
        synchronized (lock) { return arpCache.size(); }
    }

    public void setInterface(String iface) {
        if (iface != null && !iface.trim().isEmpty()) {
            this.interfaceName = iface.trim();
        }
    }

    public String getInterface() { return interfaceName; }

    public boolean isRestoring() { return isRestoring.get(); }

    public void clearCache() {
        synchronized (lock) {
            arpCache.clear();
            arpDevices.clear();
        }
    }

    public boolean waitForRestoreComplete(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (isRestoring.get()
                && (System.currentTimeMillis() - start) < timeoutMs) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isRestoring.get();
    }

    public void resetState() {
        restoreLock.lock();
        try {
            isRestoring.set(false);
            lastRestoreTime = 0;
            lastRestoreSuccess = false;
        } finally {
            restoreLock.unlock();
        }
    }
}