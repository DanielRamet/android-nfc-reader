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
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.firebase.auth.FirebaseAuth;
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

    private boolean isProcessing = false;
    private String lastUid = "";
    private long lastScanTime = 0;
    private static final long SCAN_COOLDOWN_MS = 3000;
    private RelativeLayout loadingOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Actualizar el proveedor SSL para corregir el error de ALPN en gRPC/SSL
        ProviderInstaller.installIfNeededAsync(this, new ProviderInstaller.ProviderInstallListener() {
            @Override
            public void onProviderInstalled() {
                // El motor SSL moderno ya está activo para gRPC / Firebase
                Log.d("SSLProvider", "Proveedor de seguridad SSL actualizado correctamente.");
            }

            @Override
            public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
                Log.e("SSLProvider", "Error al actualizar el proveedor SSL: " + errorCode);

                // 1. Mostrar diálogo de recuperación si Google Play Services requiere interacción del usuario
                GoogleApiAvailability availability = GoogleApiAvailability.getInstance();
                if (availability.isUserResolvableError(errorCode)) {
                    availability.showErrorDialogFragment(MainActivity.this, errorCode, 1, dialog -> {
                        // El usuario cerró el diálogo de recuperación
                    });
                } else {
                    // 2. Si no es resoluble, notificar que el dispositivo puede no ser compatible
                    Toast.makeText(MainActivity.this,
                            "Dispositivo no compatible con las conexiones SSL avanzadas de Firebase.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        tvLastScan = findViewById(R.id.tvLastScan);
        tvScanMessage = findViewById(R.id.tvScanMessage);
        tvScanDetails = findViewById(R.id.tvScanDetails);
        btnSimulate = findViewById(R.id.btnSimulate);
        rootLayout = findViewById(R.id.rootLayout);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        db = FirebaseFirestore.getInstance();
        FirebaseAuth.getInstance().signInAnonymously()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        tvLastScan.setText("Auth OK");
                    } else {
                        tvLastScan.setText("Auth ERROR");
                    }
                });

        readerId = Build.MANUFACTURER + " " + Build.MODEL;
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC no soportado", Toast.LENGTH_LONG).show();
        }

        // Botón de simulación
        btnSimulate.setOnClickListener(view -> {
            String[] testUids = {
                    "TEST_UID_1",
                    "TEST_UID_2",
                    "TEST_UID_3"
            };
            String uid = testUids[new Random().nextInt(testUids.length)];
            processScan(uid);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {

                byte[] id = tag.getId();

                StringBuilder sb = new StringBuilder();

                for (byte b : id) {
                    sb.append(String.format("%02X", b));
                }

                loadingOverlay.setVisibility(View.VISIBLE);
                processScan(sb.toString());
            }
        }
    }

    private void processScan(final String uid) {
        long now = System.currentTimeMillis();

        if (uid.equals(lastUid) && (now - lastScanTime) < SCAN_COOLDOWN_MS) {
            return;
        }

        lastUid = uid;
        lastScanTime = now;

        if (isProcessing) return;
        isProcessing = true;

        final DocumentReference docRef = db.collection("scans").document(uid);

        docRef.get().addOnSuccessListener(documentSnapshot -> {

            if (!documentSnapshot.exists()) {
                // 🔴 UID NUEVO → pedir nombre
                promptForName(uid);
                return;
            }

            // 🟢 UID EXISTENTE → incrementar contador
            Long count = documentSnapshot.getLong("count");
            String name = documentSnapshot.getString("name");

            long newCount = (count != null) ? count + 1 : 1;

            Map<String, Object> data = new HashMap<>();
            data.put("uid", uid);
            data.put("name", name);
            data.put("count", newCount);
            data.put("lastScan", System.currentTimeMillis());
            data.put("readerId", readerId);

            docRef.set(data).addOnSuccessListener(unused -> {
                showEventMessage(name, uid, newCount);
                isProcessing = false;

            });
        });
    }

    private void promptForName(String uid) {

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Nuevo chip detectado");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Nombre (opcional)");
        builder.setView(input);

        builder.setPositiveButton("Guardar", (dialog, which) -> {

            String name = input.getText().toString().trim();

            if (name.isEmpty()) {
                name = generateRandomName();
            }

            long count = 0;

            Map<String, Object> data = new HashMap<>();
            data.put("uid", uid);
            data.put("name", name);
            data.put("count", count);
            data.put("lastScan", System.currentTimeMillis());
            data.put("readerId", readerId);

            final String finalName = name;

            db.collection("scans").document(uid)
                    .set(data)
                    .addOnSuccessListener(unused ->  {
                        showEventMessage(finalName, uid, count);
                        isProcessing = false;
                    });
        });

        builder.setNegativeButton("Cancelar", (dialog, which) -> {
            loadingOverlay.setVisibility(View.GONE);
            isProcessing = false;
            dialog.cancel();
        });
        builder.show();
    }

    private void showEventMessage(String name, String uid, long count) {
        loadingOverlay.setVisibility(View.GONE);
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
            PendingIntent pendingIntent;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_MUTABLE
                );
            } else {
                pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        0
                );
            }

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