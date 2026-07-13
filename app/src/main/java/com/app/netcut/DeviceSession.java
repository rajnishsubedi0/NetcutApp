package com.app.netcut;

public class DeviceSession {
    public final String sessionKey;
    public final Device device;

    public NetcutRunner runner;
    public int pid = -1;
    public boolean running = false;
    public boolean stopping = false;

    public DeviceSession(String sessionKey, Device device) {
        this.sessionKey = sessionKey;
        this.device = device;
    }
}