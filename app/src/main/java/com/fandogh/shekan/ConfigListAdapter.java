package com.fandogh.shekan;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.TextView;
import java.util.List;

public class ConfigListAdapter extends ArrayAdapter<ConfigModel> {
    private Context context;
    private List<ConfigModel> configs;
    private int selectedPosition = -1;

    public ConfigListAdapter(Context context, List<ConfigModel> configs) {
        super(context, 0, configs);
        this.context = context;
        this.configs = configs;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(
                    android.R.layout.simple_list_item_single_choice, parent, false);
        }

        ConfigModel config = configs.get(position);
        TextView textView = convertView.findViewById(android.R.id.text1);
        RadioButton radioButton = convertView.findViewById(android.R.id.checkbox);

        String displayText = String.format("%s\nPing: %dms | %s | وضعیت: %s",
                config.getName(),
                config.getPingMs(),
                config.getSpeed(),
                config.getStatus());

        textView.setText(displayText);
        radioButton.setChecked(position == selectedPosition);

        convertView.setOnClickListener(v -> {
            selectedPosition = position;
            notifyDataSetChanged();
        });

        return convertView;
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public ConfigModel getSelectedConfig() {
        if (selectedPosition >= 0 && selectedPosition < configs.size()) {
            return configs.get(selectedPosition);
        }
        return null;
    }
}
