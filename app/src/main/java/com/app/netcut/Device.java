package com.app.netcut;

public class Device {
    public String ip;
    public String mac;
    public String vendor;
    public boolean isGateway;
    public boolean isCut;
    public String deviceId;
    public boolean isOnline;

    public Device(String ip, String mac, String vendor, boolean isGateway) {
        this(ip, mac, vendor, isGateway, false);
    }

    public Device(String ip, String mac, String vendor, boolean isGateway, boolean isCut) {
        this.ip = ip;
        this.mac = mac;
        this.vendor = vendor;
        this.isGateway = isGateway;
        this.isCut = isCut;
        this.deviceId = buildDeviceId(ip, mac);
        this.isOnline = true;
    }

    public static String buildDeviceId(String ip, String mac) {
        if (mac != null && !mac.trim().isEmpty()) {
            return mac.replace(":", "_").replace("-", "_").toLowerCase();
        }
        return ip == null ? "unknown" : ip.replace(".", "_");
    }
}