package com.custom.gin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class VoiceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.putExtra("start_mode", "audio");
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(mainIntent);
        finish();
    }
}
