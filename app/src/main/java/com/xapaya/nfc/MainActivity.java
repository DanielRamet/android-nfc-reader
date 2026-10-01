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

    private TextView tvLastScan;
    private TextView tvScanMessage;
    private TextView tvScanDetails;

    private TextView tvWelcome;
    private TextView tvWelcomeSubtitle1;
    private TextView tvWelcomeSubtitle2;
    private View layoutScanResult;

    private RelativeLayout rootLayout;
    private RelativeLayout loadingOverlay;

    private View layoutWelcome;

    private FirebaseFirestore db;

    private String readerId;

    private boolean isProcessing = false;

    private String lastUid = "";
    private long lastScanTime = 0;

    private static final long SCAN_COOLDOWN_MS = 3000;

    private final Handler handler = new Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        // ====================================================
        // Actualizar proveedor SSL
        // ====================================================
        ProviderInstaller.installIfNeededAsync(
                this,
                new ProviderInstaller.ProviderInstallListener() {

                    @Override
                    public void onProviderInstalled() {

                        Log.d(
                                "SSLProvider",
                                "Proveedor de seguridad SSL actualizado correctamente."
                        );
                    }

                    @Override
                    public void onProviderInstallFailed(
                            int errorCode,
                            Intent recoveryIntent) {

                        Log.e(
                                "SSLProvider",
                                "Error al actualizar el proveedor SSL: "
                                        + errorCode
                        );

                        GoogleApiAvailability availability =
                                GoogleApiAvailability.getInstance();

                        if (availability.isUserResolvableError(errorCode)) {

                            availability.showErrorDialogFragment(
                                    MainActivity.this,
                                    errorCode,
                                    1,
                                    dialog -> {
                                        // El usuario cerró el diálogo
                                    }
                            );

                        } else {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Dispositivo no compatible con las conexiones SSL avanzadas de Firebase.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                }
        );


        // ====================================================
        // Referencias del layout
        // ====================================================

        rootLayout = findViewById(R.id.rootLayout);

        //tvLastScan = findViewById(R.id.tvLastScan);

        tvScanMessage = findViewById(R.id.tvScanMessage);

        tvScanDetails = findViewById(R.id.tvScanDetails);

        layoutWelcome = findViewById(R.id.layoutWelcome);

        tvWelcome = findViewById(R.id.tvWelcome);

        tvWelcomeSubtitle1 = findViewById(R.id.tvWelcomeSubtitle1);
        tvWelcomeSubtitle2 = findViewById(R.id.tvWelcomeSubtitle2);

        loadingOverlay = findViewById(R.id.loadingOverlay);

        layoutScanResult = findViewById(R.id.layoutScanResult);

        // ====================================================
        // Firebase
        // ====================================================

        db = FirebaseFirestore.getInstance();

        FirebaseAuth.getInstance()
                .signInAnonymously()
                .addOnCompleteListener(task -> {

                    if (task.isSuccessful()) {

                        Log.d(
                                "FirebaseAuth",
                                "Autenticación anónima correcta"
                        );

                    } else {

                        Log.e(
                                "FirebaseAuth",
                                "Error de autenticación",
                                task.getException()
                        );

                        Toast.makeText(
                                MainActivity.this,
                                "Error de autenticación con Firebase",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });


        // ====================================================
        // Identificador del lector
        // ====================================================

        readerId =
                Build.MANUFACTURER
                        + " "
                        + Build.MODEL;


        // ====================================================
        // NFC
        // ====================================================

        nfcAdapter =
                NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {

            Toast.makeText(
                    this,
                    "NFC no soportado",
                    Toast.LENGTH_LONG
            ).show();
        }


        // ====================================================
        // Estado inicial
        // ====================================================
        showWelcomeScreen();
    }


    // ========================================================
    // PANTALLA INICIAL
    // ========================================================
    private void showWelcomeScreen() {
        layoutWelcome.setVisibility(View.VISIBLE);
        layoutScanResult.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        rootLayout.setBackgroundResource(R.drawable.bg_initial);
    }


    // ========================================================
    // NFC
    // ========================================================

    @Override
    protected void onNewIntent(Intent intent) {

        super.onNewIntent(intent);

        if (intent == null) {
            return;
        }

        String action =
                intent.getAction();

        if (
                NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                        ||
                        NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                        ||
                        NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
        ) {

            Tag tag =
                    intent.getParcelableExtra(
                            NfcAdapter.EXTRA_TAG
                    );

            if (tag != null) {

                byte[] id =
                        tag.getId();

                StringBuilder sb =
                        new StringBuilder();

                for (byte b : id) {

                    sb.append(
                            String.format(
                                    "%02X",
                                    b
                            )
                    );
                }

                String uid =
                        sb.toString();


                // Mostrar "Leyendo chip..."
                loadingOverlay.setVisibility(
                        View.VISIBLE
                );

                processScan(uid);
            }
        }
    }


    // ========================================================
    // PROCESAR LECTURA
    // ========================================================

    private void processScan(final String uid) {

        long now =
                System.currentTimeMillis();


        // ----------------------------------------------------
        // Evitar lecturas duplicadas durante 3 segundos
        // ----------------------------------------------------

        if (
                uid.equals(lastUid)
                        &&
                        (now - lastScanTime)
                                < SCAN_COOLDOWN_MS
        ) {

            loadingOverlay.setVisibility(
                    View.GONE
            );

            return;
        }


        lastUid = uid;
        lastScanTime = now;


        // ----------------------------------------------------
        // Evitar procesar dos lecturas simultáneamente
        // ----------------------------------------------------

        if (isProcessing) {
            return;
        }

        isProcessing = true;


        // ----------------------------------------------------
        // Buscar pulsera en Firestore
        // ----------------------------------------------------

        final DocumentReference docRef =
                db.collection("scans")
                        .document(uid);


        docRef.get()
                .addOnSuccessListener(
                        documentSnapshot -> {


                            // =================================================
                            // PULSERA NUEVA
                            // =================================================

                            if (!documentSnapshot.exists()) {

                                // Quitamos el overlay antes del diálogo
                                loadingOverlay.setVisibility(
                                        View.GONE
                                );

                                promptForName(uid);

                                return;
                            }


                            // =================================================
                            // PULSERA EXISTENTE
                            // =================================================

                            Long count =
                                    documentSnapshot.getLong(
                                            "count"
                                    );

                            String name =
                                    documentSnapshot.getString(
                                            "name"
                                    );


                            long newCount =
                                    (count != null)
                                            ? count + 1
                                            : 1;


                            // Si por algún motivo no tiene nombre
                            if (
                                    name == null
                                            ||
                                            name.trim().isEmpty()
                            ) {

                                name =
                                        generateRandomName();
                            }


                            Map<String, Object> data =
                                    new HashMap<>();


                            data.put(
                                    "uid",
                                    uid
                            );

                            data.put(
                                    "name",
                                    name
                            );

                            data.put(
                                    "count",
                                    newCount
                            );

                            data.put(
                                    "lastScan",
                                    System.currentTimeMillis()
                            );

                            data.put(
                                    "readerId",
                                    readerId
                            );


                            // Guardar
                            String finalName = name;
                            docRef.set(data)
                                    .addOnSuccessListener(
                                            unused -> {

                                                showEventMessage(
                                                        finalName,
                                                        uid,
                                                        newCount
                                                );

                                                isProcessing =
                                                        false;
                                            }
                                    )
                                    .addOnFailureListener(
                                            e -> {

                                                showFirebaseError();

                                                isProcessing =
                                                        false;
                                            }
                                    );
                        }
                )
                .addOnFailureListener(
                        e -> {

                            showFirebaseError();

                            isProcessing =
                                    false;
                        }
                );
    }


    // ========================================================
    // NUEVA PULSERA - PEDIR NOMBRE
    // ========================================================

    private void promptForName(String uid) {

        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(
                        this
                );


        builder.setTitle(
                "Nuevo chip detectado"
        );


        final android.widget.EditText input =
                new android.widget.EditText(this);

        input.setHint(
                "Nombre (opcional)"
        );


        builder.setView(input);


        // ----------------------------------------------------
        // GUARDAR
        // ----------------------------------------------------

        builder.setPositiveButton(
                "Guardar",
                (dialog, which) -> {

                    String name =
                            input.getText()
                                    .toString()
                                    .trim();


                    // Si no introduce nombre,
                    // generamos uno aleatorio
                    if (name.isEmpty()) {

                        name =
                                generateRandomName();
                    }


                    // Nueva pulsera comienza con 0
                    long count = 0;


                    Map<String, Object> data =
                            new HashMap<>();


                    data.put(
                            "uid",
                            uid
                    );

                    data.put(
                            "name",
                            name
                    );

                    data.put(
                            "count",
                            count
                    );

                    data.put(
                            "lastScan",
                            System.currentTimeMillis()
                    );

                    data.put(
                            "readerId",
                            readerId
                    );


                    final String finalName =
                            name;


                    db.collection("scans")
                            .document(uid)
                            .set(data)
                            .addOnSuccessListener(
                                    unused -> {

                                        showEventMessage(
                                                finalName,
                                                uid,
                                                count
                                        );

                                        isProcessing =
                                                false;
                                    }
                            )
                            .addOnFailureListener(
                                    e -> {

                                        showFirebaseError();

                                        isProcessing =
                                                false;
                                    }
                            );
                }
        );


        // ----------------------------------------------------
        // CANCELAR
        // ----------------------------------------------------

        builder.setNegativeButton(
                "Cancelar",
                (dialog, which) -> {

                    loadingOverlay.setVisibility(
                            View.GONE
                    );

                    isProcessing =
                            false;

                    dialog.cancel();
                }
        );


        builder.show();
    }


    // ========================================================
    // MOSTRAR RESULTADO
    // ========================================================

    private void showEventMessage(String name, String uid, long count) {
        layoutWelcome.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);

        // Mostrar contenedor con el Chibi y resultado
        layoutScanResult.setVisibility(View.VISIBLE);

        // Texto verde neón brillante (#00FF88)
        tvScanMessage.setText("✔ OK!\nConteo: " + count);
        tvScanMessage.setTextColor(Color.parseColor("#00FF88"));

        // Texto blanco brillante (#FFFFFF) sobre tarjeta oscura
        tvScanDetails.setText(name + "\n\nUID: " + uid);
        tvScanDetails.setTextColor(Color.WHITE);

        //tvLastScan.setText("Último conteo: " + count);

        // Regresar a la pantalla de bienvenida tras 3 segundos
        handler.postDelayed(this::showWelcomeScreen, 3000);
    }


    // ========================================================
    // ERROR FIREBASE
    // ========================================================

    private void showFirebaseError() {

        loadingOverlay.setVisibility(
                View.GONE
        );

        Toast.makeText(
                MainActivity.this,
                "Error al comunicarse con Firebase",
                Toast.LENGTH_LONG
        ).show();
    }


    // ========================================================
    // NOMBRE ALEATORIO
    // ========================================================

    private String generateRandomName() {

        String[] names = {

                "Trago Loco",
                "Chupito Express",
                "Capitan Mojito",
                "El Destilado",
                "Licor Loco",
                "Burbujitas",
                "Sorbitos",
                "Licoretas",
                "Ronrron",
                "Copita rebelde"

        };


        Random rnd =
                new Random();


        return names[
                rnd.nextInt(
                        names.length
                )
                ];
    }


    // ========================================================
    // NFC - RESUME
    // ========================================================

    @Override
    protected void onResume() {

        super.onResume();


        if (nfcAdapter != null) {

            PendingIntent pendingIntent;


            if (
                    Build.VERSION.SDK_INT
                            >= Build.VERSION_CODES.S
            ) {

                pendingIntent =
                        PendingIntent.getActivity(
                                this,
                                0,
                                new Intent(
                                        this,
                                        getClass()
                                ).addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                ),
                                PendingIntent.FLAG_MUTABLE
                        );

            } else {

                pendingIntent =
                        PendingIntent.getActivity(
                                this,
                                0,
                                new Intent(
                                        this,
                                        getClass()
                                ).addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                ),
                                0
                        );
            }


            IntentFilter[] filters =
                    new IntentFilter[]{};


            String[][] techList =
                    new String[][]{};


            nfcAdapter.enableForegroundDispatch(
                    this,
                    pendingIntent,
                    filters,
                    techList
            );
        }
    }


    // ========================================================
    // NFC - PAUSE
    // ========================================================

    @Override
    protected void onPause() {

        super.onPause();


        if (nfcAdapter != null) {

            nfcAdapter.disableForegroundDispatch(
                    this
            );
        }
    }
}
