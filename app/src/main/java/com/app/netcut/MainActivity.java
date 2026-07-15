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
import android.view.Window;
import android.view.WindowManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int MAX_START_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private TextView tvGateway, tvIface, tvStatus, tvKilledCount;
    private Button btnScan, btnStop, btnRestoreArp, btnShowKilled, btnShowDevices;
    private ListView lvDevices;

    // Make devices list static to persist across configuration changes
    private static final List<Device> devices = new CopyOnWriteArrayList<>();
    private DeviceAdapter adapter;
    private KilledDevicesFragment killedFragment;
    private KilledDevicesManager killedManager;

    private final HostScanner scanner = new HostScanner();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArpRestore arpRestore;
    private RootShellManager shellManager;

    private String gatewayIp;
    private String iface;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private boolean isRootAvailable = false;
    private final AtomicBoolean isStopping = new AtomicBoolean(false);
    private boolean showingKilled = false;
    private boolean autoScanDone = false;

    // Make sessions static to persist across configuration changes
    private static final Map<String, DeviceSession> sessionsByDeviceId = new ConcurrentHashMap<>();
    private static final Map<String, Integer> startRetryCount = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Handle status bar appearance
        handleStatusBar();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initializeViews();
        setupClickListeners();

        killedManager = KilledDevicesManager.getInstance(this);
        killedFragment = new KilledDevicesFragment();

        // Restore state if available
        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        }

        // If we already have devices, update the UI
        if (!devices.isEmpty()) {
            adapter.notifyDataSetChanged();
            tvStatus.setText("📱 Devices (" + devices.size() + ")");
            updateKilledCount();

            // Update each device's running state
            for (Device d : devices) {
                DeviceSession s = sessionsByDeviceId.get(d.deviceId);
                if (s != null && s.running) {
                    d.isCut = true;
                }
            }
            adapter.notifyDataSetChanged();
        }

        mainHandler.post(() -> {
            if (tvStatus != null && tvStatus.getText().toString().equals("Initializing...")) {
                tvStatus.setText("Ready");
            }
        });

        new Thread(() -> {
            try {
                startService(new Intent(this, CleanupService.class));

                new ExtractFile().extractTheFile(this, R.raw.netcut, "netcut");

                shellManager = RootShellManager.getInstance();
                arpRestore = ArpRestore.getInstance();
                iface = arpRestore.getInterfaceName();
                isRootAvailable = shellManager != null && shellManager.hasRootAccess();

                if (gatewayIp == null || gatewayIp.isEmpty()) {
                    gatewayIp = NetUtils.getGatewayIp(this);
                }

                runOnUi(() -> {
                    tvGateway.setText("🌐 Gateway: " + gatewayIp);
                    tvIface.setText("📶 Iface: " + iface);

                    if (devices.isEmpty()) {
                        tvStatus.setText("Ready");
                    }

                    updateKilledCount();
                    requestPermissions();

                    if (!isRootAvailable) {
                        showRootDialog();
                    } else if (devices.isEmpty() && !autoScanDone) {
                        mainHandler.postDelayed(this::performAutoScan, 1500);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Initialization failed", e);
                runOnUi(() -> {
                    tvStatus.setText("Initialization failed");
                    Toast.makeText(this, "Init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Handle status bar appearance for both light and dark themes
     */
    private void handleStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            // Check if it's dark mode
            int nightModeFlags = getResources().getConfiguration().uiMode &
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // For Android M and above, set status bar icon color based on theme
                if (isDarkMode) {
                    // Dark mode: white icons
                    window.getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    );
                    window.setStatusBarColor(ContextCompat.getColor(this, android.R.color.black));
                } else {
                    // Light mode: dark icons
                    window.getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    );
                    window.setStatusBarColor(ContextCompat.getColor(this, android.R.color.white));
                }
            } else {
                // For older Android versions
                window.setStatusBarColor(ContextCompat.getColor(this, android.R.color.black));
            }
        }
    }

    /**
     * Update status bar appearance on theme changes
     */
    private void updateStatusBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            if (window != null) {
                int nightModeFlags = getResources().getConfiguration().uiMode &
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                boolean isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (isDarkMode) {
                        // Dark mode: white icons
                        window.getDecorView().setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        );
                        window.setStatusBarColor(ContextCompat.getColor(this, android.R.color.black));
                    } else {
                        // Light mode: dark icons
                        window.getDecorView().setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        );
                        window.setStatusBarColor(ContextCompat.getColor(this, android.R.color.white));
                    }
                }
            }
        }
    }

    private void restoreState(Bundle savedInstanceState) {
        isRootAvailable = savedInstanceState.getBoolean("isRootAvailable", false);
        gatewayIp = savedInstanceState.getString("gatewayIp", "");
        iface = savedInstanceState.getString("iface", "");
        autoScanDone = savedInstanceState.getBoolean("autoScanDone", false);
        showingKilled = savedInstanceState.getBoolean("showingKilled", false);

        // Restore devices list if saved
        if (savedInstanceState.containsKey("devices")) {
            List<Device> savedDevices = (List<Device>) savedInstanceState.getSerializable("devices");
            if (savedDevices != null && !savedDevices.isEmpty()) {
                devices.clear();
                devices.addAll(savedDevices);
            }
        }

        // Restore sessions if saved
        if (savedInstanceState.containsKey("sessionKeys")) {
            if (killedManager != null) {
                for (Device d : devices) {
                    if (killedManager.isDeviceKilled(d)) {
                        d.isCut = true;
                    }
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isRootAvailable", isRootAvailable);
        outState.putString("gatewayIp", gatewayIp);
        outState.putString("iface", iface);
        outState.putBoolean("autoScanDone", autoScanDone);
        outState.putBoolean("showingKilled", showingKilled);

        // Save devices list
        if (!devices.isEmpty()) {
            outState.putSerializable("devices", new ArrayList<>(devices));
        }

        // Save session keys
        if (!sessionsByDeviceId.isEmpty()) {
            outState.putStringArrayList("sessionKeys", new ArrayList<>(sessionsByDeviceId.keySet()));
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "Configuration changed, preserving state...");

        // Update status bar appearance for the new theme
        updateStatusBarAppearance();

        // The activity is not recreating, just refresh UI
        runOnUi(() -> {
            // Update UI with preserved data
            if (tvGateway != null && gatewayIp != null) {
                tvGateway.setText("🌐 Gateway: " + gatewayIp);
            }
            if (tvIface != null && iface != null) {
                tvIface.setText("📶 Iface: " + iface);
            }

            // Refresh device list
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }

            // Update status
            if (tvStatus != null) {
                if (devices.isEmpty()) {
                    tvStatus.setText("Ready");
                } else {
                    tvStatus.setText("📱 Devices (" + devices.size() + ")");
                }
            }

            // Update killed count
            updateKilledCount();

            // Refresh killed fragment if showing
            if (showingKilled && killedFragment != null) {
                killedFragment.refresh();
            }

            // Update button states based on active sessions
            boolean hasActiveSessions = !sessionsByDeviceId.isEmpty();
            if (btnStop != null) {
                btnStop.setEnabled(hasActiveSessions);
            }
            if (btnScan != null) {
                btnScan.setEnabled(!isScanning.get() && !hasActiveSessions);
            }
        });
    }

    @Override
    protected void onDestroy() {
        // Only clean up if we're not in a configuration change
        if (!isChangingConfigurations()) {
            stopAllSessions();
            if (shellManager != null) {
                shellManager.close();
            }
            // Clear static data only on full destroy
            if (!isChangingConfigurations()) {
                devices.clear();
                sessionsByDeviceId.clear();
                startRetryCount.clear();
            }
        } else {
            // Just stop sessions but keep data
            stopAllSessions();
        }
        super.onDestroy();
    }

    // ... (keep all your existing methods unchanged from here) ...

    private void showRootDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Root Required")
                .setMessage("This app requires root access to function properly.")
                .setPositiveButton("Check Again", (d, w) -> {
                    new Thread(() -> {
                        boolean root = shellManager != null && shellManager.hasRootAccess();
                        runOnUi(() -> {
                            isRootAvailable = root;
                            if (!root) showRootDialog();
                        });
                    }).start();
                })
                .setNegativeButton("Continue", null)
                .show();
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

        adapter = new DeviceAdapter(MainActivity.this, devices);
        lvDevices.setAdapter(adapter);

        btnStop.setEnabled(false);
        btnRestoreArp.setEnabled(true);
        btnShowDevices.setVisibility(View.GONE);

        if (tvStatus != null) {
            tvStatus.setText("Ready");
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
    }

    private void performAutoScan() {
        if (autoScanDone) return;
        autoScanDone = true;

        if (isRootAvailable && !isScanning.get() && devices.isEmpty()) {
            Log.d(TAG, "Performing auto-scan...");
            startScan();
        }
    }

    private void showKilledDevices() {
        showingKilled = true;
        btnShowKilled.setVisibility(View.GONE);
        btnShowDevices.setVisibility(View.VISIBLE);
        tvStatus.setText("📋 Killed Devices (" + killedManager.getCount() + ")");

        killedFragment = new KilledDevicesFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, killedFragment)
                .commitAllowingStateLoss();

        lvDevices.setVisibility(View.GONE);
        findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
    }

    private void showDevices() {
        showingKilled = false;
        btnShowKilled.setVisibility(View.VISIBLE);
        btnShowDevices.setVisibility(View.GONE);
        tvStatus.setText("📱 Devices (" + devices.size() + ")");

        FragmentManager fm = getSupportFragmentManager();
        if (killedFragment != null && killedFragment.isAdded()) {
            fm.beginTransaction().remove(killedFragment).commitAllowingStateLoss();
        }

        lvDevices.setVisibility(View.VISIBLE);
        findViewById(R.id.fragment_container).setVisibility(View.GONE);
        updateKilledCount();
    }

    private void updateKilledCount() {
        runOnUi(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvKilledCount != null && killedManager != null) {
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
        if (isScanning.getAndSet(true)) {
            Toast.makeText(this, "Scan already running", Toast.LENGTH_SHORT).show();
            return;
        }

        devices.clear();
        adapter.notifyDataSetChanged();
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

                    for (Device d : devices) {
                        DeviceSession s = sessionsByDeviceId.get(d.deviceId);
                        boolean wasKilled = killedManager != null && killedManager.isDeviceKilled(d);

                        if (wasKilled && (s == null || !s.running)) {
                            d.isCut = true;
                            restoreKilledSession(d);
                        } else if (s != null && s.running) {
                            d.isCut = true;
                        } else {
                            d.isCut = false;
                        }
                    }

                    if (killedFragment != null && killedFragment.isAdded() && showingKilled) {
                        killedFragment.refresh();
                    }

                    adapter.notifyDataSetChanged();
                    isScanning.set(false);
                    btnScan.setEnabled(true);
                    btnScan.setText("🔍 Scan");
                    tvStatus.setText("✅ Found " + found.size() + " devices. Tap to kill.");
                    updateKilledCount();
                });
            }
        });
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private void restoreKilledSession(Device d) {
        if (d != null && d.mac != null && !d.mac.isEmpty()) {
            Log.d(TAG, "Auto-restoring killed session for: " + d.mac);
            startSessionWithRetry(d);
        }
    }

    private void startSessionWithRetry(Device d) {
        String sessionKey = d.deviceId;
        int retryCount = startRetryCount.getOrDefault(sessionKey, 0);

        if (retryCount >= MAX_START_RETRIES) {
            Log.w(TAG, "Max retries reached for " + d.ip + ", giving up");
            mainHandler.post(() -> {
                Toast.makeText(MainActivity.this,
                        "Failed to start session for " + d.ip + " after " + MAX_START_RETRIES + " attempts",
                        Toast.LENGTH_LONG).show();
            });
            startRetryCount.remove(sessionKey);
            return;
        }

        startRetryCount.put(sessionKey, retryCount + 1);
        startSession(d);
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

                            startRetryCount.remove(sessionKey);

                            if (killedManager != null) {
                                killedManager.addKilledDevice(d);
                            }
                            if (killedFragment != null) killedFragment.refresh();
                            updateKilledCount();

                            adapter.notifyDataSetChanged();
                            tvStatus.setText("🔴 Running for " + d.ip + " (pid: " + pid + ")");
                            btnStop.setEnabled(true);
                            btnScan.setEnabled(false);
                        });
                    }

                    @Override
                    public void onStopped() {
                        mainHandler.post(() -> {
                            session.running = false;
                            session.stopping = false;
                            session.pid = -1;
                            d.isCut = false;

                            sessionsByDeviceId.remove(sessionKey);

                            adapter.notifyDataSetChanged();
                            tvStatus.setText("🟢 Stopped for " + d.ip);
                            btnStop.setEnabled(false);
                            btnScan.setEnabled(true);
                            isStopping.set(false);
                            updateKilledCount();
                        });
                    }

                    @Override
                    public void onCrashed(String reason) {
                        mainHandler.post(() -> {
                            session.running = false;
                            session.stopping = false;
                            session.pid = -1;
                            d.isCut = true;

                            int retryCount = startRetryCount.getOrDefault(sessionKey, 0);

                            if (retryCount < MAX_START_RETRIES &&
                                    (reason == null || !reason.contains("user stopped"))) {
                                Log.d(TAG, "Session crashed, scheduling retry " + (retryCount + 1) +
                                        " for " + d.ip);
                                tvStatus.setText("🔄 Retrying " + d.ip + " (" + (retryCount + 1) + "/" +
                                        MAX_START_RETRIES + ")");

                                mainHandler.postDelayed(() -> {
                                    if (!session.stopping && !isStopping.get()) {
                                        startSessionWithRetry(d);
                                    }
                                }, RETRY_DELAY_MS);
                            } else {
                                sessionsByDeviceId.remove(sessionKey);
                                adapter.notifyDataSetChanged();
                                tvStatus.setText("⚠️ Crashed for " + d.ip);
                                btnStop.setEnabled(false);
                                btnScan.setEnabled(true);
                                isStopping.set(false);
                                Toast.makeText(MainActivity.this,
                                        "⚠️ Session crashed: " + d.ip,
                                        Toast.LENGTH_SHORT).show();
                                startRetryCount.remove(sessionKey);
                            }
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
                    if (killedManager != null) {
                        killedManager.removeKilledDevice(d);
                    }
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("❌ Failed to start for " + d.ip);
                    btnScan.setEnabled(true);
                    btnStop.setEnabled(false);
                    updateKilledCount();
                    Toast.makeText(MainActivity.this,
                            "Failed to start for " + d.ip + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    startRetryCount.remove(sessionKey);
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
            if (killedManager != null) {
                killedManager.removeKilledDevice(d);
            }
            if (killedFragment != null) killedFragment.refresh();
            updateKilledCount();
            startRetryCount.remove(d.deviceId);

            sessionsByDeviceId.remove(d.deviceId);

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

        if (isStopping.get()) {
            Toast.makeText(this, "Already stopping...", Toast.LENGTH_SHORT).show();
            return;
        }

        isStopping.set(true);
        session.stopping = true;
        tvStatus.setText("⏹ Stopping " + d.ip + "...");
        btnStop.setEnabled(false);
        btnScan.setEnabled(false);

        startRetryCount.remove(sessionKey);

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

                mainHandler.post(() -> {
                    if (killedManager != null) {
                        killedManager.removeKilledDevice(d);
                    }
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
                    isStopping.set(false);
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
                if (killedManager != null) {
                    killedManager.removeKilledDevice(d);
                }
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
            isStopping.set(false);
            startRetryCount.remove(sessionKey);
        });
    }

    private void stopAllSessions() {
        if (sessionsByDeviceId.isEmpty()) {
            return;
        }

        if (isStopping.get()) {
            return;
        }

        isStopping.set(true);
        runOnUi(() -> {
            tvStatus.setText("⏹ Stopping all sessions...");
            btnStop.setEnabled(false);
            btnScan.setEnabled(false);
        });

        new Thread(() -> {
            List<DeviceSession> sessions = new ArrayList<>(sessionsByDeviceId.values());

            for (DeviceSession session : sessions) {
                if (session == null) continue;
                try {
                    session.stopping = true;
                    if (session.runner != null) {
                        session.runner.stop();
                    }
                    arpRestore.flushAndRestoreFast(session.sessionKey);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to stop session " + session.sessionKey, e);
                    try {
                        if (session.runner != null) {
                            session.runner.emergencyStop();
                            session.runner.destroy();
                        }
                    } catch (Exception ignored) {}
                }

                if (session.device != null) {
                    session.device.isCut = false;
                }
            }

            sessionsByDeviceId.clear();
            startRetryCount.clear();

            runOnUi(() -> {
                adapter.notifyDataSetChanged();
                if (killedFragment != null && killedFragment.isAdded() && showingKilled) {
                    killedFragment.refresh();
                }
                updateKilledCount();
                tvStatus.setText("✅ All sessions stopped");
                btnStop.setEnabled(false);
                btnScan.setEnabled(true);
                isStopping.set(false);
            });
        }).start();
    }

    private void restoreAllArp() {
        if (!isRootAvailable) {
            Toast.makeText(this, "Root required for ARP restore", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!sessionsByDeviceId.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("🔄 Active Sessions Found")
                    .setMessage("There are active sessions running. Do you want to stop them and restore ARP?")
                    .setPositiveButton("Yes, Stop All", (d, w) -> {
                        stopAllSessions();
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
            List<String> sessionKeys = new ArrayList<>(sessionsByDeviceId.keySet());

            if (sessionKeys.isEmpty()) {
                try {
                    String currentGateway = NetUtils.getGatewayIp(MainActivity.this);
                    if (currentGateway != null && !currentGateway.isEmpty()) {
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
                for (String sessionKey : sessionKeys) {
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
                } else {
                    tvStatus.setText("✅⚠️ Some ARP entries may not have restored");
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
                if (devices.isEmpty()) {
                    performAutoScan();
                }
            }
        }
    }

    public void toggleKillDirectly(int position) {
        if (position < 0 || position >= devices.size()) return;

        Device d = devices.get(position);

        if (d.isGateway) {
            Toast.makeText(this, "Cannot target gateway", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isRootAvailable) {
            Toast.makeText(this, "Root access required", Toast.LENGTH_SHORT).show();
            return;
        }

        DeviceSession session = sessionsByDeviceId.get(d.deviceId);
        boolean isRunning = session != null && session.running;

        if (isRunning) {
            stopSession(d);
        } else {
            startSessionWithRetry(d);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update status bar when returning to app
        updateStatusBarAppearance();
        updateKilledCount();
        if (killedFragment != null && showingKilled) {
            killedFragment.refresh();
        }
        // Refresh device list if we have devices
        if (!devices.isEmpty() && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}