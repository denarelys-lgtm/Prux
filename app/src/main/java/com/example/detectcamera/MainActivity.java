package com.example.detectcamera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;

    private EditText etUsername;
    private EditText etPassword;
    private TextView tvIpAddress;
    private Button btnStartServer;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener = this::onRequestPermissionResultShizuku;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        tvIpAddress = findViewById(R.id.tvIpAddress);
        btnStartServer = findViewById(R.id.btnStartServer);

        tvIpAddress.setText("IP: http://" + obtenerIpLocal() + ":8080");
        btnStartServer.setOnClickListener(v -> gestionarPermisosYArrancar());

        // Registrar listener para escuchar la respuesta de autorización de Shizuku
        Shizuku.addRequestPermissionResultListener(shizukuListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuListener);
    }

    private String obtenerIpLocal() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        return "Desconocida";
    }

    private void gestionarPermisosYArrancar() {
        if (faltanPermisosRuntime()) {
            solicitarPermisosEstandar();
            return;
        }

        // Manejo automático de extracción e inicio del Daemon con Shizuku
        if (ShizukuManager.estaDisponible()) {
            if (!ShizukuManager.tienePermisos()) {
                ShizukuManager.solicitarPermiso();
                Toast.makeText(this, "Acepta el permiso de Shizuku en la pantalla", Toast.LENGTH_SHORT).show();
                return;
            } else {
                // Copia el .jar de assets y lo ejecuta vía Shizuku/ADB
                ShizukuManager.iniciarDaemonAuto(this);
            }
        } else {
            Toast.makeText(this, "Shizuku no está activo. Se intentará conectar al Daemon si ya fue ejecutado previamente.", Toast.LENGTH_LONG).show();
        }

        iniciarServidorService();
    }

    private void onRequestPermissionResultShizuku(int requestCode, int grantResult) {
        if (requestCode == ShizukuManager.SHIZUKU_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuManager.iniciarDaemonAuto(this);
                iniciarServidorService();
            } else {
                Toast.makeText(this, "Permiso de Shizuku denegado.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean faltanPermisosRuntime() {
        boolean camara = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        return camara || audio;
    }

    private void solicitarPermisosEstandar() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                },
                REQUEST_CODE_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (faltanPermisosRuntime()) {
                Toast.makeText(this, "Debes conceder los permisos de cámara y audio.", Toast.LENGTH_LONG).show();
                return;
            }
            gestionarPermisosYArrancar();
        }
    }

    private void iniciarServidorService() {
        Intent serviceIntent = new Intent(this, CameraService.class);
        serviceIntent.putExtra("USER_PARAM", etUsername.getText().toString());
        serviceIntent.putExtra("PASS_PARAM", etPassword.getText().toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Servidor Iniciado", Toast.LENGTH_SHORT).show();
    }
}
