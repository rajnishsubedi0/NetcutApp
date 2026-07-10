package com.app.netcut;

import android.content.Context;

import com.app.netcut.Device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HostScanner {

    public interface ScanCallback {
        void onProgress(int done, int total);
        void onFinished(List<Device> devices);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(32);
    private volatile boolean cancelled = false;
    private Thread scanThread;
    private RootShellManager shellManager;

    public HostScanner() {
        shellManager = RootShellManager.getInstance();
    }

    public void cancel() {
        cancelled = true;
        if (scanThread != null) {
            scanThread.interrupt();
        }
    }

    public void scan(Context ctx, ScanCallback cb) {
        if (scanThread != null && scanThread.isAlive()) {
            return;
        }

        cancelled = false;
        scanThread = new Thread(() -> {
            try {
                performScan(ctx, cb);
            } catch (Exception e) {
                e.printStackTrace();
                if (cb != null) {
                    cb.onFinished(new ArrayList<>());
                }
            }
        });
        scanThread.start();
    }

    private void performScan(Context ctx, ScanCallback cb) {
        String localIp = NetUtils.getLocalIp(ctx);
        String gateway = NetUtils.getGatewayIp(ctx);
        String prefix = localIp.substring(0, localIp.lastIndexOf('.') + 1);

        final int total = 254;
        final AtomicInteger done = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(254);

        // Ping all hosts
        for (int i = 1; i <= 254; i++) {
            if (cancelled) break;

            final String ip = prefix + i;
            pool.submit(() -> {
                try {
                    if (!cancelled) {
                        NetUtils.ping(ip, 400);
                    }
                } finally {
                    int completed = done.incrementAndGet();
                    if (cb != null) {
                        cb.onProgress(completed, total);
                    }
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!cancelled) {
            // Give kernel time to populate ARP cache
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            // Merge ARP table using libsu
            Map<String, Device> found = new HashMap<>();

            // Try to read ARP table using libsu
            List<String> arpLines = shellManager.executeCommandLines("cat /proc/net/arp");
            for (String line : arpLines) {
                if (line.startsWith("IP")) continue; // Skip header
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0];
                    String mac = parts[3];
                    if (!"00:00:00:00:00:00".equals(mac) && !"incomplete".equals(mac)) {
                        boolean isGw = ip.equals(gateway);
                        String vendor = OuiLookup.lookup(mac);
                        found.put(ip, new Device(ip, mac, vendor, isGw));
                    }
                }
            }

            List<Device> list = new ArrayList<>(found.values());
            list.sort((a, b) -> {
                if (a.isGateway != b.isGateway) return a.isGateway ? -1 : 1;
                return Integer.compare(lastOctet(a.ip), lastOctet(b.ip));
            });

            if (cb != null) {
                cb.onFinished(list);
            }
        } else {
            if (cb != null) {
                cb.onFinished(new ArrayList<>());
            }
        }
    }

    private static int lastOctet(String ip) {
        try {
            return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        } catch (Exception e) {
            return 0;
        }
    }
}