package com.example.coupenapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;

public class ManageUrlActivity extends AppCompatActivity {

    private EditText urlNameEditText;
    private EditText urlEditText;
    private Button addUrlButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_url);

        urlNameEditText = findViewById(R.id.urlNameEditText);
        urlEditText = findViewById(R.id.urlEditText);
        addUrlButton = findViewById(R.id.addUrlButton);

        addUrlButton.setOnClickListener(v -> {
            String name = urlNameEditText.getText().toString().trim();
            String url = urlEditText.getText().toString().trim();

            if (!name.isEmpty() && !url.isEmpty()) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("name", name);
                resultIntent.putExtra("url", url);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }
}
