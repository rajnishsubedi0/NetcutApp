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

    private long lastRestoreTime = 0;
    private boolean lastRestoreSuccess = false;

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

                Log.d(TAG, "Snapshot captured for " + sessionKey
                        + " entries=" + s.arpCache.size()
                        + " gateway=" + s.gatewayIp
                        + " target=" + s.targetIp);
            } catch (Exception e) {
                Log.e(TAG, "captureTrustedSnapshot failed for " + sessionKey, e);
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
            boolean badState = false;

            for (int i = 0; i < parts.length - 1; i++) {
                if ("lladdr".equals(parts[i])) mac = parts[i + 1];
                else if ("dev".equals(parts[i])) dev = parts[i + 1];
            }

            String lower = line.toLowerCase();
            if (lower.contains(" failed") || lower.contains(" incomplete")) {
                badState = true;
            }

            if (!badState && mac != null && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                snapshot.arpCache.put(ip, mac);
                if (dev != null && !dev.isEmpty()) {
                    snapshot.arpDevices.put(ip, dev);
                }
            }
        }
    }

    public boolean flushAndRestoreFast(String sessionKey) {
        long now = System.currentTimeMillis();
        if (now - lastRestoreTime < SHORT_CIRCUIT_MS) {
            return lastRestoreSuccess;
        }

        boolean locked = false;
        try {
            locked = restoreLock.tryLock(1, TimeUnit.SECONDS);
            if (!locked) return false;
            if (!isRestoring.compareAndSet(false, true)) return false;

            Snapshot s;
            synchronized (lock) {
                s = snapshots.get(sessionKey);
                if (s == null) {
                    // If no snapshot, try a generic restore for the gateway
                    Log.w(TAG, "No snapshot for " + sessionKey + ", trying generic restore");
                    return genericRestore();
                }
                s = copySnapshot(s);
            }

            String gatewayIp = s.gatewayIp;
            String gatewayMac = s.gatewayMac;

            if (gatewayIp == null || gatewayIp.isEmpty()) {
                gatewayIp = NetUtils.getGatewayIp(context);
            }

            if (gatewayIp == null || gatewayIp.isEmpty()) {
                lastRestoreSuccess = false;
                lastRestoreTime = now;
                return false;
            }

            // Only delete and restore the specific target IP and gateway
            // This ensures other sessions are not affected
            if (s.targetIp != null && !s.targetIp.isEmpty()) {
                shellManager.executeCommandBool("ip neigh del " + shQ(s.targetIp) + " dev " + shQ(interfaceName) + " 2>/dev/null || true");

                if (s.targetMac != null && !s.targetMac.isEmpty()) {
                    String dev = s.arpDevices.get(s.targetIp);
                    if (dev == null || dev.isEmpty()) dev = interfaceName;

                    shellManager.executeCommandBool(
                            "ip neigh replace " + shQ(s.targetIp) +
                                    " lladdr " + shQ(s.targetMac) +
                                    " dev " + shQ(dev) +
                                    " nud reachable 2>/dev/null"
                    );
                }
            }

            // Always restore gateway if we have a valid MAC
            if (gatewayMac != null && !gatewayMac.isEmpty()) {
                shellManager.executeCommandBool("ip neigh del " + shQ(gatewayIp) + " dev " + shQ(interfaceName) + " 2>/dev/null || true");

                String gwDev = s.arpDevices.get(gatewayIp);
                if (gwDev == null || gwDev.isEmpty()) gwDev = interfaceName;

                shellManager.executeCommandBool(
                        "ip neigh replace " + shQ(gatewayIp) +
                                " lladdr " + shQ(gatewayMac) +
                                " dev " + shQ(gwDev) +
                                " nud reachable 2>/dev/null"
                );
            }

            shellManager.executeCommandBool("ping -c1 -W1 " + shQ(gatewayIp) + " >/dev/null 2>&1 || true");

            lastRestoreSuccess = true;
            lastRestoreTime = now;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "flushAndRestoreFast failed for " + sessionKey, e);
            lastRestoreSuccess = false;
            return false;
        } finally {
            isRestoring.set(false);
            if (locked) {
                try { restoreLock.unlock(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean genericRestore() {
        try {
            String gatewayIp = NetUtils.getGatewayIp(context);
            if (gatewayIp == null || gatewayIp.isEmpty()) return false;

            // Try to get gateway MAC from current ARP cache
            List<String> lines = shellManager.executeCommandLines("ip neigh show " + shQ(gatewayIp));
            if (lines != null && !lines.isEmpty()) {
                for (String line : lines) {
                    if (line.contains("lladdr")) {
                        String[] parts = line.trim().split("\\s+");
                        for (int i = 0; i < parts.length - 1; i++) {
                            if ("lladdr".equals(parts[i])) {
                                String mac = parts[i + 1];
                                if (mac != null && !mac.isEmpty() && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                                    // Restore gateway
                                    shellManager.executeCommandBool("ip neigh del " + shQ(gatewayIp) + " dev " + shQ(interfaceName) + " 2>/dev/null || true");
                                    shellManager.executeCommandBool(
                                            "ip neigh replace " + shQ(gatewayIp) +
                                                    " lladdr " + shQ(mac) +
                                                    " dev " + shQ(interfaceName) +
                                                    " nud reachable 2>/dev/null"
                                    );
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "genericRestore failed", e);
            return false;
        }
    }

    public void removeSnapshot(String sessionKey) {
        synchronized (lock) {
            snapshots.remove(sessionKey);
        }
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