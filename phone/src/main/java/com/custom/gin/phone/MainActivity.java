package com.custom.gin.phone;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.custom.gin.phone.chat.ChatAdapter;
import com.custom.gin.phone.chat.ChatMessage;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements GinServerManager.StatusListener {
    private static final int PERMISSION_REQUEST_CODE = 101;

    private TextView textConfigSummary;
    private TextView textServerStatus;
    private Button btnToggleServer;
    private MaterialSwitch switchMode;
    private RecyclerView recyclerChat;
    private ChatAdapter chatAdapter;

    private GinServerManager serverManager;
    private SharedPreferences prefs;

    // We will keep a local list of all logs + messages for display if needed, 
    // or just display ChatSession messages + server logs as SYSTEM messages.
    private final List<ChatMessage> displayMessages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("gin_prefs", MODE_PRIVATE);
        serverManager = GinServerManager.getInstance(this);

        Toolbar topAppBar = findViewById(R.id.topAppBar);
        topAppBar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_settings) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
                return false;
            }
        });

        textConfigSummary = findViewById(R.id.text_config_summary);
        textServerStatus = findViewById(R.id.text_server_status);
        btnToggleServer = findViewById(R.id.btn_toggle_server);
        switchMode = findViewById(R.id.switch_mode);
        recyclerChat = findViewById(R.id.recycler_chat);

        chatAdapter = new ChatAdapter();
        recyclerChat.setLayoutManager(new LinearLayoutManager(this));
        recyclerChat.setAdapter(chatAdapter);

        switchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            serverManager.setChatMode(isChecked);
            if (!isChecked) {
                displayMessages.clear();
                chatAdapter.setMessages(displayMessages);
                Toast.makeText(MainActivity.this, "Switched to Quick Mode (History Cleared)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "Switched to Chat Mode", Toast.LENGTH_SHORT).show();
            }
        });

        btnToggleServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serverManager.isRunning()) {
                    stopServerService();
                } else {
                    if (checkAndRequestPermissions()) {
                        startServerService();
                    } else {
                        Toast.makeText(MainActivity.this, "Permissions are required to run the server", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // Sync initial switch state with server manager
        serverManager.setChatMode(switchMode.isChecked());

        checkAndRequestPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        serverManager.setStatusListener(this);
        updateConfigSummary();
    }

    @Override
    protected void onStop() {
        super.onStop();
        serverManager.setStatusListener(null);
    }

    private void updateConfigSummary() {
        int activeIndex = prefs.getInt("active_preset_index", 1);
        String provider = prefs.getString("preset_provider_" + activeIndex, "Gemini");
        String model = prefs.getString("preset_model_" + activeIndex, "gemini-1.5-flash");
        String presetName = prefs.getString("preset_name_" + activeIndex, "Preset " + activeIndex);
        
        String summary = "RFCOMM | " + presetName + " (" + provider + " / " + model + ")";
        textConfigSummary.setText(summary);
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted && !serverManager.isRunning()) {
                startServerService();
            }
        }
    }

    @Override
    public void onServerStateChanged(boolean running, boolean connected) {
        if (running) {
            if (connected) {
                textServerStatus.setText("CLIENT CONNECTED");
                textServerStatus.setTextColor(Color.GREEN);
            } else {
                textServerStatus.setText("WAITING FOR CLIENT");
                textServerStatus.setTextColor(Color.BLUE);
            }
            btnToggleServer.setText("Stop Server");
        } else {
            textServerStatus.setText("STOPPED");
            textServerStatus.setTextColor(Color.RED);
            btnToggleServer.setText("Start Server");
        }
    }

    @Override
    public void onLogAdded(String logLine) {
        ChatMessage msg = new ChatMessage(ChatMessage.Role.SYSTEM, logLine);
        displayMessages.add(msg);
        chatAdapter.addMessage(msg);
        recyclerChat.scrollToPosition(displayMessages.size() - 1);
    }

    @Override
    public void onChatModeChanged(boolean isChatMode) {
        if (switchMode.isChecked() != isChatMode) {
            switchMode.setChecked(isChatMode);
        }
    }

    private void startServerService() {
        Intent serviceIntent = new Intent(this, GinServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopServerService() {
        Intent serviceIntent = new Intent(this, GinServerService.class);
        stopService(serviceIntent);
    }
}
