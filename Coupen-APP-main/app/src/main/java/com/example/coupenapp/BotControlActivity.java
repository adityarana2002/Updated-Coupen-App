package com.example.coupenapp;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.coupenapp.telegram.BotState;
import com.example.coupenapp.telegram.TelegramBotManager;
import com.example.coupenapp.telegram.TelegramConfig;

/**
 * Control panel for the on-device Telegram auto-claim listener.
 * Observes {@link TelegramBotManager} for live status + log updates.
 */
public class BotControlActivity extends AppCompatActivity implements TelegramBotManager.Listener {

    private EditText tokenEditText, groupEditText;
    private Button saveButton, testButton, startButton, stopButton;
    private CheckBox autoStartCheck;
    private TextView statusText, statsText, logText;

    private TelegramBotManager manager;
    private TelegramConfig config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bot_control);

        manager = TelegramBotManager.getInstance(this);
        config = new TelegramConfig(this);

        tokenEditText  = findViewById(R.id.tokenEditText);
        groupEditText  = findViewById(R.id.groupEditText);
        saveButton     = findViewById(R.id.saveConfigButton);
        testButton     = findViewById(R.id.testConnButton);
        startButton    = findViewById(R.id.startBotButton);
        stopButton     = findViewById(R.id.stopBotButton);
        autoStartCheck = findViewById(R.id.autoStartCheck);
        statusText     = findViewById(R.id.statusText);
        statsText      = findViewById(R.id.statsText);
        logText        = findViewById(R.id.logText);
        logText.setMovementMethod(new ScrollingMovementMethod());

        // Load saved config
        tokenEditText.setText(config.getBotToken());
        groupEditText.setText(config.getGroupName());
        autoStartCheck.setChecked(config.isAutoStart());

        saveButton.setOnClickListener(v -> { saveConfig(); toast("Configuration saved"); });

        testButton.setOnClickListener(v -> {
            saveConfig();
            toast("Testing connection…");
            manager.testConnection((ok, msg) ->
                    toast(ok ? "✅ Connected: " + msg : "❌ Failed: " + msg));
        });

        startButton.setOnClickListener(v -> { saveConfig(); manager.start(); });
        stopButton.setOnClickListener(v -> manager.stop());
        autoStartCheck.setOnCheckedChangeListener((b, checked) -> config.setAutoStart(checked));

        // Populate existing log history
        StringBuilder sb = new StringBuilder();
        for (String line : manager.getLogHistory()) sb.append(line).append("\n");
        logText.setText(sb.toString());

        refreshStatus();
    }

    private void saveConfig() {
        config.setBotToken(tokenEditText.getText().toString().trim());
        config.setGroupName(groupEditText.getText().toString().trim());
        config.setAutoStart(autoStartCheck.isChecked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.addListener(this);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        manager.removeListener(this);
    }

    private void refreshStatus() {
        BotState s = manager.getState();
        String dot;
        switch (s) {
            case RUNNING:                 dot = "🟢"; break;
            case CONNECTING:
            case RECONNECTING:            dot = "🟡"; break;
            case ERROR:                   dot = "🔴"; break;
            default:                      dot = "⚪"; break;
        }
        statusText.setText(dot + " Telegram Bot " + s.label());
        statsText.setText(
                "Connection:      " + s.label()
                        + "\nLast connected:  " + manager.getLastConnectionTime()
                        + "\nLast coupon:     " + manager.getLastCouponCode() + "   @ " + manager.getLastCouponTime()
                        + "\nReceived:        " + manager.getTotalReceived()
                        + "     Processed: " + manager.getTotalProcessed()
                        + "\nLast error:      " + manager.getLastError());

        boolean running = manager.isRunning();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    // ── TelegramBotManager.Listener (already posted to main thread) ──
    @Override
    public void onStateChanged(BotState state) {
        runOnUiThread(this::refreshStatus);
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> {
            logText.append(line + "\n");
            refreshStatus();
        });
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
