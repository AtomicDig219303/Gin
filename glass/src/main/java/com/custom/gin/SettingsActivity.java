package com.custom.gin;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";

    private CardScrollView cardScrollView;
    private SettingsCardAdapter adapter;
    private AudioManager audioManager;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences("gin_glass_prefs", MODE_PRIVATE);

        adapter = new SettingsCardAdapter();
        cardScrollView = new CardScrollView(this);
        cardScrollView.setAdapter(adapter);
        cardScrollView.activate();
        setContentView(cardScrollView);

        cardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    switchTransport();
                } else if (position == 1) {
                    cycleModelPreset();
                }
            }
        });
    }

    private void switchTransport() {
        GinConnectionManager mgr = GinConnectionManager.getInstance(this);
        String current = mgr.getCurrentTransportType();
        String newType = "BLE".equals(current) ? "RFCOMM" : "BLE";

        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
        mgr.selectTransport(newType);

        Log.d(TAG, "Transport switched to " + newType);
        adapter.notifyDataSetChanged();
    }

    private void cycleModelPreset() {
        int currentIdx = prefs.getInt("active_preset_index", 1);
        int nextIdx = (currentIdx % 3) + 1; // Cycles 1 -> 2 -> 3 -> 1

        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);

        // Save locally
        prefs.edit().putInt("active_preset_index", nextIdx).apply();

        // Send control message to phone
        GinConnectionManager mgr = GinConnectionManager.getInstance(this);
        String payload = "SET_PRESET:" + nextIdx;
        mgr.sendData(payload.getBytes(), com.custom.gin.transport.GinTransport.TYPE_CONTROL);

        Log.d(TAG, "Cycled preset to " + nextIdx);
        adapter.notifyDataSetChanged();
    }

    private class SettingsCardAdapter extends CardScrollAdapter {
        @Override
        public int getPosition(Object item) {
            return AdapterView.INVALID_POSITION;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CardBuilder card = new CardBuilder(SettingsActivity.this, CardBuilder.Layout.TEXT);

            if (position == 0) {
                // Transport Card
                GinConnectionManager mgr = GinConnectionManager.getInstance(SettingsActivity.this);
                String current = mgr.getCurrentTransportType();
                card.setText("Transport: " + current);
                if ("BLE".equals(current)) {
                    card.setFootnote("Tap to switch to RFCOMM");
                } else {
                    card.setFootnote("Tap to switch to BLE");
                }
            } else if (position == 1) {
                // Model Preset Card
                int activeIndex = prefs.getInt("active_preset_index", 1);
                String name = prefs.getString("preset_name_" + activeIndex, "Preset " + activeIndex);

                card.setText("Model: " + name);
                card.setFootnote("Tap to cycle to next preset");
            }

            return card.getView(convertView, parent);
        }
    }
}
