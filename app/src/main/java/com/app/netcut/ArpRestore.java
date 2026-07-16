package com.app.netcut;

import static com.app.netcut.ShellUtils.shQ;
import static com.topjohnwu.superuser.internal.Utils.context;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ArpRestore {

    private static final String TAG = "ArpRestore";
    private static final long SHORT_CIRCUIT_MS = 120L;

    private static ArpRestore instance;

    private final Object lock = new Object();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private String interfaceName = "wlan0";
    private final RootShellManager shellManager;

    private final Map<String, Long> lastRestoreTimes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lastRestoreResults = new ConcurrentHashMap<>();

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
                if (lines != null) {
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

                    if ((s.gatewayMac == null || s.gatewayMac.isEmpty()) && shellManager != null) {
                        shellManager.executeCommandBool("ip neigh show " + shQ(gateway) + " >/dev/null 2>&1");
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
        if (shellManager == null) return;

        List<String> lines = shellManager.executeCommandLines("ip neigh show");
        if (lines == null) return;

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;

            String lower = line.toLowerCase();
            if (lower.contains(" failed") || lower.contains(" incomplete")) continue;

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4) continue;

            String ip = parts[0];
            String mac = null;
            String dev = null;

            for (int i = 0; i < parts.length - 1; i++) {
                if ("lladdr".equals(parts[i])) {
                    mac = parts[i + 1];
                } else if ("dev".equals(parts[i])) {
                    dev = parts[i + 1];
                }
            }

            if (mac != null && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                snapshot.arpCache.put(ip, mac);
                if (dev != null) {
                    snapshot.arpDevices.put(ip, dev);
                }
            }
        }
    }

    public boolean flushAndRestoreFast(String sessionKey) {
        long now = System.currentTimeMillis();

        Long lastTime = lastRestoreTimes.get(sessionKey);
        Boolean lastResult = lastRestoreResults.get(sessionKey);
        if (lastTime != null && (now - lastTime) < SHORT_CIRCUIT_MS) {
            return lastResult != null && lastResult;
        }

        try {
            Snapshot s;
            synchronized (lock) {
                s = snapshots.get(sessionKey);
                if (s != null) s = copySnapshot(s);
            }

            boolean result = (s != null) ? restoreFromSnapshot(s) : genericRestore();

            lastRestoreTimes.put(sessionKey, now);
            lastRestoreResults.put(sessionKey, result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "flushAndRestoreFast failed", e);
            lastRestoreTimes.put(sessionKey, now);
            lastRestoreResults.put(sessionKey, false);
            return false;
        }
    }

    private boolean restoreFromSnapshot(Snapshot s) {
        if (shellManager == null || !shellManager.isShellAvailable()) return false;

        boolean didSomething = false;

        String gatewayIp = s.gatewayIp;
        if (gatewayIp == null || gatewayIp.isEmpty()) {
            try {
                gatewayIp = NetUtils.getGatewayIp(context);
            } catch (Exception ignored) {
            }
        }

        // FIXED: DO NOT flush the entire ARP table. Flushing deletes the gateway's
        // MAC entry, causing Android's connectivity stack to momentarily think
        // the Wi-Fi is broken and disconnect.

        if (gatewayIp != null && s.gatewayMac != null && !s.gatewayMac.isEmpty()) {
            String gwDev = s.arpDevices.getOrDefault(gatewayIp, interfaceName);
            // 'nud reachable' forces the kernel to accept this entry immediately without probing
            boolean ok = shellManager.executeCommandBool(
                    "ip neigh replace " + shQ(gatewayIp) +
                            " lladdr " + shQ(s.gatewayMac) +
                            " dev " + shQ(gwDev) +
                            " nud reachable"
            );
            didSomething = didSomething || ok;
        }

        if (s.targetIp != null && !s.targetIp.isEmpty()
                && s.targetMac != null && !s.targetMac.isEmpty()) {
            String targetDev = s.arpDevices.getOrDefault(s.targetIp, interfaceName);
            boolean ok = shellManager.executeCommandBool(
                    "ip neigh replace " + shQ(s.targetIp) +
                            " lladdr " + shQ(s.targetMac) +
                            " dev " + shQ(targetDev) +
                            " nud reachable"
            );
            didSomething = didSomething || ok;
        }

        return didSomething;
    }

    private boolean genericRestore() {
        try {
            if (shellManager == null || !shellManager.isShellAvailable()) return false;

            String gatewayIp = NetUtils.getGatewayIp(context);
            if (gatewayIp == null || gatewayIp.isEmpty()) return false;

            // Do not flush. Just try to read and reinforce the existing correct entry.
            List<String> lines = shellManager.executeCommandLines("ip neigh show " + shQ(gatewayIp));
            if (lines == null || lines.isEmpty()) return false;

            for (String line : lines) {
                if (line == null || !line.contains("lladdr")) continue;

                String[] parts = line.trim().split("\\s+");
                String mac = null;
                String dev = interfaceName;

                for (int i = 0; i < parts.length - 1; i++) {
                    if ("lladdr".equals(parts[i])) {
                        mac = parts[i + 1];
                    } else if ("dev".equals(parts[i])) {
                        dev = parts[i + 1];
                    }
                }

                if (mac != null && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                    return shellManager.executeCommandBool(
                            "ip neigh replace " + shQ(gatewayIp) +
                                    " lladdr " + shQ(mac) +
                                    " dev " + shQ(dev) +
                                    " nud reachable"
                    );
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
        lastRestoreTimes.remove(sessionKey);
        lastRestoreResults.remove(sessionKey);
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