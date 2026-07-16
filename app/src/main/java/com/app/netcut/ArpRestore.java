package com.app.netcut;

import static com.app.netcut.ShellUtils.shQ;
import static com.topjohnwu.superuser.internal.Utils.context;

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
    private static final long SHORT_CIRCUIT_MS = 200L;
    private static ArpRestore instance;

    private final Object lock = new Object();
    private final AtomicBoolean isRestoring = new AtomicBoolean(false);
    private final ReentrantLock restoreLock = new ReentrantLock();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private String interfaceName = "wlan0";
    private final RootShellManager shellManager;
    private final Map<String, Long> lastRestoreTimes = new HashMap<>();
    private final Map<String, Boolean> lastRestoreResults = new HashMap<>();

    private static class Snapshot {
        Map<String, String> arpCache = new HashMap<>();
        Map<String, String> arpDevices = new HashMap<>();
        String gatewayIp;
        String gatewayMac;
        String targetIp;
        String targetMac;
    }

    private ArpRestore() {
        this.shellManager = RootShellManager.getInstance();
        detectInterface();
    }

    public static synchronized ArpRestore getInstance() {
        if (instance == null) {
            instance = new ArpRestore();
        }
        return instance;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    private void detectInterface() {
        try {
            String iface = System.getProperty("wifi.interface");
            if (iface != null && !iface.trim().isEmpty()) {
                interfaceName = iface.trim();
                return;
            }
            if (shellManager != null && shellManager.isShellAvailable()) {
                List<String> lines = shellManager.executeCommandLines("ip link show");
                for (String line : lines) {
                    if (line != null && line.contains("wlan")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            interfaceName = parts[1].trim().split("\\s+")[0];
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "detectInterface failed", e);
        }
    }

    public void captureTrustedSnapshot(Context ctx, String sessionKey, String targetIp, String targetMac) {
        synchronized (lock) {
            Snapshot s = new Snapshot();
            s.targetIp = targetIp;
            s.targetMac = targetMac;
            try {
                String gateway = NetUtils.getGatewayIp(ctx);
                s.gatewayIp = gateway;
                loadTrustedArpWithIpNeigh(s);
                if (gateway != null) {
                    s.gatewayMac = s.arpCache.get(gateway);
                    if (s.gatewayMac == null || s.gatewayMac.isEmpty()) {
                        shellManager.executeCommandBool("ping -c1 -W1 " + shQ(gateway) + " >/dev/null 2>&1");
                        s.arpCache.clear();
                        s.arpDevices.clear();
                        loadTrustedArpWithIpNeigh(s);
                        s.gatewayMac = s.arpCache.get(gateway);
                    }
                }
                snapshots.put(sessionKey, s);
            } catch (Exception e) {
                Log.e(TAG, "captureTrustedSnapshot failed", e);
            }
        }
    }

    private void loadTrustedArpWithIpNeigh(Snapshot snapshot) {
        List<String> lines = shellManager.executeCommandLines("ip neigh show");
        if (lines == null) return;
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
            if (line.toLowerCase().contains(" failed") || line.toLowerCase().contains(" incomplete")) continue;
            if (mac != null && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                snapshot.arpCache.put(ip, mac);
                if (dev != null) snapshot.arpDevices.put(ip, dev);
            }
        }
    }

    public boolean flushAndRestoreFast(String sessionKey) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            Long lastTime = lastRestoreTimes.get(sessionKey);
            Boolean lastResult = lastRestoreResults.get(sessionKey);
            if (lastTime != null && (now - lastTime) < SHORT_CIRCUIT_MS) {
                return lastResult != null && lastResult;
            }
        }

        boolean locked = false;
        try {
            locked = restoreLock.tryLock(1, TimeUnit.SECONDS);
            if (!locked || !isRestoring.compareAndSet(false, true)) return false;

            Snapshot s;
            synchronized (lock) {
                s = snapshots.get(sessionKey);
                if (s != null) s = copySnapshot(s);
            }

            if (s == null) return genericRestore();

            // --- PATCH START: HIGH-SPEED RESTORATION LOGIC ---

            // 1. Restore Gateway ATOMICALLY
            String gatewayIp = (s.gatewayIp != null) ? s.gatewayIp : NetUtils.getGatewayIp(context);
            if (gatewayIp != null && s.gatewayMac != null) {
                String gwDev = s.arpDevices.getOrDefault(gatewayIp, interfaceName);

                // Atomic replace ensures no 'gap' where the IP is missing from the table
                shellManager.executeCommandBool(
                        "ip neigh replace " + shQ(gatewayIp) + " lladdr " + shQ(s.gatewayMac) + " dev " + shQ(gwDev) + " nud reachable"
                );

                // GRATUITOUS ARP TRIGGER: Force a ping to the gateway.
                // This creates an immediate outgoing packet that tells the target device
                // and the gateway that the connection is active again.
                shellManager.executeCommandBool("ping -c 1 -W 1 " + shQ(gatewayIp) + " >/dev/null 2>&1");
            }

            // 2. Restore target entry
            if (s.targetIp != null && s.targetMac != null) {
                String targetDev = s.arpDevices.getOrDefault(s.targetIp, interfaceName);
                shellManager.executeCommandBool(
                        "ip neigh replace " + shQ(s.targetIp) + " lladdr " + shQ(s.targetMac) + " dev " + shQ(targetDev) + " nud reachable"
                );

                // Optional: Trigger a ping to the target to resolve any hanging states on the target side
                shellManager.executeCommandBool("ping -c 1 -W 1 " + shQ(s.targetIp) + " >/dev/null 2>&1");
            }

            // 3. Final State Force: Ensure the neighbor is marked as REACHABLE
            if (gatewayIp != null) {
                shellManager.executeCommandBool("ip neigh change " + shQ(gatewayIp) + " reach");
            }

            // --- PATCH END ---

            synchronized (lock) {
                lastRestoreTimes.put(sessionKey, now);
                lastRestoreResults.put(sessionKey, true);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "restore failed", e);
            return false;
        } finally {
            isRestoring.set(false);
            if (locked) restoreLock.unlock();
        }
    }

    private boolean genericRestore() {
        try {
            String gatewayIp = NetUtils.getGatewayIp(context);
            if (gatewayIp == null) return false;
            List<String> lines = shellManager.executeCommandLines("ip neigh show " + shQ(gatewayIp));
            if (lines != null && !lines.isEmpty()) {
                for (String line : lines) {
                    if (line.contains("lladdr")) {
                        String[] parts = line.trim().split("\\s+");
                        for (int i = 0; i < parts.length - 1; i++) {
                            if ("lladdr".equals(parts[i])) {
                                String mac = parts[i + 1];
                                if (mac != null && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                                    shellManager.executeCommandBool("ip neigh replace " + shQ(gatewayIp) + " lladdr " + shQ(mac) + " dev " + shQ(interfaceName) + " nud reachable");
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) { return false; }
    }

    public void removeSnapshot(String sessionKey) {
        synchronized (lock) { snapshots.remove(sessionKey); }
    }

    private Snapshot copySnapshot(Snapshot src) {
        Snapshot s = new Snapshot();
        s.gatewayIp = src.gatewayIp;
        s.gatewayMac = src.gatewayMac;
        s.targetIp = src.targetIp;
        s.targetMac = src.targetMac;
        s.arpCache = new HashMap<>(src.arpCache);
        s.arpDevices = new HashMap<>(src.arpDevices);
        return s;
    }
}