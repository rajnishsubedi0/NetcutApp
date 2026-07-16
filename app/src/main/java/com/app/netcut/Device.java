package com.app.netcut;

import java.io.Serializable;

public class Device implements Serializable {
    private static final long serialVersionUID = 1L;

    public String ip;
    public String mac;
    public String vendor;
    public boolean isGateway;
    public boolean isCut;
    public String deviceId;

    public Device(String ip, String mac, String vendor, boolean isGateway) {
        this.ip = ip;
        this.mac = mac;
        this.vendor = vendor;
        this.isGateway = isGateway;
        this.isCut = false;
        this.deviceId = mac != null ? mac.replace(":", "_") : ip;
    }
}