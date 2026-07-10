package com.app.netcut;
public class Device {
    public String ip;
    public String mac;
    public String vendor;
    public boolean isGateway;

    public Device(String ip, String mac, String vendor, boolean isGateway) {
        this.ip = ip;
        this.mac = mac;
        this.vendor = vendor == null ? "Unknown" : vendor;
        this.isGateway = isGateway;
    }
}