package com.app.netcut;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KilledDevicesManager {
    private static final String TAG = "KilledDevicesManager";
    private static final String PREF_NAME = "netcut_killed_devices";
    private static final String KEY_KILLED_DEVICES = "killed_devices_json";

    private static KilledDevicesManager instance;
    private final SharedPreferences prefs;
    private final Map<String, KilledDeviceInfo> killedDevices = new HashMap<>();

    public static class KilledDeviceInfo {
        public String mac;
        public String ip;
        public String vendor;
        public String name;
        public long timestamp;

        public KilledDeviceInfo(String mac, String ip, String vendor, String name) {
            this.mac = mac;
            this.ip = ip;
            this.vendor = vendor;
            this.name = name != null ? name : vendor != null ? vendor : "Unknown Device";
            this.timestamp = System.currentTimeMillis();
        }

        public KilledDeviceInfo(JSONObject json) throws JSONException {
            this.mac = json.getString("mac");
            this.ip = json.optString("ip", "");
            this.vendor = json.optString("vendor", "");
            this.name = json.optString("name", "Unknown Device");
            this.timestamp = json.optLong("timestamp", System.currentTimeMillis());
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("mac", mac);
            json.put("ip", ip);
            json.put("vendor", vendor);
            json.put("name", name);
            json.put("timestamp", timestamp);
            return json;
        }

        public Device toDevice() {
            Device d = new Device(ip, mac, vendor, false);
            d.isCut = true;
            return d;
        }
    }

    private KilledDevicesManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadKilledDevices();
    }

    public static synchronized KilledDevicesManager getInstance(Context context) {
        if (instance == null) {
            instance = new KilledDevicesManager(context);
        }
        return instance;
    }

    private void loadKilledDevices() {
        killedDevices.clear();
        String json = prefs.getString(KEY_KILLED_DEVICES, "");
        if (json.isEmpty()) return;

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                KilledDeviceInfo info = new KilledDeviceInfo(obj);
                killedDevices.put(info.mac, info);
            }
            Log.d(TAG, "Loaded " + killedDevices.size() + " killed devices");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load killed devices", e);
        }
    }

    private void saveKilledDevices() {
        try {
            JSONArray array = new JSONArray();
            for (KilledDeviceInfo info : killedDevices.values()) {
                array.put(info.toJson());
            }
            prefs.edit().putString(KEY_KILLED_DEVICES, array.toString()).apply();
            Log.d(TAG, "Saved " + killedDevices.size() + " killed devices");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save killed devices", e);
        }
    }

    public void addKilledDevice(Device device) {
        if (device == null || device.mac == null || device.mac.isEmpty()) return;

        KilledDeviceInfo info = new KilledDeviceInfo(
                device.mac,
                device.ip,
                device.vendor,
                device.vendor
        );
        killedDevices.put(device.mac, info);
        saveKilledDevices();
        Log.d(TAG, "Added killed device: " + device.mac);
    }

    public void removeKilledDevice(Device device) {
        if (device == null || device.mac == null || device.mac.isEmpty()) return;
        killedDevices.remove(device.mac);
        saveKilledDevices();
        Log.d(TAG, "Removed killed device: " + device.mac);
    }

    public void removeKilledDevice(String mac) {
        if (mac == null || mac.isEmpty()) return;
        killedDevices.remove(mac);
        saveKilledDevices();
        Log.d(TAG, "Removed killed device: " + mac);
    }

    public boolean isDeviceKilled(Device device) {
        if (device == null || device.mac == null || device.mac.isEmpty()) return false;
        return killedDevices.containsKey(device.mac);
    }

    public boolean isDeviceKilled(String mac) {
        return mac != null && killedDevices.containsKey(mac);
    }

    public KilledDeviceInfo getDeviceInfo(String mac) {
        return killedDevices.get(mac);
    }

    public void updateDeviceInfo(String mac, String ip, String vendor, String name) {
        KilledDeviceInfo info = killedDevices.get(mac);
        if (info != null) {
            if (ip != null && !ip.isEmpty()) info.ip = ip;
            if (vendor != null && !vendor.isEmpty()) info.vendor = vendor;
            if (name != null && !name.isEmpty()) info.name = name;
            saveKilledDevices();
        }
    }

    public void setDeviceName(String mac, String name) {
        KilledDeviceInfo info = killedDevices.get(mac);
        if (info != null && name != null && !name.isEmpty()) {
            info.name = name;
            saveKilledDevices();
        }
    }

    public List<KilledDeviceInfo> getKilledDevicesList() {
        return new ArrayList<>(killedDevices.values());
    }

    public List<Device> getKilledDevices() {
        List<Device> devices = new ArrayList<>();
        for (KilledDeviceInfo info : killedDevices.values()) {
            Device d = info.toDevice();
            d.isCut = true;
            devices.add(d);
        }
        return devices;
    }

    public void clearAll() {
        killedDevices.clear();
        saveKilledDevices();
    }

    public int getCount() {
        return killedDevices.size();
    }
}