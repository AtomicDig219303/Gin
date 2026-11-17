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

import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import java.nio.charset.StandardCharsets;

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
        setContentView(cardScrollView);

        cardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    cycleModelPreset();
                } else if (position == 1) {
                    toggleChatMode();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cardScrollView != null) {
            cardScrollView.activate();
        }
        GinConnectionManager.getInstance(this).ensureConnected();
    }

    @Override
    protected void onPause() {
        if (cardScrollView != null) {
            cardScrollView.deactivate();
        }
        super.onPause();
    }

    private void cycleModelPreset() {
        int currentIdx = prefs.getInt("active_preset_index", 1);
        int nextIdx = (currentIdx % 3) + 1; // Cycles 1 -> 2 -> 3 -> 1

        audioManager.playSoundEffect(Sounds.SELECTED);

        // Save locally
        prefs.edit().putInt("active_preset_index", nextIdx).apply();

        // Send control message to phone
        GinConnectionManager mgr = GinConnectionManager.getInstance(this);
        String payload = "SET_PRESET:" + nextIdx;
        mgr.sendData(payload.getBytes(StandardCharsets.UTF_8), com.custom.gin.transport.GinTransport.TYPE_CONTROL);

        Log.d(TAG, "Cycled preset to " + nextIdx);
        adapter.notifyDataSetChanged();
    }

    private void toggleChatMode() {
        boolean isChatMode = prefs.getBoolean("is_chat_mode", true);
        boolean newMode = !isChatMode;

        audioManager.playSoundEffect(Sounds.SELECTED);

        // Save locally
        prefs.edit().putBoolean("is_chat_mode", newMode).apply();

        // Send control message to phone
        GinConnectionManager mgr = GinConnectionManager.getInstance(this);
        String payload = "SET_CHAT_MODE:" + newMode;
        mgr.sendData(payload.getBytes(StandardCharsets.UTF_8), com.custom.gin.transport.GinTransport.TYPE_CONTROL);

        Log.d(TAG, "Chat mode toggled to " + newMode);
        adapter.notifyDataSetChanged();
    }

    private class SettingsCardAdapter extends CardScrollAdapter {
        private final CardBuilder[] mCards;

        public SettingsCardAdapter() {
            mCards = new CardBuilder[2];
            for (int i = 0; i < 2; i++) {
                mCards[i] = new CardBuilder(SettingsActivity.this, CardBuilder.Layout.TEXT);
            }
        }

        @Override
        public int getPosition(Object item) {
            if (item instanceof Integer) {
                int pos = (Integer) item;
                if (pos >= 0 && pos < mCards.length) {
                    return pos;
                }
            }
            return AdapterView.INVALID_POSITION;
        }

        @Override
        public int getCount() {
            return mCards.length;
        }

        @Override
        public Object getItem(int position) {
            return Integer.valueOf(position);
        }

        @Override
        public int getViewTypeCount() {
            return CardBuilder.getViewTypeCount();
        }

        @Override
        public int getItemViewType(int position) {
            return mCards[position].getItemViewType();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CardBuilder card = mCards[position];

            if (position == 0) {
                // Model Preset Card
                int activeIndex = prefs.getInt("active_preset_index", 1);
                String name = prefs.getString("preset_name_" + activeIndex, "Preset " + activeIndex);

                card.setText("Model: " + name);
                card.setFootnote("Tap to cycle preset (" + activeIndex + "/3)");
            } else if (position == 1) {
                // Chat Mode Card
                boolean isChatMode = prefs.getBoolean("is_chat_mode", true);
                if (isChatMode) {
                    card.setText("Mode: Chat");
                    card.setFootnote("Tap to switch to Quick Mode");
                } else {
                    card.setText("Mode: Quick");
                    card.setFootnote("Tap to switch to Chat Mode");
                }
            }

            return card.getView(convertView, parent);
        }
    }
}
