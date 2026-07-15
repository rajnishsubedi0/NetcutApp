package com.app.netcut;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;


import com.app.netcut.KilledDevicesManager.KilledDeviceInfo;


import java.util.List;

public class DeviceAdapter extends ArrayAdapter<Device> {
    private final MainActivity context;
    Button statusBadge;

    public DeviceAdapter(Context ctx, List<Device> list) {
        super(ctx, 0, list);
        this.context = (MainActivity) ctx;
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
        statusBadge= convertView.findViewById(R.id.tvStatusBadge);
        LinearLayout hz_bg_layout=convertView.findViewById(R.id.horizontalLinearLayoutForBg);



        ip.setText(d.ip);
        mac.setText(d.mac);
        vendor.setText(d.vendor);

        statusBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                context.toggleKillDirectly(position);
            }
        });

        // Check if device is in killed list
        KilledDevicesManager manager = KilledDevicesManager.getInstance(context);
        KilledDeviceInfo info = manager.getDeviceInfo(d.mac);

        if (d.isGateway) {
            statusBadge.setText("Gateway");
            statusBadge.setTextColor(Color.parseColor("#FFC107"));
            statusBadge.setBackgroundResource(R.drawable.border_for_listview_items_yellow);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_yellow);
            ip.setTextColor(Color.parseColor("#FFC107"));
        } else if (d.isCut) {
            statusBadge.setText("Revive");
            statusBadge.setTextColor(Color.parseColor("#4CAF50"));
            ip.setTextColor(Color.parseColor("#F44336"));
            statusBadge.setBackgroundResource(R.drawable.border_for_listview_items_green);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_red);
        } else if (info != null) {
            statusBadge.setText(".....");
            statusBadge.setTextColor(Color.parseColor("#FFBF00"));
            ip.setTextColor(Color.parseColor("#A5C4E8"));
            statusBadge.setBackgroundResource(R.drawable.border_for_listview_items_red);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_red);
        } else {
            statusBadge.setText("Cut");
            statusBadge.setTextColor(Color.parseColor("#FF6B6B"));
            ip.setTextColor(Color.parseColor("#4CAF50"));
            statusBadge.setBackgroundResource(R.drawable.border_for_listview_items_red);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_green);
        }

        return convertView;
    }
}
