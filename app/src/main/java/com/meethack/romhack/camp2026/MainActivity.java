package com.meethack.romhack.camp2026;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        findViewById(R.id.buttonCreateCustomer).setOnClickListener(v ->
                startActivity(new Intent(this, CreateCustomerActivity.class)));
        findViewById(R.id.buttonPos).setOnClickListener(v ->
                startActivity(new Intent(this, PosActivity.class)));
        findViewById(R.id.buttonFormat).setOnClickListener(v ->
                startActivity(new Intent(this, CardFormatActivity.class)));
        findViewById(R.id.buttonRead).setOnClickListener(v ->
                startActivity(new Intent(this, CardReadActivity.class)));
    }
}