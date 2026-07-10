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

    private final Handler cleanupHandler = new Handler(Looper.getMainLooper());
    private final Runnable cleanupRunnable = new Runnable() {
        @Override
        public void run() {
            if (runner != null && runner.isRunning() && !isForeground) {
                Log.d(TAG, "App not in foreground, killing netcut...");
                forceKillNetcut();
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
        if (runner != null && runner.isRunning()) {
            Log.d(TAG, "Netcut is running, killing it...");
            forceKillNetcut();
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

        // 1. Stop the timer FIRST (while Handler/Looper are still valid)
        stopCleanupTimer();

        // 2. Kill netcut & restore ARP
        forceKillNetcut();

        // 3. Tear down the runner
        if (runner != null) {
            try {
                runner.emergencyStop();
                runner.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Runner destroy failed", e);
            }
            runner = null;
        }

        // 4. Run remaining cleanup
        cleanup();

        // 5. Close the root shell
        if (shellManager != null) {
            shellManager.close();
        }

        // 6. Shut down our executor
        ioExecutor.shutdownNow();

        // 7. Finally call super
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
        btnFixNetwork = findViewById(R.id.btnFixNetwork);  // ← was missing!
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
                            tvStatus.setText("✅ Netcut running (pid=" + pid + ", " + mode + ")");
                            btnStop.setEnabled(true);
                            btnScan.setEnabled(false);
                        });
                    }

                    @Override
                    public void onStopped() {
                        mainHandler.post(() -> {
                            Log.d("NETCUT", "stopped");
                            tvStatus.setText("✅ Netcut stopped");
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            currentTarget = null;
                        });
                    }

                    @Override
                    public void onCrashed(String reason) {
                        mainHandler.post(() -> {
                            Log.e("NETCUT", "crashed: " + reason);
                            tvStatus.setText("⚠ Netcut crashed: " + reason);
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            currentTarget = null;
                            Toast.makeText(MainActivity.this,
                                    "Netcut crashed. Attempting ARP restore.",
                                    Toast.LENGTH_SHORT).show();
                        });
                        ioExecutor.execute(() -> {
                            if (arpRestore != null) arpRestore.flushAndRestore();
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
                        + "Please grant root permissions when prompted by SuperSU/Magisk.\n\n"
                        + "If root is not available, the app will not work.")
                .setPositiveButton("Check Again", (d, w) -> checkRootAccess())
                .setNegativeButton("Continue Anyway", null)
                .setCancelable(false)
                .show();
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();

        // Location permissions (required for WiFi scanning)
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

        // Android 13+ nearby WiFi devices permission
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
        btnStop.setOnClickListener(v -> stopKill());
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
                Toast.makeText(this, "Root access required for this operation.",
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
                            NetcutKiller.killAllAndFlushArp(shellManager);
                            if (arpRestore != null) arpRestore.flushAndRestore();

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

    private void forceKillNetcut() {
        Log.d(TAG, "Force killing netcut...");
        ioExecutor.execute(() -> {
            try {
                NetcutKiller.killAllAndFlushArp(shellManager);
                if (arpRestore != null) arpRestore.flushAndRestore();
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
        if (runner != null && runner.isRunning()) stopKill();

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
            Toast.makeText(this, "Root access required for this operation.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        currentTarget = d;
        tvStatus.setText("🎯 Killing " + d.ip + "...");
        btnScan.setEnabled(false);
        btnStop.setEnabled(false);

        ioExecutor.execute(() -> {
            try {
                String args = "-i " + iface
                        + " --target " + d.ip
                        + " --gateway " + gatewayIp;
                runner.start(args);
                mainHandler.post(() ->
                        Toast.makeText(MainActivity.this,
                                "Netcut start requested",
                                Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e("NETCUT", "Failed to launch netcut", e);
                mainHandler.post(() -> {
                    tvStatus.setText("❌ Failed to start");
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    currentTarget = null;
                    Toast.makeText(MainActivity.this,
                            "Failed to launch netcut. Check root/binary/args.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void stopKill() {
        if (runner == null || !runner.isRunning()) {
            btnStop.setEnabled(false);
            return;
        }
        tvStatus.setText("⏹ Stopping and restoring ARP...");
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        ioExecutor.execute(() -> {
            try {
                runner.stop();
                if (arpRestore != null) arpRestore.flushAndRestore();

                mainHandler.post(() -> {
                    tvStatus.setText("✅ Stopped. ARP restored.");
                    Toast.makeText(this, "Netcut stopped. ARP table restored.",
                            Toast.LENGTH_SHORT).show();
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    currentTarget = null;
                });
            } catch (Exception e) {
                Log.e("NETCUT", "Stop failed", e);
                mainHandler.post(() -> {
                    tvStatus.setText("⚠ Stop may have failed. Check ARP.");
                    Toast.makeText(this,
                            "Stop may have failed. Check ARP table.",
                            Toast.LENGTH_LONG).show();
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    currentTarget = null;
                });
            }
        });
    }

    private void restoreArp() {
        if (!isRootAvailable) {
            Toast.makeText(this, "Root required for ARP restore",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Restore Internet")
                .setMessage("This will:\n"
                        + "1. Stop any running netcut process\n"
                        + "2. Flush the poisoned ARP cache\n"
                        + "3. Restore correct ARP entries\n\n"
                        + "Continue?")
                .setPositiveButton("Restore", (d, w) -> doRestoreArp())
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void doRestoreArp() {
        tvStatus.setText("🔄 Restoring internet...");
        btnRestoreArp.setEnabled(false);
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        ioExecutor.execute(() -> {
            try {
                // STEP 1: Kill netcut FIRST (otherwise it re-poisons immediately)
                Log.d(TAG, "Restore ARP: Step 1 - killing netcut...");
                if (runner != null && runner.isRunning()) {
                    runner.stop();
                }
                // Belt-and-suspenders: also kill via shell
                NetcutKiller.killAll(shellManager);

                // STEP 2: Refresh the ARP cache from the live system
                // (The cached entries may be stale/empty)
                Log.d(TAG, "Restore ARP: Step 2 - refreshing ARP cache...");
                if (arpRestore != null) {
                    arpRestore.refreshCache();
                    int cacheSize = arpRestore.getCacheSize();
                    Log.d(TAG, "ARP cache now has " + cacheSize + " entries");

                    if (cacheSize == 0) {
                        Log.w(TAG, "ARP cache is empty! Trying to ping gateway to populate...");
                        // Trigger ARP resolution by pinging the gateway
                        if (shellManager != null && gatewayIp != null) {
                            shellManager.executeCommand("ping -c 2 -W 1 " + gatewayIp);
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                            arpRestore.refreshCache();
                            cacheSize = arpRestore.getCacheSize();
                            Log.d(TAG, "After ping, ARP cache has " + cacheSize + " entries");
                        }
                    }
                }

                // STEP 3: Reset the short-circuit cache so flushAndRestore actually runs
                if (arpRestore != null) {
                    arpRestore.resetState();
                }

                // STEP 4: Flush poisoned entries and restore correct ones
                Log.d(TAG, "Restore ARP: Step 3 - flushing and restoring...");
                boolean restored = false;
                if (arpRestore != null) {
                    restored = arpRestore.flushAndRestore();
                }

                // STEP 5: Verify by pinging gateway
                Log.d(TAG, "Restore ARP: Step 4 - verifying connectivity...");
                boolean internetOk = false;
                if (shellManager != null && gatewayIp != null) {
                    int pingRc = shellManager.executeCommandWithCode(
                            "ping -c 2 -W 2 " + gatewayIp + " >/dev/null 2>&1");
                    internetOk = (pingRc == 0);
                    Log.d(TAG, "Ping to gateway " + gatewayIp + " rc=" + pingRc);
                }

                final boolean finalRestored = restored;
                final boolean finalInternetOk = internetOk;

                mainHandler.post(() -> {
                    if (finalInternetOk) {
                        tvStatus.setText("✅ Internet restored successfully");
                        Toast.makeText(MainActivity.this,
                                "Internet restored! Gateway is reachable.",
                                Toast.LENGTH_SHORT).show();
                    } else if (finalRestored) {
                        tvStatus.setText("⚠ ARP restored (verify internet manually)");
                        Toast.makeText(MainActivity.this,
                                "ARP entries restored. If internet still doesn't work, try toggling WiFi.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        tvStatus.setText("❌ Restore failed");
                        Toast.makeText(MainActivity.this,
                                "Failed to restore ARP. Try 'Fix Network' button.",
                                Toast.LENGTH_LONG).show();
                    }
                    btnRestoreArp.setEnabled(true);
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    currentTarget = null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Restore ARP failed", e);
                mainHandler.post(() -> {
                    tvStatus.setText("❌ Restore failed: " + e.getMessage());
                    Toast.makeText(MainActivity.this,
                            "Restore failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    btnRestoreArp.setEnabled(true);
                    btnScan.setEnabled(true);
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
                NetcutKiller.killAllAndFlushArp(shellManager);
            }
            runner = null;
        }

        if (arpRestore != null) {
            try {
                arpRestore.flushAndRestore();
                Log.d(TAG, "ARP restored during cleanup");
            } catch (Exception e) {
                Log.e(TAG, "ARP restore failed during cleanup", e);
            }
        }

        mainHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Cleanup completed");
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    public void killNetcutNow(View view) {
        Log.d(TAG, "Manual kill netcut requested");
        forceKillNetcut();
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
                        "Location permission is required to enumerate WiFi neighbors.",
                        Toast.LENGTH_LONG).show();
            } else {
                // Permissions granted, refresh network info
                initializeNetworkInfo();
            }
        }
    }
}