package com.xapaya.nfc;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private TextView tvLastScan, tvScanMessage, tvScanDetails;
    private Button btnSimulate;
    private RelativeLayout rootLayout;
    private FirebaseFirestore db;
    private String readerId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLastScan = findViewById(R.id.tvLastScan);
        tvScanMessage = findViewById(R.id.tvScanMessage);
        tvScanDetails = findViewById(R.id.tvScanDetails);
        btnSimulate = findViewById(R.id.btnSimulate);
        rootLayout = findViewById(R.id.rootLayout);

        db = FirebaseFirestore.getInstance();
        readerId = Build.MANUFACTURER + " " + Build.MODEL;
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        // Botón de simulación
        btnSimulate.setOnClickListener(view -> {
            String randomUid = generateRandomUid();
            processScan(randomUid);
        });
    }

    private String generateRandomUid() {
        Random rnd = new Random();
        int num = rnd.nextInt(30);
        return "bracelet_" + num;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                byte[] id = tag.getId();
                StringBuilder sb = new StringBuilder();
                for (byte b : id) sb.append(String.format("%02X", b));
                processScan(sb.toString());
            }
        }
    }

    private void processScan(final String uid) {
        final DocumentReference docRef = db.collection("scans").document(uid);
        docRef.get().addOnSuccessListener(documentSnapshot -> {
            long newCount = 1;
            String name = "Unknown";

            if (documentSnapshot.exists()) {
                Long count = documentSnapshot.getLong("count");
                if (count != null) newCount = count + 1;
                String dbName = documentSnapshot.getString("name");
                if (dbName != null) name = dbName;
            } else {
                name = generateRandomName();
            }

            Map<String, Object> data = new HashMap<>();
            data.put("uid", uid);
            data.put("name", name);
            data.put("count", newCount);
            data.put("lastScan", System.currentTimeMillis());
            data.put("readerId", readerId);

            String finalName = name;
            long finalNewCount = newCount;
            docRef.set(data).addOnSuccessListener(unused -> showEventMessage(finalName, uid, finalNewCount));
        });
    }

    private void showEventMessage(String name, String uid, long count) {
        tvScanMessage.setText("✔ OK! Conteo: " + count);
        tvScanMessage.setVisibility(View.VISIBLE);

        tvScanDetails.setText("Name: " + name + " | UID: " + uid);
        tvScanDetails.setVisibility(View.VISIBLE);

        tvLastScan.setText("Último conteo: " + count);

        // Animación de fondo verde
        rootLayout.setBackgroundColor(Color.parseColor("#A8E6A1"));
        new Handler().postDelayed(() -> rootLayout.setBackgroundColor(Color.WHITE), 1000);

        // Desaparece mensaje grande después de 2 segundos
        new Handler().postDelayed(() -> tvScanMessage.setVisibility(View.GONE), 2000);
    }

    private String generateRandomName() {
        String[] names = {"Trago Loco", "Chupito Express", "Capitan Mojito", "El Destilado",
                "Licor Loco", "Burbujitas", "Sorbitos", "Licoretas", "Ronrron", "Copita rebelde"};
        Random rnd = new Random();
        return names[rnd.nextInt(names.length)];
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
            IntentFilter[] filters = new IntentFilter[]{};
            String[][] techList = new String[][]{};
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, techList);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }
}