package com.app.netcut;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

public class DeviceAdapter extends ArrayAdapter<Device> {
    public DeviceAdapter(Context ctx, List<Device> list) {
        super(ctx, 0, list);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_device, parent, false);
        }

        Device d = getItem(position);
        TextView ip = convertView.findViewById(R.id.tvIp);
        TextView mac = convertView.findViewById(R.id.tvMac);
        TextView vendor = convertView.findViewById(R.id.tvVendor);

        String tag = d.isGateway ? " (Gateway)" : (d.isCut ? " (Cut)" : " (Online)");
        ip.setText(d.ip + tag);
        mac.setText(d.mac);
        vendor.setText(d.vendor);

        if (d.isGateway) {
            ip.setTextColor(Color.parseColor("#FFC107"));
        } else if (d.isCut) {
            ip.setTextColor(Color.parseColor("#F44336"));
        } else {
            ip.setTextColor(Color.parseColor("#4CAF50"));
        }

        return convertView;
    }
}