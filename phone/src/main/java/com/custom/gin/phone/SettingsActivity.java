package com.custom.gin.phone;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {
    private Spinner spinnerTransport;
    private EditText editApiKeyGemini;
    private EditText editApiKeyOpenai;
    private EditText editApiKeyAnthropic;
    private EditText editCustomUrl;
    private EditText editSystemPrompt;

    // Preset 1
    private EditText editPresetName1;
    private Spinner spinnerProvider1;
    private EditText editModelName1;
    private Spinner spinnerReasoning1;
    private Switch switchAudio1;

    // Preset 2
    private EditText editPresetName2;
    private Spinner spinnerProvider2;
    private EditText editModelName2;
    private Spinner spinnerReasoning2;
    private Switch switchAudio2;

    // Preset 3
    private EditText editPresetName3;
    private Spinner spinnerProvider3;
    private EditText editModelName3;
    private Spinner spinnerReasoning3;
    private Switch switchAudio3;

    private Button btnSave;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        prefs = getSharedPreferences("gin_prefs", MODE_PRIVATE);

        // Bind global views
        spinnerTransport = findViewById(R.id.spinner_transport);
        editApiKeyGemini = findViewById(R.id.edit_api_key_gemini);
        editApiKeyOpenai = findViewById(R.id.edit_api_key_openai);
        editApiKeyAnthropic = findViewById(R.id.edit_api_key_anthropic);
        editCustomUrl = findViewById(R.id.edit_custom_url);
        editSystemPrompt = findViewById(R.id.edit_system_prompt);

        // Bind Preset 1
        editPresetName1 = findViewById(R.id.edit_preset_name_1);
        spinnerProvider1 = findViewById(R.id.spinner_provider_1);
        editModelName1 = findViewById(R.id.edit_model_name_1);
        spinnerReasoning1 = findViewById(R.id.spinner_reasoning_1);
        switchAudio1 = findViewById(R.id.switch_audio_1);

        // Bind Preset 2
        editPresetName2 = findViewById(R.id.edit_preset_name_2);
        spinnerProvider2 = findViewById(R.id.spinner_provider_2);
        editModelName2 = findViewById(R.id.edit_model_name_2);
        spinnerReasoning2 = findViewById(R.id.spinner_reasoning_2);
        switchAudio2 = findViewById(R.id.switch_audio_2);

        // Bind Preset 3
        editPresetName3 = findViewById(R.id.edit_preset_name_3);
        spinnerProvider3 = findViewById(R.id.spinner_provider_3);
        editModelName3 = findViewById(R.id.edit_model_name_3);
        spinnerReasoning3 = findViewById(R.id.spinner_reasoning_3);
        switchAudio3 = findViewById(R.id.switch_audio_3);

        btnSave = findViewById(R.id.btn_save);

        setupSpinners();
        loadSettings();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
    }

    private void setupSpinners() {
        String[] transports = {"Bluetooth Classic (RFCOMM)", "Bluetooth Low Energy (BLE)"};
        ArrayAdapter<String> transportAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, transports);
        transportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTransport.setAdapter(transportAdapter);

        String[] providers = {"Gemini", "Anthropic Claude", "OpenAI GPT", "Custom Self-Hosted"};
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, providers);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        spinnerProvider1.setAdapter(providerAdapter);
        spinnerProvider2.setAdapter(providerAdapter);
        spinnerProvider3.setAdapter(providerAdapter);

        String[] reasonings = {"None", "High"};
        ArrayAdapter<String> reasoningAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, reasonings);
        reasoningAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        spinnerReasoning1.setAdapter(reasoningAdapter);
        spinnerReasoning2.setAdapter(reasoningAdapter);
        spinnerReasoning3.setAdapter(reasoningAdapter);
    }

    private void loadSettings() {
        boolean useBle = prefs.getBoolean("use_ble", false);
        spinnerTransport.setSelection(useBle ? 1 : 0);

        editApiKeyGemini.setText(prefs.getString("api_key_gemini", ""));
        editApiKeyOpenai.setText(prefs.getString("api_key_openai", ""));
        editApiKeyAnthropic.setText(prefs.getString("api_key_anthropic", ""));
        editCustomUrl.setText(prefs.getString("custom_url", ""));
        editSystemPrompt.setText(prefs.getString("system_prompt", 
                "You are an AI assistant on Google Glass. Keep responses very short, concise, and direct (max 2 sentences)."));

        loadPreset(1, editPresetName1, spinnerProvider1, editModelName1, spinnerReasoning1, switchAudio1);
        loadPreset(2, editPresetName2, spinnerProvider2, editModelName2, spinnerReasoning2, switchAudio2);
        loadPreset(3, editPresetName3, spinnerProvider3, editModelName3, spinnerReasoning3, switchAudio3);
    }

    private void loadPreset(int index, EditText name, Spinner provider, EditText model, Spinner reasoning, Switch audio) {
        name.setText(prefs.getString("preset_name_" + index, "Preset " + index));
        
        String provStr = prefs.getString("preset_provider_" + index, "Gemini");
        int providerIndex = 0;
        if ("Anthropic Claude".equals(provStr)) providerIndex = 1;
        else if ("OpenAI GPT".equals(provStr)) providerIndex = 2;
        else if ("Custom Self-Hosted".equals(provStr)) providerIndex = 3;
        provider.setSelection(providerIndex);

        model.setText(prefs.getString("preset_model_" + index, "gemini-1.5-flash"));
        
        String resStr = prefs.getString("preset_reasoning_" + index, "None");
        reasoning.setSelection("High".equals(resStr) ? 1 : 0);

        audio.setChecked(prefs.getBoolean("preset_audio_" + index, true));
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        
        boolean useBle = spinnerTransport.getSelectedItemPosition() == 1;
        editor.putBoolean("use_ble", useBle);

        editor.putString("api_key_gemini", editApiKeyGemini.getText().toString().trim());
        editor.putString("api_key_openai", editApiKeyOpenai.getText().toString().trim());
        editor.putString("api_key_anthropic", editApiKeyAnthropic.getText().toString().trim());
        editor.putString("custom_url", editCustomUrl.getText().toString().trim());
        editor.putString("system_prompt", editSystemPrompt.getText().toString().trim());

        savePreset(editor, 1, editPresetName1, spinnerProvider1, editModelName1, spinnerReasoning1, switchAudio1);
        savePreset(editor, 2, editPresetName2, spinnerProvider2, editModelName2, spinnerReasoning2, switchAudio2);
        savePreset(editor, 3, editPresetName3, spinnerProvider3, editModelName3, spinnerReasoning3, switchAudio3);

        editor.apply();

        Toast.makeText(this, "Configuration saved successfully", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void savePreset(SharedPreferences.Editor editor, int index, EditText name, Spinner provider, EditText model, Spinner reasoning, Switch audio) {
        editor.putString("preset_name_" + index, name.getText().toString().trim());
        editor.putString("preset_provider_" + index, provider.getSelectedItem().toString());
        editor.putString("preset_model_" + index, model.getText().toString().trim());
        editor.putString("preset_reasoning_" + index, reasoning.getSelectedItem().toString());
        editor.putBoolean("preset_audio_" + index, audio.isChecked());
    }
}
