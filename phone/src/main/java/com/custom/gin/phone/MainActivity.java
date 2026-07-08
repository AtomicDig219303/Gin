package com.custom.gin.phone;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements GinServerManager.StatusListener {
    private static final int PERMISSION_REQUEST_CODE = 101;

    private TextView textConfigSummary;
    private TextView textServerStatus;
    private Button btnToggleServer;
    private Button btnOpenSettings;
    private TextView textLogs;
    private ScrollView scrollLogs;

    private GinServerManager serverManager;
    private SharedPreferences prefs;
    private final StringBuilder logsBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("gin_prefs", MODE_PRIVATE);
        serverManager = GinServerManager.getInstance(this);

        // Bind views
        textConfigSummary = findViewById(R.id.text_config_summary);
        textServerStatus = findViewById(R.id.text_server_status);
        btnToggleServer = findViewById(R.id.btn_toggle_server);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        textLogs = findViewById(R.id.text_logs);
        scrollLogs = findViewById(R.id.scroll_logs);

        // Setup click listeners
        btnToggleServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serverManager.isRunning()) {
                    serverManager.stopServer();
                } else {
                    if (checkAndRequestPermissions()) {
                        serverManager.startServer();
                    } else {
                        Toast.makeText(MainActivity.this, "Permissions are required to run the server", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        btnOpenSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        // Request permissions on startup
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
        boolean useBle = prefs.getBoolean("use_ble", false);
        String provider = prefs.getString("provider", "Gemini");
        String model = prefs.getString("model_name", "gemini-1.5-flash");
        
        String summary = "Config: " + (useBle ? "BLE" : "RFCOMM") + " | " + provider + " | " + model;
        textConfigSummary.setText(summary);
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else { // Older Android versions
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
                serverManager.startServer();
            }
        }
    }

    // Server state callbacks
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
        logsBuilder.append(logLine).append("\n");
        textLogs.setText(logsBuilder.toString());
        
        // Auto scroll to bottom
        scrollLogs.post(new Runnable() {
            @Override
            public void run() {
                scrollLogs.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
