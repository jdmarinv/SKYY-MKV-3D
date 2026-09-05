package com.iqh3d.geoexplorer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Aprovisionador automático de la lista blanca (whitelist) de 3DFV para la tableta IQH3D SKYY.
 *
 * Permite que al abrir la aplicación, el reproductor verifique si está registrado en
 * /sdcard/K3DX/config/.white_list2.config (o white_list2.config) y se auto-registre
 * sin requerir conexión por cable ADB a una computadora.
 */
public final class ThreeDfvProvisioner {
    private static final String TAG = "ThreeDfvProvisioner";

    public static final String TARGET_ENTRY = "30@com.iqh3d.geoexplorer.MainActivity";
    public static final String SERVICE_PACKAGE = "com.wztech.service3d";
    public static final String SERVICE_CLASS = "com.wztech.service3d.Service3D";
    public static final String SERVICE_ACTION = "com.wztech.service";

    private ThreeDfvProvisioner() {}

    public static void autoProvisionAsync(final Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                boolean provisioned = checkAndProvision(appContext);
                if (provisioned) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(appContext, "3DFV registrado automáticamente para SKYY MKV 3D", Toast.LENGTH_LONG).show()
                    );
                }
            } catch (Exception e) {
                Log.e(TAG, "Error durante auto-aprovisionamiento 3DFV", e);
            }
        }, "ThreeDfvProvisionerThread").start();
    }

    public static synchronized boolean checkAndProvision(Context context) {
        File configDir = findConfigDirectory();
        if (configDir == null || !configDir.exists() || !configDir.isDirectory()) {
            Log.d(TAG, "Directorio K3DX/config no detectado (no es tableta SKYY o firmware 3DFV no presente)");
            return false;
        }

        File activeConfigFile = resolveActiveConfigFile(configDir);
        if (activeConfigFile == null || !activeConfigFile.exists()) {
            Log.w(TAG, "No se encontró ningún archivo white_list2.config válido en " + configDir.getAbsolutePath());
            return false;
        }

        // Verificar si la entrada ya existe
        if (containsEntry(activeConfigFile, TARGET_ENTRY)) {
            Log.i(TAG, "La entrada 3DFV ya está presente en " + activeConfigFile.getName());
            return false;
        }

        Log.i(TAG, "Entrada 3DFV no encontrada. Auto-registrando en " + activeConfigFile.getAbsolutePath());

        // 1. Crear copia de seguridad preventiva
        createBackup(activeConfigFile);

        // 2. Anexar la entrada 30@com.iqh3d.geoexplorer.MainActivity
        boolean appended = appendEntry(activeConfigFile, TARGET_ENTRY);
        if (!appended) {
            Log.e(TAG, "Fallo al escribir entrada en " + activeConfigFile.getAbsolutePath());
            return false;
        }

        // 3. Notificar / reiniciar servicio 3DFV (com.wztech.service3d)
        reloadService(context);
        return true;
    }

    private static File findConfigDirectory() {
        File sdcard = Environment.getExternalStorageDirectory();
        File dir = new File(sdcard, "K3DX/config");
        if (dir.exists()) return dir;

        File direct = new File("/sdcard/K3DX/config");
        if (direct.exists()) return direct;

        return dir;
    }

    private static File resolveActiveConfigFile(File configDir) {
        File hidden = new File(configDir, ".white_list2.config");
        File visible = new File(configDir, "white_list2.config");

        boolean hiddenExists = hidden.exists();
        boolean visibleExists = visible.exists();

        if (hiddenExists && !visibleExists) return hidden;
        if (visibleExists && !hiddenExists) return visible;

        if (hiddenExists && visibleExists) {
            // Si alguno ya está registrado, usar ese
            if (containsEntry(visible, TARGET_ENTRY)) return visible;
            if (containsEntry(hidden, TARGET_ENTRY)) return hidden;

            // Revisar cuál contiene la entrada de Chrome (patrón de firmware SKYY)
            if (hasChromeEntry(hidden)) return hidden;
            if (hasChromeEntry(visible)) return visible;

            // Por defecto en la tableta física probada, el archivo activo es el oculto
            return hidden;
        }

        return null;
    }

    private static boolean containsEntry(File file, String entry) {
        if (file == null || !file.exists()) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals(entry.trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error leyendo " + file.getName(), e);
        }
        return false;
    }

    private static boolean hasChromeEntry(File file) {
        if (file == null || !file.exists()) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.trim().toLowerCase(Locale.ROOT);
                if (clean.startsWith("30@") && clean.contains("chrome")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void createBackup(File sourceFile) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File backup = new File(sourceFile.getParentFile(), sourceFile.getName() + ".bak." + stamp);

            try (FileInputStream in = new FileInputStream(sourceFile);
                 FileOutputStream out = new FileOutputStream(backup)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
            Log.i(TAG, "Backup de 3DFV creado en: " + backup.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "No se pudo crear backup de " + sourceFile.getName(), e);
        }
    }

    private static boolean appendEntry(File file, String entry) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            // Asegurar salto de línea antes de escribir
            String toWrite = "\r\n" + entry + "\r\n";
            out.write(toWrite.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error anexando entrada a " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private static void reloadService(Context context) {
        try {
            Intent stopIntent = new Intent();
            stopIntent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
            context.stopService(stopIntent);
        } catch (Throwable ignored) {}

        try {
            Intent startIntent = new Intent(SERVICE_ACTION);
            startIntent.setPackage(SERVICE_PACKAGE);
            context.startService(startIntent);
            Log.i(TAG, "Servicio 3DFV reiniciado exitosamente (" + SERVICE_ACTION + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Error iniciando servicio " + SERVICE_PACKAGE, t);
        }
    }
}
