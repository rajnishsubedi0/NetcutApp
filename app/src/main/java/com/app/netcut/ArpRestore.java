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

    // trusted snapshot captured before poisoning
    private final Map<String, String> trustedArpCache = new HashMap<>();
    private final Map<String, String> trustedArpDevices = new HashMap<>();

    private String trustedGatewayIp;
    private String trustedGatewayMac;

    private String interfaceName = "wlan0";
    private final RootShellManager shellManager;

    private long lastRestoreTime = 0;
    private boolean lastRestoreSuccess = false;

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
    public String getInterfaceName(){
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

    public void captureTrustedSnapshot(Context ctx) {
        synchronized (lock) {
            trustedArpCache.clear();
            trustedArpDevices.clear();
            trustedGatewayIp = null;
            trustedGatewayMac = null;

            try {
                String gateway = NetUtils.getGatewayIp(ctx);
                trustedGatewayIp = gateway;

                loadTrustedArpWithIpNeigh();

                if (gateway != null) {
                    trustedGatewayMac = trustedArpCache.get(gateway);

                    if (trustedGatewayMac == null || trustedGatewayMac.isEmpty()) {
                        shellManager.executeCommandBool("ping -c1 -W1 " + shQ(gateway) + " >/dev/null 2>&1");
                        trustedArpCache.clear();
                        trustedArpDevices.clear();
                        loadTrustedArpWithIpNeigh();
                        trustedGatewayMac = trustedArpCache.get(gateway);
                    }
                }

                Log.d(TAG, "Trusted snapshot captured: entries=" + trustedArpCache.size()
                        + " gateway=" + trustedGatewayIp
                        + " gwMac=" + trustedGatewayMac);
            } catch (Exception e) {
                Log.e(TAG, "captureTrustedSnapshot failed", e);
            }
        }
    }

    private void loadTrustedArpWithIpNeigh() {
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

            if (!badState && mac != null
                    && !"00:00:00:00:00:00".equalsIgnoreCase(mac)) {
                trustedArpCache.put(ip, mac);
                if (dev != null && !dev.isEmpty()) {
                    trustedArpDevices.put(ip, dev);
                }
            }
        }
    }

    public boolean flushAndRestoreFast() {
        long now = System.currentTimeMillis();
        if (now - lastRestoreTime < SHORT_CIRCUIT_MS) {
            return lastRestoreSuccess;
        }

        boolean locked = false;
        try {
            locked = restoreLock.tryLock(1, TimeUnit.SECONDS);
            if (!locked) return false;
            if (!isRestoring.compareAndSet(false, true)) return false;

            String gatewayIp;
            String gatewayMac;
            Map<String, String> cacheSnapshot;
            Map<String, String> devSnapshot;

            synchronized (lock) {
                gatewayIp = trustedGatewayIp;
                gatewayMac = trustedGatewayMac;
                cacheSnapshot = new HashMap<>(trustedArpCache);
                devSnapshot = new HashMap<>(trustedArpDevices);
            }

            if (gatewayIp == null || gatewayIp.isEmpty()) {
                gatewayIp = NetUtils.getGatewayIp(context);
            }

            if (gatewayIp == null || gatewayIp.isEmpty()) {
                lastRestoreSuccess = false;
                lastRestoreTime = now;
                return false;
            }

            // kill broken neighbor entries first
            shellManager.executeCommandBool("ip neigh del " + shQ(gatewayIp) + " dev " + shQ(interfaceName) + " 2>/dev/null || true");

            // restore gateway from trusted snapshot if available
            if (gatewayMac != null && !gatewayMac.isEmpty()) {
                String gwDev = devSnapshot.get(gatewayIp);
                if (gwDev == null || gwDev.isEmpty()) gwDev = interfaceName;

                shellManager.executeCommandBool(
                        "ip neigh replace " + shQ(gatewayIp) +
                                " lladdr " + shQ(gatewayMac) +
                                " dev " + shQ(gwDev) +
                                " nud reachable 2>/dev/null"
                );
            }

            // trigger fresh resolution / connectivity
            shellManager.executeCommandBool("ping -c1 -W1 " + shQ(gatewayIp) + " >/dev/null 2>&1 || true");

            // optionally restore other known entries
            StringBuilder cmd = new StringBuilder();
            for (Map.Entry<String, String> entry : cacheSnapshot.entrySet()) {
                String ip = entry.getKey();
                String mac = entry.getValue();

                if (ip.equals(gatewayIp)) continue;

                String dev = devSnapshot.get(ip);
                if (dev == null || dev.isEmpty()) dev = interfaceName;

                cmd.append("ip neigh replace ")
                        .append(shQ(ip)).append(" lladdr ")
                        .append(shQ(mac)).append(" dev ")
                        .append(shQ(dev)).append(" nud reachable 2>/dev/null || true; ");
            }

            if (cmd.length() > 0) {
                shellManager.executeCommandBool(cmd.toString());
            }

            lastRestoreSuccess = true;
            lastRestoreTime = now;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "flushAndRestoreFast failed", e);
            lastRestoreSuccess = false;
            return false;
        } finally {
            isRestoring.set(false);
            if (locked) {
                try { restoreLock.unlock(); } catch (Exception ignored) {}
            }
        }
    }
}