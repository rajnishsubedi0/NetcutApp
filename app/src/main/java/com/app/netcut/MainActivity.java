package com.app.netcut;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvGateway, tvIface, tvStatus;
    private Button btnScan, btnStop, btnRestoreArp;
    private ListView lvDevices;

    private final List<Device> devices = new ArrayList<>();
    private DeviceAdapter adapter;

    private final HostScanner scanner = new HostScanner();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArpRestore arpRestore;
    private RootShellManager shellManager;

    private String gatewayIp;
    private String iface;

    private boolean isScanning = false;
    private boolean isRootAvailable = false;
    private boolean isStopping = false;

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
        btnScan = findViewById(R.id.btnScan);
        btnStop = findViewById(R.id.btnStop);
        btnRestoreArp = findViewById(R.id.btnRestoreArp);
        lvDevices = findViewById(R.id.lvDevices);

        adapter = new DeviceAdapter(this, devices);
        lvDevices.setAdapter(adapter);

        btnStop.setEnabled(false);
        btnRestoreArp.setEnabled(true);
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
                    .setMessage("This app requires root.")
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
            tvGateway.setText("Gateway: " + gatewayIp);
            tvIface.setText("Iface: " + iface);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to get network info", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "initializeNetworkInfo failed", e);
        }
    }

    private void setupClickListeners() {
        btnScan.setOnClickListener(v -> startScan());
        btnStop.setOnClickListener(v -> stopAllSessions());
        btnRestoreArp.setOnClickListener(v -> restoreAllArp());

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            Device d = devices.get(position);

            if (d.isGateway) {
                Toast.makeText(this, "Cannot target gateway", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isRootAvailable) {
                Toast.makeText(this, "Root required", Toast.LENGTH_SHORT).show();
                return;
            }

            showDeviceDialog(d);
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
        tvStatus.setText("Scanning...");

        scanner.scan(this, new HostScanner.ScanCallback() {
            @Override
            public void onProgress(int done, int total) {
                mainHandler.post(() -> tvStatus.setText("Scanning " + done + "/" + total));
            }

            @Override
            public void onFinished(List<Device> found) {
                mainHandler.post(() -> {
                    devices.clear();
                    devices.addAll(found);

                    for (Device d : devices) {
                        DeviceSession s = sessionsByDeviceId.get(d.deviceId);
                        d.isCut = s != null && s.running;
                    }

                    adapter.notifyDataSetChanged();
                    isScanning = false;
                    btnScan.setEnabled(true);
                    tvStatus.setText("Found " + found.size() + " devices. Tap one to kill.");
                });
            }
        });
    }

    private void showDeviceDialog(Device d) {
        DeviceSession session = sessionsByDeviceId.get(d.deviceId);
        boolean isRunning = session != null && session.running;

        String title = isRunning ? "Manage Target" : "Target Device";
        String action = isRunning ? "Unkill" : "Kill";

        String msg = "IP: " + d.ip
                + "\nMAC: " + d.mac
                + "\nVendor: " + d.vendor
                + "\nState: " + (isRunning ? "Cut" : "Online");

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
                .setNeutralButton("Cancel", null)
                .show();
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
                            adapter.notifyDataSetChanged();
                            tvStatus.setText("Running for " + d.ip + " pid=" + pid);
                            btnStop.setEnabled(true);
                            btnScan.setEnabled(false);
                            Toast.makeText(MainActivity.this,
                                    "Started for " + d.ip,
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
                            adapter.notifyDataSetChanged();
                            tvStatus.setText("Stopped for " + d.ip);
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            isStopping = false;
                            Toast.makeText(MainActivity.this,
                                    "Restored " + d.ip,
                                    Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onCrashed(String reason) {
                        mainHandler.post(() -> {
                            session.running = false;
                            session.stopping = false;
                            session.pid = -1;
                            d.isCut = false;
                            adapter.notifyDataSetChanged();
                            tvStatus.setText("Crashed for " + d.ip);
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            isStopping = false;
                            Toast.makeText(MainActivity.this,
                                    "Session crashed: " + d.ip,
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
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("Failed to start for " + d.ip);
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    Toast.makeText(MainActivity.this,
                            "Failed to start for " + d.ip,
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
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
        tvStatus.setText("Stopping " + d.ip + "...");
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        new Thread(() -> {
            try {
                // IMMEDIATELY restore ARP for this specific device in parallel
                Thread arpThread = new Thread(() -> {
                    try {
                        // Restore ARP immediately using the snapshot for this session
                        boolean restored = arpRestore.flushAndRestoreFast(sessionKey);
                        Log.d(TAG, "ARP restore for " + sessionKey + ": " + (restored ? "success" : "failed"));

                        // If restore failed, try one more time with a fresh snapshot
                        if (!restored) {
                            Log.w(TAG, "ARP restore failed, retrying for " + sessionKey);
                            arpRestore.captureTrustedSnapshot(MainActivity.this, sessionKey, d.ip, d.mac);
                            arpRestore.flushAndRestoreFast(sessionKey);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "ARP restore error for " + sessionKey, e);
                    }
                });
                arpThread.start();

                // Stop the runner
                if (session.runner != null && session.running) {
                    session.runner.stop();
                }

                // Wait briefly for the runner to stop (max 1 second)
                int waitCount = 0;
                while (session.running && waitCount < 10) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    waitCount++;
                }

                // If still running, emergency stop
                if (session.running) {
                    Log.w(TAG, "Runner still running, emergency stop for " + sessionKey);
                    if (session.runner != null) {
                        session.runner.emergencyStop();
                        session.runner.destroy();
                    }
                    // Ensure ARP is restored
                    arpRestore.flushAndRestoreFast(sessionKey);
                }

                // Wait for ARP thread to complete (max 1 second)
                try {
                    arpThread.join(1000);
                } catch (InterruptedException ignored) {}

                // Update UI and cleanup
                mainHandler.post(() -> {
                    session.running = false;
                    session.stopping = false;
                    session.pid = -1;
                    d.isCut = false;
                    sessionsByDeviceId.remove(sessionKey);
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("Internet restored for " + d.ip);
                    btnStop.setEnabled(false);
                    btnScan.setEnabled(true);
                    isStopping = false;
                    Toast.makeText(MainActivity.this,
                            "Internet restored for " + d.ip,
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Stop session failed for " + d.ip, e);

                // Emergency cleanup
                try {
                    if (session.runner != null) {
                        session.runner.emergencyStop();
                        session.runner.destroy();
                    }
                    arpRestore.flushAndRestoreFast(sessionKey);
                } catch (Exception ignored) {}

                mainHandler.post(() -> {
                    sessionsByDeviceId.remove(sessionKey);
                    d.isCut = false;
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("Emergency stopped " + d.ip);
                    btnStop.setEnabled(false);
                    btnScan.setEnabled(true);
                    isStopping = false;
                    Toast.makeText(MainActivity.this,
                            "Internet restored for " + d.ip,
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
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
        tvStatus.setText("Stopping all sessions...");
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
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to stop session " + session.sessionKey, e);
                        try {
                            session.runner.emergencyStop();
                            session.runner.destroy();
                            arpRestore.flushAndRestoreFast(session.sessionKey);
                        } catch (Exception ignored) {}
                    }
                }
            }

            sessionsByDeviceId.clear();

            mainHandler.post(() -> {
                adapter.notifyDataSetChanged();
                tvStatus.setText("All sessions stopped");
                btnStop.setEnabled(false);
                btnScan.setEnabled(true);
                isStopping = false;
                Toast.makeText(MainActivity.this,
                        "All sessions stopped and ARP restored",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void restoreAllArp() {
        if (!isRootAvailable) {
            Toast.makeText(this, "Root required for ARP restore", Toast.LENGTH_SHORT).show();
            return;
        }

        // Stop all sessions first
        if (!sessionsByDeviceId.isEmpty()) {
            Toast.makeText(this, "Stopping sessions first...", Toast.LENGTH_SHORT).show();
            stopAllSessions();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Restore Internet")
                .setMessage("This will restore ARP table for all devices.\n\nContinue?")
                .setPositiveButton("Restore", (d, w) -> doRestoreAllArp())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRestoreAllArp() {
        tvStatus.setText("Restoring ARP...");
        btnRestoreArp.setEnabled(false);

        new Thread(() -> {
            boolean allSuccess = true;
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

            final boolean success = allSuccess;
            mainHandler.post(() -> {
                if (success) {
                    tvStatus.setText("ARP restored successfully");
                    Toast.makeText(MainActivity.this,
                            "ARP restored for all devices",
                            Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("Partial ARP restore");
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
            }
        }
    }
}