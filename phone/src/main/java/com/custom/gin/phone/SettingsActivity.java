package com.custom.gin.phone;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private Spinner spinnerTransport;
    private Spinner spinnerProvider;
    private EditText editApiKey;
    private EditText editModelName;
    private EditText editCustomUrl;
    private EditText editSystemPrompt;
    private Button btnSave;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("gin_prefs", MODE_PRIVATE);

        // Bind views
        spinnerTransport = findViewById(R.id.spinner_transport);
        spinnerProvider = findViewById(R.id.spinner_provider);
        editApiKey = findViewById(R.id.edit_api_key);
        editModelName = findViewById(R.id.edit_model_name);
        editCustomUrl = findViewById(R.id.edit_custom_url);
        editSystemPrompt = findViewById(R.id.edit_system_prompt);
        btnSave = findViewById(R.id.btn_save);

        // Setup Spinners
        String[] transports = {"Bluetooth Classic (RFCOMM)", "Bluetooth Low Energy (BLE)"};
        ArrayAdapter<String> transportAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, transports);
        transportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTransport.setAdapter(transportAdapter);

        String[] providers = {"Gemini", "Anthropic Claude", "OpenAI GPT", "Custom Self-Hosted"};
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, providers);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(providerAdapter);

        // Load values
        loadSettings();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
    }

    private void loadSettings() {
        boolean useBle = prefs.getBoolean("use_ble", false);
        spinnerTransport.setSelection(useBle ? 1 : 0);

        String provider = prefs.getString("provider", "Gemini");
        int providerIndex = 0;
        if ("Anthropic Claude".equals(provider)) providerIndex = 1;
        else if ("OpenAI GPT".equals(provider)) providerIndex = 2;
        else if ("Custom Self-Hosted".equals(provider)) providerIndex = 3;
        spinnerProvider.setSelection(providerIndex);

        editApiKey.setText(prefs.getString("api_key", ""));
        editModelName.setText(prefs.getString("model_name", "gemini-1.5-flash"));
        editCustomUrl.setText(prefs.getString("custom_url", ""));
        editSystemPrompt.setText(prefs.getString("system_prompt", 
                "You are an AI assistant on Google Glass. Keep responses very short, concise, and direct (max 2 sentences)."));
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        
        boolean useBle = spinnerTransport.getSelectedItemPosition() == 1;
        editor.putBoolean("use_ble", useBle);

        String provider = spinnerProvider.getSelectedItem().toString();
        editor.putString("provider", provider);

        editor.putString("api_key", editApiKey.getText().toString().trim());
        editor.putString("model_name", editModelName.getText().toString().trim());
        editor.putString("custom_url", editCustomUrl.getText().toString().trim());
        editor.putString("system_prompt", editSystemPrompt.getText().toString().trim());

        editor.apply();

        Toast.makeText(this, "Configuration saved successfully", Toast.LENGTH_SHORT).show();
        finish();
    }
}
