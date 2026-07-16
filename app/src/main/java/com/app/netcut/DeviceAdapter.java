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


import androidx.annotation.NonNull;


import com.app.netcut.KilledDevicesManager.KilledDeviceInfo;


import java.util.List;

public class DeviceAdapter extends ArrayAdapter<Device> {
    private final MainActivity context;
    Button kill_and_revive_button;

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
        TextView ipAddressNameHolder = convertView.findViewById(R.id.ipAddressNameHolder);
        TextView macAddressNameHolder = convertView.findViewById(R.id.macAddressNameHolder);
        TextView deviceVendorTypeHolder = convertView.findViewById(R.id.deviceVendorTypeHolder);
        kill_and_revive_button= convertView.findViewById(R.id.kill_and_revive_button);
        LinearLayout hz_bg_layout=convertView.findViewById(R.id.horizontal_colorFilled_layout);



        ipAddressNameHolder.setText(d.ip);
        macAddressNameHolder.setText(d.mac);
        deviceVendorTypeHolder.setText(d.vendor);

        kill_and_revive_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                context.toggleKillDirectly(position);
            }
        });

        // Check if device is in killed list
        KilledDevicesManager manager = KilledDevicesManager.getInstance(context);
//        KilledDeviceInfo info = manager.getDeviceInfo(d.mac);

        if (d.isGateway) {
            kill_and_revive_button.setText("Gateway");
            kill_and_revive_button.setTextColor(Color.parseColor("#FFC107"));
            kill_and_revive_button.setBackgroundResource(R.drawable.border_for_listview_items_yellow);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_yellow);
            ipAddressNameHolder.setTextColor(Color.parseColor("#FFC107"));
        } else if (d.isCut) {
            kill_and_revive_button.setText("Revive");
            kill_and_revive_button.setTextColor(Color.parseColor("#4CAF50"));
            ipAddressNameHolder.setTextColor(Color.parseColor("#F44336"));
            kill_and_revive_button.setBackgroundResource(R.drawable.border_for_listview_items_green);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_red);
//        } else if (info != null) {
//            statusBadge.setText(".....");
//            statusBadge.setTextColor(Color.parseColor("#FFBF00"));
//            ipAddressNameHolder.setTextColor(Color.parseColor("#A5C4E8"));
//            statusBadge.setBackgroundResource(R.drawable.border_for_listview_items_red);
//            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_red);
        } else {
            kill_and_revive_button.setText("Cut");
            kill_and_revive_button.setTextColor(Color.parseColor("#FF6B6B"));
            ipAddressNameHolder.setTextColor(Color.parseColor("#4CAF50"));
            kill_and_revive_button.setBackgroundResource(R.drawable.border_for_listview_items_red);
            hz_bg_layout.setBackgroundResource(R.drawable.bg_for_item_view_green);
        }

        return convertView;
    }
}
