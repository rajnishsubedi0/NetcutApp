package com.app.netcut;



import java.util.HashMap;
import java.util.Map;

public class OuiLookup {
    private static final Map<String, String> OUI = new HashMap<>();
    static {
        OUI.put("00:1A:11", "Google");
        OUI.put("FC:FB:FB", "Cisco");
        OUI.put("3C:5A:B4", "Google");
        OUI.put("A4:C3:F0", "Intel");
        OUI.put("B8:27:EB", "Raspberry Pi");
        OUI.put("DC:A6:32", "Raspberry Pi");
        OUI.put("F0:99:BF", "Apple");
        OUI.put("BC:D0:74", "Samsung");
        OUI.put("00:0C:29", "VMware");
        // add more as needed…
    }

    public static String lookup(String mac) {
        if (mac == null || mac.length() < 8) return "Unknown";
        String key = mac.substring(0, 8).toUpperCase();
        return OUI.getOrDefault(key, "Unknown");
    }
}