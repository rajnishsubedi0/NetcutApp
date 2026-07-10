package com.app.netcut;

import android.Manifest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvGateway, tvIface, tvStatus;
    private Button btnScan, btnStop, btnRestoreArp, btnFixNetwork;
    private ListView lvDevices;

    private final List<Device> devices = new ArrayList<>();
    private DeviceAdapter adapter;

    private final HostScanner scanner = new HostScanner();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);

    private ArpRestore arpRestore;
    private RootShellManager shellManager;
    private NetcutRunner runner;

    private String gatewayIp;
    private String iface = "wlan0";
    private Device currentTarget;

    private boolean isScanning = false;
    private boolean isRootAvailable = false;
    private boolean isForeground = false;
    private boolean isStopping = false;

    private final Handler cleanupHandler = new Handler(Looper.getMainLooper());
    private final Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            if (runner != null && runner.isRunning() && !isForeground) {
                Log.d(TAG, "App not in foreground, killing netcut...");
                forceKillNetcutFast();
                if (runner != null) {
                    try {
                        runner.emergencyStop();
                        runner.destroy();
                        runner = null;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to stop runner", e);
                    }
                }
            }
            cleanupHandler.postDelayed(this, 5000);
        }
    };

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
        initializeViews();
        initializeShellManager();
        initializeArpRestore();
        initializeRunner();
        checkRootAccess();
        requestPermissions();
        initializeNetworkInfo();
        setupClickListeners();
        startCleanupTimer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        isForeground = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        isForeground = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        if (runner != null && runner.isRunning() && !isStopping) {
            Log.d(TAG, "Netcut is running, killing it...");
            forceKillNetcutFast();
            try {
                runner.emergencyStop();
                runner.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop runner", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy - final cleanup...");
        stopCleanupTimer();
        forceKillNetcutFast();

        if (runner != null) {
            try {
                runner.emergencyStop();
                runner.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Runner destroy failed", e);
            }
            runner = null;
        }

        cleanup();
        if (shellManager != null) {
            shellManager.close();
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------

    private void initializeViews() {
        tvGateway = findViewById(R.id.tvGateway);
        tvIface = findViewById(R.id.tvIface);
        tvStatus = findViewById(R.id.tvStatus);
        btnScan = findViewById(R.id.btnScan);
        btnStop = findViewById(R.id.btnStop);
        btnRestoreArp = findViewById(R.id.btnRestoreArp);
        btnFixNetwork = findViewById(R.id.btnFixNetwork);
        lvDevices = findViewById(R.id.lvDevices);

        adapter = new DeviceAdapter(this, devices);
        lvDevices.setAdapter(adapter);

        btnStop.setEnabled(false);
        btnRestoreArp.setEnabled(true);
        tvStatus.setText("Ready");
    }

    private void initializeShellManager() {
        shellManager = RootShellManager.getInstance();
    }

    private void initializeArpRestore() {
        arpRestore = ArpRestore.getInstance(this);
    }

    private void initializeRunner() {
        runner = new NetcutRunner(
                this,
                line -> {
                    Log.d("NETCUT", line);
                    mainHandler.post(() -> {
                        if (line != null && !line.trim().isEmpty()) {
                            tvStatus.setText(line);
                        }
                    });
                },
                new NetcutRunner.StateListener() {
                    @Override
                    public void onStarted(int pid, NetcutRunner.LaunchMode mode) {
                        mainHandler.post(() -> {
                            Log.d("NETCUT", "started pid=" + pid + " mode=" + mode);
                            tvStatus.setText("✅ Netcut running (pid=" + pid + ")");
                            btnStop.setEnabled(true);
                            btnScan.setEnabled(false);
                            isStopping = false;
                        });
                    }

                    @Override
                    public void onStopped() {
                        mainHandler.post(() -> {
                            Log.d("NETCUT", "stopped");
                            tvStatus.setText("✅ Netcut stopped - Internet restored");
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            currentTarget = null;
                            isStopping = false;
                            Toast.makeText(MainActivity.this,
                                    "Internet restored", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onCrashed(String reason) {
                        mainHandler.post(() -> {
                            Log.e("NETCUT", "crashed: " + reason);
                            tvStatus.setText("⚠ Netcut crashed - Restoring...");
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            currentTarget = null;
                            isStopping = false;
                        });
                        // Fast restore on crash
                        ioExecutor.execute(() -> {
                            if (arpRestore != null) {
                                arpRestore.flushAndRestoreFast();
                                mainHandler.post(() -> {
                                    tvStatus.setText("✅ Internet restored after crash");
                                    Toast.makeText(MainActivity.this,
                                            "Internet restored", Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                    }
                }
        );
    }

    private void checkRootAccess() {
        isRootAvailable = shellManager != null && shellManager.hasRootAccess();
        String status = "Root: " + (isRootAvailable ? "✓ Available" : "✗ Not available");
        Toast.makeText(this, status, Toast.LENGTH_LONG).show();
        if (!isRootAvailable) showRootWarning();
    }

    private void showRootWarning() {
        String rootStatus = shellManager != null
                ? shellManager.getRootStatus()
                : "✗ Shell not initialized";
        new AlertDialog.Builder(this)
                .setTitle("⚠ Root Required")
                .setMessage("This app requires root access to function.\n\n"
                        + "Status: " + rootStatus + "\n\n"
                        + "Please grant root permissions when prompted.")
                .setPositiveButton("Check Again", (d, w) -> checkRootAccess())
                .setNegativeButton("Continue Anyway", null)
                .setCancelable(false)
                .show();
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES)
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
            Log.e(TAG, "Failed to get network info", e);
        }
    }

    private void setupClickListeners() {
        btnScan.setOnClickListener(v -> startScan());
        btnStop.setOnClickListener(v -> stopKillFast());
        btnRestoreArp.setOnClickListener(v -> restoreArp());
        btnFixNetwork.setOnClickListener(v -> fixNetwork());

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            Device d = devices.get(position);
            if (d.isGateway) {
                Toast.makeText(this, "Cannot target the gateway itself.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isRootAvailable) {
                Toast.makeText(this, "Root access required.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            confirmKill(d);
        });
    }

    // ------------------------------------------------------------------
    // Cleanup timer
    // ------------------------------------------------------------------

    private void startCleanupTimer() {
        cleanupHandler.postDelayed(cleanupRunnable, 5000);
    }

    private void stopCleanupTimer() {
        cleanupHandler.removeCallbacks(cleanupRunnable);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void fixNetwork() {
        new AlertDialog.Builder(this)
                .setTitle("Fix Network")
                .setMessage("This will kill any running netcut processes and restore the ARP table. Continue?")
                .setPositiveButton("Fix", (d, w) -> {
                    tvStatus.setText("🔧 Fixing network...");
                    ioExecutor.execute(() -> {
                        try {
                            // Kill all netcut processes
                            NetcutKiller.killAll(shellManager);

                            // Flush ARP
                            shellManager.executeCommandBool("ip neigh flush all 2>/dev/null");

                            // Fast restore
                            if (arpRestore != null) {
                                arpRestore.refreshCache();
                                arpRestore.flushAndRestoreFast();
                            }

                            mainHandler.post(() -> {
                                tvStatus.setText("✅ Network fixed");
                                Toast.makeText(this, "Network restored successfully",
                                        Toast.LENGTH_SHORT).show();
                                if (runner != null) {
                                    runner.emergencyStop();
                                    runner.destroy();
                                    runner = null;
                                }
                                btnStop.setEnabled(false);
                                btnScan.setEnabled(true);
                                currentTarget = null;
                                isStopping = false;
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to fix network", e);
                            mainHandler.post(() -> {
                                tvStatus.setText("❌ Failed to fix network");
                                Toast.makeText(this, "Failed to fix network",
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void forceKillNetcutFast() {
        Log.d(TAG, "Force killing netcut (fast)...");
        ioExecutor.execute(() -> {
            try {
                NetcutKiller.killAll(shellManager);
                shellManager.executeCommandBool("ip neigh flush all 2>/dev/null");
                if (arpRestore != null) {
                    arpRestore.refreshCache();
                    arpRestore.flushAndRestoreFast();
                }
                Log.d(TAG, "Force kill + ARP restore complete");
            } catch (Exception e) {
                Log.e(TAG, "Force kill failed", e);
            }
        });
    }

    private void startScan() {
        if (isScanning) {
            Toast.makeText(this, "Scan already in progress", Toast.LENGTH_SHORT).show();
            return;
        }
        if (runner != null && runner.isRunning()) {
            Toast.makeText(this, "Stop netcut first", Toast.LENGTH_SHORT).show();
            return;
        }

        devices.clear();
        adapter.notifyDataSetChanged();
        tvStatus.setText("📡 Scanning...");
        btnScan.setEnabled(false);
        isScanning = true;

        scanner.scan(this, new HostScanner.ScanCallback() {
            @Override
            public void onProgress(int done, int total) {
                mainHandler.post(() ->
                        tvStatus.setText("📡 Scanning " + done + "/" + total));
            }

            @Override
            public void onFinished(List<Device> found) {
                mainHandler.post(() -> {
                    devices.clear();
                    devices.addAll(found);
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("Found " + found.size() + " devices. Tap one to kill.");
                    btnScan.setEnabled(true);
                    isScanning = false;
                    if (arpRestore != null) arpRestore.refreshCache();
                });
            }
        });
    }

    private void confirmKill(Device d) {
        new AlertDialog.Builder(this)
                .setTitle("Kill Internet?")
                .setMessage("Target: " + d.ip + "\nMAC: " + d.mac
                        + "\nVendor: " + d.vendor
                        + "\n\nProceed to cut this device's connection?")
                .setPositiveButton("Kill", (dlg, w) -> startKill(d))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startKill(Device d) {
        if (runner == null) {
            Toast.makeText(this, "Runner not initialized", Toast.LENGTH_SHORT).show();
            return;
        }
        if (runner.isRunning()) {
            Toast.makeText(this, "Already running. Stop first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isRootAvailable) {
            Toast.makeText(this, "Root access required.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        currentTarget = d;
        tvStatus.setText("🎯 Killing " + d.ip + "...");
        btnScan.setEnabled(false);
        btnStop.setEnabled(false);
        isStopping = false;

        ioExecutor.execute(() -> {
            try {
                String args = "-i " + iface
                        + " --target " + d.ip
                        + " --gateway " + gatewayIp;
                runner.start(args);
            } catch (Exception e) {
                Log.e("NETCUT", "Failed to launch netcut", e);
                mainHandler.post(() -> {
                    tvStatus.setText("❌ Failed to start");
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    currentTarget = null;
                    Toast.makeText(MainActivity.this,
                            "Failed to launch netcut.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * FAST stop - single restore only
     */
    private void stopKillFast() {
        if (runner == null || !runner.isRunning()) {
            btnStop.setEnabled(false);
            return;
        }

        if (isStopping) {
            Toast.makeText(this, "Already stopping...", Toast.LENGTH_SHORT).show();
            return;
        }

        isStopping = true;
        tvStatus.setText("⏹ Stopping and restoring...");
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        ioExecutor.execute(() -> {
            try {
                // Stop the runner - this handles ARP restore internally
                runner.stop();

                // Runner's onStopped callback will update UI
                // No additional restore needed here!

            } catch (Exception e) {
                Log.e("NETCUT", "Stop failed", e);
                isStopping = false;
                mainHandler.post(() -> {
                    tvStatus.setText("⚠ Stop failed - trying emergency...");
                    // Emergency fallback
                    if (arpRestore != null) {
                        arpRestore.flushAndRestoreFast();
                    }
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    currentTarget = null;
                    Toast.makeText(this,
                            "Emergency restore completed",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Restore ARP - separate function for manual restore button
     */
    private void restoreArp() {
        if (!isRootAvailable) {
            Toast.makeText(this, "Root required for ARP restore",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // First stop netcut if running
        if (runner != null && runner.isRunning()) {
            Toast.makeText(this, "Stopping netcut first...", Toast.LENGTH_SHORT).show();
            stopKillFast();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Restore Internet")
                .setMessage("This will restore the ARP table.\n\nContinue?")
                .setPositiveButton("Restore", (d, w) -> doRestoreArp())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRestoreArp() {
        tvStatus.setText("🔄 Restoring ARP...");
        btnRestoreArp.setEnabled(false);

        ioExecutor.execute(() -> {
            try {
                // Single restore - fast path
                if (arpRestore != null) {
                    arpRestore.refreshCache();
                    boolean success = arpRestore.flushAndRestoreFast();

                    mainHandler.post(() -> {
                        if (success) {
                            tvStatus.setText("✅ ARP restored");
                            Toast.makeText(MainActivity.this,
                                    "ARP table restored successfully",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            tvStatus.setText("⚠ Partial restore - check manually");
                            Toast.makeText(MainActivity.this,
                                    "ARP restore may be incomplete",
                                    Toast.LENGTH_LONG).show();
                        }
                        btnRestoreArp.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Restore ARP failed", e);
                mainHandler.post(() -> {
                    tvStatus.setText("❌ Restore failed");
                    Toast.makeText(MainActivity.this,
                            "Restore failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    btnRestoreArp.setEnabled(true);
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private void cleanup() {
        Log.d(TAG, "Starting immediate cleanup...");
        scanner.cancel();
        isScanning = false;

        if (runner != null) {
            try {
                if (runner.isRunning()) runner.stop();
                runner.emergencyStop();
                runner.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Runner cleanup failed", e);
                NetcutKiller.killAll(shellManager);
                if (arpRestore != null) arpRestore.flushAndRestoreFast();
            }
            runner = null;
        }

        mainHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Cleanup completed");
    }

    public void killNetcutNow(View view) {
        Log.d(TAG, "Manual kill netcut requested");
        forceKillNetcutFast();
        Toast.makeText(this, "Netcut killed and ARP restored", Toast.LENGTH_SHORT).show();
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