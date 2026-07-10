package com.app.netcut;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class NetUtils {

    public static String getGatewayIp(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        int gw = wm.getDhcpInfo().gateway;
        return String.format("%d.%d.%d.%d",
                (gw & 0xff), (gw >> 8 & 0xff),
                (gw >> 16 & 0xff), (gw >> 24 & 0xff));
    }

    public static String getLocalIp(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        int ip = wm.getConnectionInfo().getIpAddress();
        return String.format("%d.%d.%d.%d",
                (ip & 0xff), (ip >> 8 & 0xff),
                (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }

    /** Read /proc/net/arp to enrich scan results with MAC addresses. */
    public static List<String[]> readArpTable() {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            br.readLine(); // header
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 6) {
                    // ip, hw_type, flags, mac, mask, device
                    if (!"00:00:00:00:00:00".equals(parts[3])) {
                        out.add(new String[]{parts[0], parts[3], parts[5]});
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Quick ping to populate ARP cache. */
    public static boolean ping(String ip, int timeoutMs) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isReachable(timeoutMs);
        } catch (Exception e) {
            return false;
        }
    }
}