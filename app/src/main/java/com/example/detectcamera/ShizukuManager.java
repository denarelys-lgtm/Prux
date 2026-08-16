package com.example.detectcamera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import rikka.shizuku.Shizuku;

public class ShizukuManager {

    public static final int SHIZUKU_CODE = 1002;
    private static final String TAG = "ShizukuManager";

    public static boolean estaDisponible() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean tienePermisos() {
        if (!estaDisponible()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static void solicitarPermiso() {
        if (estaDisponible()) {
            Shizuku.requestPermission(SHIZUKU_CODE);
        }
    }

    /**
     * Extrae ScreenDaemon.jar desde los assets de la APK hacia el almacenamiento interno (/sdcard/)
     */
    public static void copiarDaemonDesdeAssets(Context context) {
        File destino = new File("/sdcard/ScreenDaemon.jar");

        try (InputStream input = context.getAssets().open("ScreenDaemon.jar");
             FileOutputStream output = new FileOutputStream(destino)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.flush();
            Log.i(TAG, "ScreenDaemon.jar copiado exitosamente desde assets.");
        } catch (Exception e) {
            Log.e(TAG, "Error extrayendo ScreenDaemon.jar desde assets: " + e.getMessage());
        }
    }

    /**
     * Copia el ejecutable e inicia el Daemon ADB utilizando Shizuku
     */
    public static void iniciarDaemonAuto(Context context) {
        // 1. Extraer siempre el .jar de los assets para asegurar la versión más reciente
        copiarDaemonDesdeAssets(context);

        // 2. Comando ADB ejecutado vía Shizuku
        String cmd = "cp /sdcard/ScreenDaemon.jar /data/local/tmp/ScreenDaemon.jar && " +
                     "app_process -Djava.class.path=/data/local/tmp/ScreenDaemon.jar /data/local/tmp/ ScreenDaemon > /dev/null 2>&1 &";

        try {
            Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            Log.i(TAG, "Comando de inicio enviado a Shizuku.");
        } catch (Exception e) {
            Log.e(TAG, "Error ejecutando comando en Shizuku: " + e.getMessage());
        }
    }
}
