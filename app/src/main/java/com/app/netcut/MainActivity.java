package com.app.netcut;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.app.netcut.KilledDevicesManager.KilledDeviceInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvGateway, tvIface, tvStatus, tvKilledCount;
    private Button btnScan, btnStop, btnRestoreArp, btnShowKilled, btnShowDevices;
    private ListView lvDevices;

    private final List<Device> devices = new ArrayList<>();
    private DeviceAdapter adapter;
    private KilledDevicesFragment killedFragment;
    private KilledDevicesManager killedManager;

    private final HostScanner scanner = new HostScanner();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArpRestore arpRestore;
    private RootShellManager shellManager;

    private String gatewayIp;
    private String iface;

    private boolean isScanning = false;
    private boolean isRootAvailable = false;
    private boolean isStopping = false;
    private boolean showingKilled = false;
    private boolean autoScanDone = false;

    private final Map<String, DeviceSession> sessionsByDeviceId = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        startService(new Intent(this, CleanupService.class));

        killedManager = KilledDevicesManager.getInstance(this);
        killedFragment = new KilledDevicesFragment();

        initializeViews();

        try {
            new ExtractFile().extractTheFile(this, R.raw.netcut, "netcut");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        shellManager = RootShellManager.getInstance();
        arpRestore = ArpRestore.getInstance();
        iface = arpRestore.getInterfaceName();

        checkRootAccess();
        requestPermissions();
        initializeNetworkInfo();
        setupClickListeners();
        updateKilledCount();

        // Auto-scan after a short delay
        mainHandler.postDelayed(this::performAutoScan, 1500);
    }

    @Override
    protected void onDestroy() {
        stopAllSessions();
        if (shellManager != null) shellManager.close();
        super.onDestroy();
    }

    private void initializeViews() {
        tvGateway = findViewById(R.id.tvGateway);
        tvIface = findViewById(R.id.tvIface);
        tvStatus = findViewById(R.id.tvStatus);
        tvKilledCount = findViewById(R.id.tvKilledCount);
        btnScan = findViewById(R.id.btnScan);
        btnStop = findViewById(R.id.btnStop);
        btnRestoreArp = findViewById(R.id.btnRestoreArp);
        btnShowKilled = findViewById(R.id.btnShowKilled);
        btnShowDevices = findViewById(R.id.btnShowDevices);
        lvDevices = findViewById(R.id.lvDevices);

        adapter = new DeviceAdapter(this, devices);
        lvDevices.setAdapter(adapter);

        btnStop.setEnabled(false);
        btnRestoreArp.setEnabled(true);
        btnShowDevices.setVisibility(View.GONE);
        tvStatus.setText("Ready");
    }

    private void checkRootAccess() {
        isRootAvailable = shellManager != null && shellManager.hasRootAccess();
        Toast.makeText(this,
                "Root: " + (isRootAvailable ? "✓ Available" : "✗ Not available"),
                Toast.LENGTH_LONG).show();

        if (!isRootAvailable) {
            new AlertDialog.Builder(this)
                    .setTitle("Root Required")
                    .setMessage("This app requires root access to function properly.")
                    .setPositiveButton("Check Again", (d, w) -> checkRootAccess())
                    .setNegativeButton("Continue", null)
                    .show();
        }
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissions.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void initializeNetworkInfo() {
        try {
            gatewayIp = NetUtils.getGatewayIp(this);
            tvGateway.setText("🌐 Gateway: " + gatewayIp);
            tvIface.setText("📶 Iface: " + iface);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to get network info", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "initializeNetworkInfo failed", e);
        }
    }

    private void setupClickListeners() {
        btnScan.setOnClickListener(v -> startScan());
        btnStop.setOnClickListener(v -> stopAllSessions());
        btnRestoreArp.setOnClickListener(v -> restoreAllArp());
        btnShowKilled.setOnClickListener(v -> showKilledDevices());
        btnShowDevices.setOnClickListener(v -> showDevices());

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            Device d = devices.get(position);

            if (d.isGateway) {
                Toast.makeText(this, "Cannot target gateway", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isRootAvailable) {
                Toast.makeText(this, "Root access required", Toast.LENGTH_SHORT).show();
                return;
            }

            showDeviceDialog(d);
        });
    }

    private void performAutoScan() {
        if (autoScanDone) return;
        autoScanDone = true;

        if (isRootAvailable && !isScanning && devices.isEmpty()) {
            Log.d(TAG, "Performing auto-scan...");
            Toast.makeText(this, "Auto-scanning network...", Toast.LENGTH_SHORT).show();
            startScan();
        }
    }

    private void showKilledDevices() {
        showingKilled = true;
        btnShowKilled.setVisibility(View.GONE);
        btnShowDevices.setVisibility(View.VISIBLE);
        tvStatus.setText("📋 Killed Devices (" + killedManager.getCount() + ")");

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.fragment_container, killedFragment);
        ft.commit();

        lvDevices.setVisibility(View.GONE);
        findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);

        if (killedFragment != null) {
            killedFragment.refresh();
        }
    }

    private void showDevices() {
        showingKilled = false;
        btnShowKilled.setVisibility(View.VISIBLE);
        btnShowDevices.setVisibility(View.GONE);
        tvStatus.setText("📱 Devices (" + devices.size() + ")");

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.remove(killedFragment);
        ft.commit();

        lvDevices.setVisibility(View.VISIBLE);
        findViewById(R.id.fragment_container).setVisibility(View.GONE);
        updateKilledCount();
    }

    private void updateKilledCount() {
        // Must run on UI thread
        mainHandler.post(() -> {
            if (tvKilledCount != null) {
                int count = killedManager.getCount();
                tvKilledCount.setText("⚡ " + count);
                if (count > 0 && !showingKilled) {
                    btnShowKilled.setVisibility(View.VISIBLE);
                    btnShowKilled.setText("📋 Killed (" + count + ")");
                } else if (count == 0) {
                    btnShowKilled.setVisibility(View.GONE);
                }
            }
        });
    }

    private void startScan() {
        if (isScanning) {
            Toast.makeText(this, "Scan already running", Toast.LENGTH_SHORT).show();
            return;
        }

        devices.clear();
        adapter.notifyDataSetChanged();
        isScanning = true;
        btnScan.setEnabled(false);
        btnScan.setText("⏳ Scanning...");
        tvStatus.setText("📡 Scanning network...");

        scanner.scan(this, new HostScanner.ScanCallback() {
            @Override
            public void onProgress(int done, int total) {
                mainHandler.post(() -> {
                    tvStatus.setText("📡 Scanning " + done + "/" + total);
                    if (total > 0) {
                        btnScan.setText("⏳ " + done + "/" + total);
                    }
                });
            }

            @Override
            public void onFinished(List<Device> found) {
                mainHandler.post(() -> {
                    devices.clear();
                    devices.addAll(found);

                    // Check for killed devices
                    for (Device d : devices) {
                        DeviceSession s = sessionsByDeviceId.get(d.deviceId);
                        boolean wasKilled = killedManager.isDeviceKilled(d);

                        if (wasKilled && (s == null || !s.running)) {
                            d.isCut = true;
                            // Restore this session
                            restoreKilledSession(d);
                        } else if (s != null && s.running) {
                            d.isCut = true;
                        } else {
                            d.isCut = false;
                        }
                    }

                    // Update killed fragment with device details
                    if (killedFragment != null) {
                        for (Device d : found) {
                            killedFragment.updateDevice(d);
                        }
                    }

                    adapter.notifyDataSetChanged();
                    isScanning = false;
                    btnScan.setEnabled(true);
                    btnScan.setText("🔍 Scan");
                    tvStatus.setText("✅ Found " + found.size() + " devices. Tap to kill.");
                    updateKilledCount();
                });
            }
        });
    }

    private void restoreKilledSession(Device d) {
        if (d != null && d.mac != null && !d.mac.isEmpty()) {
            Log.d(TAG, "Auto-restoring killed session for: " + d.mac);
            startSession(d);
        }
    }

    private void showDeviceDialog(Device d) {
        DeviceSession session = sessionsByDeviceId.get(d.deviceId);
        boolean isRunning = session != null && session.running;
        boolean isKilled = killedManager.isDeviceKilled(d);

        String title = isRunning ? "⚡ Manage Target" : "🎯 Target Device";
        String action = isRunning ? "🔄 Unkill" : "🔪 Kill";

        String msg = "📱 IP: " + d.ip +
                "\n🔗 MAC: " + d.mac +
                "\n🏷️ Vendor: " + d.vendor +
                "\n📊 State: " + (isRunning ? "🔴 Cut" : (isKilled ? "💀 Killed (saved)" : "🟢 Online"));

        if (isKilled) {
            KilledDeviceInfo info = killedManager.getDeviceInfo(d.mac);
            if (info != null && info.name != null && !info.name.isEmpty()) {
                msg += "\n✏️ Name: " + info.name;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(action, (dialog, which) -> {
                    if (isRunning) {
                        stopSession(d);
                    } else {
                        startSession(d);
                    }
                })
                .setNeutralButton("Set Name", (dialog, which) -> showSetNameDialog(d))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSetNameDialog(Device d) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("✏️ Set Custom Name");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint("Enter device name");

        KilledDeviceInfo info = killedManager.getDeviceInfo(d.mac);
        if (info != null && info.name != null && !info.name.isEmpty()) {
            input.setText(info.name);
        }

        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                killedManager.setDeviceName(d.mac, name);
                updateKilledCount();
                if (killedFragment != null) killedFragment.refresh();
                Toast.makeText(this, "✅ Device name updated to: " + name, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void startSession(Device d) {
        String sessionKey = d.deviceId;

        DeviceSession existing = sessionsByDeviceId.get(sessionKey);
        if (existing != null && existing.running) {
            Toast.makeText(this, "Already active for " + d.ip, Toast.LENGTH_SHORT).show();
            return;
        }

        DeviceSession session = new DeviceSession(sessionKey, d);

        NetcutRunner runner = new NetcutRunner(
                this,
                sessionKey,
                line -> Log.d("NETCUT", line),
                new NetcutRunner.StateListener() {
                    @Override
                    public void onStarted(int pid, NetcutRunner.LaunchMode mode) {
                        mainHandler.post(() -> {
                            session.pid = pid;
                            session.running = true;
                            session.stopping = false;
                            d.isCut = true;

                            killedManager.addKilledDevice(d);
                            if (killedFragment != null) killedFragment.refresh();
                            updateKilledCount();

                            adapter.notifyDataSetChanged();
                            tvStatus.setText("🔴 Running for " + d.ip + " (pid: " + pid + ")");
                            btnStop.setEnabled(true);
                            btnScan.setEnabled(false);
                            Toast.makeText(MainActivity.this,
                                    "🔪 Started killing " + d.ip,
                                    Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onStopped() {
                        mainHandler.post(() -> {
                            session.running = false;
                            session.stopping = false;
                            session.pid = -1;
                            d.isCut = false;

                            // Keep device in killed list if it was intentionally stopped
                            // The stopSession will handle removal if needed

                            adapter.notifyDataSetChanged();
                            tvStatus.setText("🟢 Stopped for " + d.ip);
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            isStopping = false;
                            updateKilledCount();
                            Toast.makeText(MainActivity.this,
                                    "✅ Internet restored for " + d.ip,
                                    Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onCrashed(String reason) {
                        mainHandler.post(() -> {
                            session.running = false;
                            session.stopping = false;
                            session.pid = -1;
                            d.isCut = true;
                            adapter.notifyDataSetChanged();
                            tvStatus.setText("⚠️ Crashed for " + d.ip);
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            isStopping = false;
                            Toast.makeText(MainActivity.this,
                                    "⚠️ Session crashed: " + d.ip,
                                    Toast.LENGTH_SHORT).show();
                        });

                        new Thread(() -> {
                            arpRestore.flushAndRestoreFast(sessionKey);
                        }).start();
                    }
                }
        );

        session.runner = runner;
        sessionsByDeviceId.put(sessionKey, session);

        new Thread(() -> {
            try {
                arpRestore.captureTrustedSnapshot(MainActivity.this, sessionKey, d.ip, d.mac);

                String args = "-i " + iface
                        + " --target " + d.ip
                        + " --gateway " + gatewayIp;

                runner.start(args);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start session for " + d.ip, e);
                mainHandler.post(() -> {
                    sessionsByDeviceId.remove(sessionKey);
                    d.isCut = false;
                    killedManager.removeKilledDevice(d);
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("❌ Failed to start for " + d.ip);
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    updateKilledCount();
                    Toast.makeText(MainActivity.this,
                            "Failed to start for " + d.ip,
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    public void unkillDevice(Device d) {
        if (d == null) return;

        DeviceSession session = sessionsByDeviceId.get(d.deviceId);
        if (session != null && session.running) {
            stopSession(d);
        } else {
            killedManager.removeKilledDevice(d);
            if (killedFragment != null) killedFragment.refresh();
            updateKilledCount();

            for (Device device : devices) {
                if (device.mac != null && device.mac.equals(d.mac)) {
                    device.isCut = false;
                    adapter.notifyDataSetChanged();
                    break;
                }
            }

            Toast.makeText(this, "✅ Device removed from killed list", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSession(Device d) {
        String sessionKey = d.deviceId;
        DeviceSession session = sessionsByDeviceId.get(sessionKey);

        if (session == null || !session.running) {
            Toast.makeText(this, "No active session for " + d.ip, Toast.LENGTH_SHORT).show();
            return;
        }

        if (isStopping) {
            Toast.makeText(this, "Already stopping...", Toast.LENGTH_SHORT).show();
            return;
        }

        isStopping = true;
        session.stopping = true;
        tvStatus.setText("⏹ Stopping " + d.ip + "...");
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        new Thread(() -> {
            try {
                Thread arpThread = new Thread(() -> {
                    try {
                        boolean restored = arpRestore.flushAndRestoreFast(sessionKey);
                        Log.d(TAG, "ARP restore for " + sessionKey + ": " + (restored ? "success" : "failed"));
                        if (!restored) {
                            arpRestore.captureTrustedSnapshot(MainActivity.this, sessionKey, d.ip, d.mac);
                            arpRestore.flushAndRestoreFast(sessionKey);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "ARP restore error for " + sessionKey, e);
                    }
                });
                arpThread.start();

                if (session.runner != null && session.running) {
                    session.runner.stop();
                }

                int waitCount = 0;
                while (session.running && waitCount < 10) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    waitCount++;
                }

                if (session.running) {
                    Log.w(TAG, "Runner still running, emergency stop for " + sessionKey);
                    if (session.runner != null) {
                        session.runner.emergencyStop();
                        session.runner.destroy();
                    }
                    arpRestore.flushAndRestoreFast(sessionKey);
                }

                try { arpThread.join(1000); } catch (InterruptedException ignored) {}

                // Remove from killed list only when explicitly unkilling
                mainHandler.post(() -> {
                    killedManager.removeKilledDevice(d);
                    if (killedFragment != null) killedFragment.refresh();
                    updateKilledCount();
                });

                mainHandler.post(() -> {
                    session.running = false;
                    session.stopping = false;
                    session.pid = -1;
                    d.isCut = false;
                    sessionsByDeviceId.remove(sessionKey);
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("✅ Internet restored for " + d.ip);
                    btnStop.setEnabled(false);
                    btnScan.setEnabled(true);
                    isStopping = false;
                    Toast.makeText(MainActivity.this,
                            "✅ Internet restored for " + d.ip,
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Stop session failed for " + d.ip, e);
                handleStopFailure(session, d, sessionKey);
            }
        }).start();
    }

    private void handleStopFailure(DeviceSession session, Device d, String sessionKey) {
        try {
            if (session.runner != null) {
                session.runner.emergencyStop();
                session.runner.destroy();
            }
            arpRestore.flushAndRestoreFast(sessionKey);
            mainHandler.post(() -> {
                killedManager.removeKilledDevice(d);
                if (killedFragment != null) killedFragment.refresh();
                updateKilledCount();
            });
        } catch (Exception ignored) {}

        mainHandler.post(() -> {
            sessionsByDeviceId.remove(sessionKey);
            d.isCut = false;
            adapter.notifyDataSetChanged();
            tvStatus.setText("🔄 Emergency stopped " + d.ip);
            btnStop.setEnabled(false);
            btnScan.setEnabled(true);
            isStopping = false;
            Toast.makeText(MainActivity.this,
                    "✅ Internet restored for " + d.ip,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void stopAllSessions() {
        if (sessionsByDeviceId.isEmpty()) {
            Toast.makeText(this, "No active sessions", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isStopping) {
            Toast.makeText(this, "Already stopping...", Toast.LENGTH_SHORT).show();
            return;
        }

        isStopping = true;
        tvStatus.setText("⏹ Stopping all sessions...");
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        new Thread(() -> {
            for (DeviceSession session : sessionsByDeviceId.values()) {
                if (session.running && session.runner != null) {
                    try {
                        session.runner.stop();
                        session.running = false;
                        session.pid = -1;
                        session.device.isCut = false;
                        arpRestore.flushAndRestoreFast(session.sessionKey);
                        // Don't remove from killed list here - keep them saved
                        // killedManager.removeKilledDevice(session.device);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to stop session " + session.sessionKey, e);
                        try {
                            session.runner.emergencyStop();
                            session.runner.destroy();
                            arpRestore.flushAndRestoreFast(session.sessionKey);
                            // Don't remove from killed list here
                            // killedManager.removeKilledDevice(session.device);
                        } catch (Exception ignored) {}
                    }
                }
            }

            sessionsByDeviceId.clear();

            mainHandler.post(() -> {
                if (killedFragment != null) killedFragment.refresh();
                updateKilledCount();
                adapter.notifyDataSetChanged();
                tvStatus.setText("✅ All sessions stopped (killed list preserved)");
                btnStop.setEnabled(false);
                btnScan.setEnabled(true);
                isStopping = false;
                Toast.makeText(MainActivity.this,
                        "✅ All sessions stopped. Killed devices preserved.",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void restoreAllArp() {
        if (!isRootAvailable) {
            Toast.makeText(this, "Root required for ARP restore", Toast.LENGTH_SHORT).show();
            return;
        }

        // If there are active sessions, ask user if they want to stop them
        if (!sessionsByDeviceId.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("🔄 Active Sessions Found")
                    .setMessage("There are active sessions running. Do you want to stop them and restore ARP?")
                    .setPositiveButton("Yes, Stop All", (d, w) -> {
                        stopAllSessions();
                        // After stopping, restore ARP
                        mainHandler.postDelayed(() -> {
                            if (sessionsByDeviceId.isEmpty()) {
                                doRestoreAllArp();
                            }
                        }, 1000);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("🔄 Restore Internet")
                .setMessage("This will restore ARP table for all devices.\n\nKilled devices will remain in the list.\n\nContinue?")
                .setPositiveButton("Restore", (d, w) -> doRestoreAllArp())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRestoreAllArp() {
        tvStatus.setText("🔄 Restoring ARP...");
        btnRestoreArp.setEnabled(false);

        new Thread(() -> {
            boolean allSuccess = true;

            // If there are no sessions, just restore ARP for gateway
            if (sessionsByDeviceId.isEmpty()) {
                try {
                    // Try to restore using current gateway
                    String currentGateway = NetUtils.getGatewayIp(MainActivity.this);
                    if (currentGateway != null && !currentGateway.isEmpty()) {
                        // We need to capture a fresh snapshot first
                        // Use a dummy session key for restoration
                        String tempKey = "temp_restore_" + System.currentTimeMillis();
                        arpRestore.captureTrustedSnapshot(MainActivity.this, tempKey, null, null);
                        boolean result = arpRestore.flushAndRestoreFast(tempKey);
                        arpRestore.removeSnapshot(tempKey);
                        allSuccess = result;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "ARP restore fallback failed", e);
                    allSuccess = false;
                }
            } else {
                // Restore ARP for all active sessions
                for (String sessionKey : sessionsByDeviceId.keySet()) {
                    try {
                        if (!arpRestore.flushAndRestoreFast(sessionKey)) {
                            allSuccess = false;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to restore ARP for " + sessionKey, e);
                        allSuccess = false;
                    }
                }
            }

            final boolean success = allSuccess;
            mainHandler.post(() -> {
                if (success) {
                    tvStatus.setText("✅ ARP restored successfully");
                    Toast.makeText(MainActivity.this,
                            "✅ ARP restored. Killed devices preserved.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("⚠️ Partial ARP restore");
                    Toast.makeText(MainActivity.this,
                            "Some ARP entries may not have restored",
                            Toast.LENGTH_LONG).show();
                }
                btnRestoreArp.setEnabled(true);
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "Location permission is required to scan WiFi networks.",
                        Toast.LENGTH_LONG).show();
            } else {
                initializeNetworkInfo();
                performAutoScan();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateKilledCount();
        if (killedFragment != null && showingKilled) {
            killedFragment.refresh();
        }
    }
}