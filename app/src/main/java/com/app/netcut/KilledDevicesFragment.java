package com.app.netcut;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.app.netcut.KilledDevicesManager.KilledDeviceInfo;

import java.util.ArrayList;
import java.util.List;

public class KilledDevicesFragment extends Fragment {
    private static final String TAG = "KilledDevicesFragment";

    private ListView lvKilledDevices;
    private TextView tvEmptyState, tvCount;
    private Button btnClearAll;

    private KilledDevicesManager killedManager;
    private DeviceAdapter adapter;
    private final List<Device> killedDevices = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_killed_devices, container, false);

        lvKilledDevices = view.findViewById(R.id.lvKilledDevices);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
        tvCount = view.findViewById(R.id.tvCount);
        btnClearAll = view.findViewById(R.id.btnClearAll);

        // Initialize manager here
        if (getContext() != null) {
            killedManager = KilledDevicesManager.getInstance(getContext());
        }

        adapter = new DeviceAdapter(requireContext(), killedDevices);
        lvKilledDevices.setAdapter(adapter);

        loadKilledDevices();

        btnClearAll.setOnClickListener(v -> {
            if (killedDevices.isEmpty()) {
                Toast.makeText(requireContext(), "No killed devices to clear", Toast.LENGTH_SHORT).show();
                return;
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle("Clear All Killed Devices")
                    .setMessage("This will remove all devices from the killed list.\n\nContinue?")
                    .setPositiveButton("Clear All", (d, w) -> {
                        if (killedManager != null) {
                            killedManager.clearAll();
                            loadKilledDevices();
                            Toast.makeText(requireContext(), "All killed devices cleared", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
//                    .setStyle(AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .show();
        });

        lvKilledDevices.setOnItemClickListener((parent, view1, position, id) -> {
            Device d = killedDevices.get(position);
            if (d != null) {
                showDeviceOptionsDialog(d);
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh when fragment becomes visible
        loadKilledDevices();
    }

    private void showDeviceOptionsDialog(Device d) {
        String[] options = {"Unkill Device", "Set Custom Name", "View Details"};

        new AlertDialog.Builder(requireContext())
                .setTitle("Device Options")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            unkillDevice(d);
                            break;
                        case 1:
                            showSetNameDialog(d);
                            break;
                        case 2:
                            showDeviceDetails(d);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
//                .setStyle(AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .show();
    }

    private void showSetNameDialog(Device d) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Set Custom Name for " + d.ip);

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Enter device name");

        if (killedManager != null) {
            KilledDeviceInfo info = killedManager.getDeviceInfo(d.mac);
            if (info != null && info.name != null && !info.name.isEmpty()) {
                input.setText(info.name);
            }
        }

        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty() && killedManager != null) {
                killedManager.setDeviceName(d.mac, name);
                loadKilledDevices();
                Toast.makeText(requireContext(), "Device name updated to: " + name, Toast.LENGTH_SHORT).show();
            } else if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
//        builder.setStyle(AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.show();
    }

    private void showDeviceDetails(Device d) {
        if (killedManager == null) {
            Toast.makeText(requireContext(), "Manager not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        KilledDeviceInfo info = killedManager.getDeviceInfo(d.mac);
        if (info == null) {
            Toast.makeText(requireContext(), "Device info not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String details = "IP Address: " + info.ip +
                "\nMAC Address: " + info.mac +
                "\nVendor: " + info.vendor +
                "\nCustom Name: " + info.name +
                "\nKilled Since: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(info.timestamp));

        new AlertDialog.Builder(requireContext())
                .setTitle("Device Details")
                .setMessage(details)
                .setPositiveButton("Close", null)
//                .setStyle(AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .show();
    }

    public void loadKilledDevices() {
        // This is already on UI thread when called from refresh
        killedDevices.clear();
        killedDevices.addAll(killedManager.getKilledDevices());

        if (killedDevices.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            lvKilledDevices.setVisibility(View.GONE);
            btnClearAll.setEnabled(false);
            tvCount.setText("No devices");
        } else {
            tvEmptyState.setVisibility(View.GONE);
            lvKilledDevices.setVisibility(View.VISIBLE);
            btnClearAll.setEnabled(true);
            tvCount.setText(killedDevices.size() + " device" + (killedDevices.size() > 1 ? "s" : ""));
        }

        adapter.notifyDataSetChanged();
    }

    public void refresh() {
        // This might be called from background threads, so post to UI thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> loadKilledDevices());
        }
    }

    public void unkillDevice(Device d) {
        if (d == null) return;

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).unkillDevice(d);
        } else if (killedManager != null) {
            // Direct removal if not in MainActivity
            killedManager.removeKilledDevice(d);
            loadKilledDevices();
            Toast.makeText(requireContext(), "Device removed from killed list", Toast.LENGTH_SHORT).show();
        }
    }

    public void updateDevice(Device device) {
        if (device == null || device.mac == null) return;

        // Ensure manager is initialized
        if (killedManager == null) {
            if (getContext() != null) {
                killedManager = KilledDevicesManager.getInstance(getContext());
            }
            if (killedManager == null) return;
        }

        if (killedManager.isDeviceKilled(device)) {
            killedManager.updateDeviceInfo(device.mac, device.ip, device.vendor, null);

            for (int i = 0; i < killedDevices.size(); i++) {
                Device d = killedDevices.get(i);
                if (d.mac != null && d.mac.equals(device.mac)) {
                    d.ip = device.ip;
                    d.vendor = device.vendor;
                    break;
                }
            }
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Clear references to avoid memory leaks
        killedManager = null;
    }
}