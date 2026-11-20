package com.winlator.l2lethal;

import android.app.Activity;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.core.Callback;
import com.winlator.core.PreloaderDialog;

import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.util.ArrayDeque;

/**
 * L2 Lethal: mientras se ve la pantalla de arranque ("Starting up..."), muestra que esta pasando:
 *  - Iniciando Windows (tiempo transcurrido)
 *  - Instalando .NET con barra REAL: MB ya instalados en C:\windows\mono contra el total de Wine Mono 10.1
 *  - Abriendo L2 Lethal
 * Si nada avanza en mucho tiempo, avisa que cierre y abra la app.
 */
public final class StartupMonitor {
    /** Suma de los 2920 archivos de wine-mono-10.1.0-x86.msi (tabla File del MSI). */
    private static final long MONO_TOTAL_BYTES = 236092765L;
    private static final long STUCK_NO_PROGRESS_MS = 4 * 60 * 1000L;
    private static final long STUCK_BOOT_MS = 10 * 60 * 1000L;

    private static final long SHOW_LOG_BUTTON_MS = 3 * 60 * 1000L;
    private static final long MAX_LOG_BYTES = 3L * 1024 * 1024;
    public static final String LOG_FILE = "l2lethal_registro.txt";

    private static OutputStream logOut;
    private static long logBytes;

    private StartupMonitor() {}

    /** Registro de lo que dice Wine/Box64 en este arranque (se reemplaza en cada arranque, maximo 3 MB). */
    public static synchronized Callback<String> openLog(Activity activity) {
        try {
            if (logOut != null) logOut.close();
            logOut = new FileOutputStream(new File(activity.getFilesDir(), LOG_FILE), false);
            logBytes = 0;
            log("L2 Lethal " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " Android " + android.os.Build.VERSION.RELEASE + " " + android.os.Build.HARDWARE);
        }
        catch (Exception e) { logOut = null; }
        return StartupMonitor::log;
    }

    public static synchronized void log(String line) {
        if (logOut == null || logBytes > MAX_LOG_BYTES) return;
        try {
            byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
            logOut.write(b);
            logOut.flush();
            logBytes += b.length;
        }
        catch (Exception e) {}
    }

    private static void shareLog(Activity activity) {
        try {
            File f = new File(activity.getFilesDir(), LOG_FILE);
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Registro L2 Lethal");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.l2lethal_send_log)));
        }
        catch (Exception e) {}
    }

    public static void start(final Activity activity, final PreloaderDialog dialog, final Container container) {
        final File monoDir = new File(container.getRootDir(), ".wine/drive_c/windows/mono");
        final boolean monoAtStart = new File(monoDir, "mono-2.0").isDirectory() && dirSize(monoDir) >= MONO_TOTAL_BYTES * 98 / 100;

        dialog.setL2StyleOnUiThread();
        Thread t = new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {tiempo, MB instalados} de los ultimos 30 s
            long lastSize = -1, lastChange = t0, lastLog = 0;
            boolean buttonShown = false;
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }

            while (dialog.isShowing() && !activity.isFinishing()) {
                long now = System.currentTimeMillis();
                long elapsed = now - t0;
                String time = formatTime(elapsed);
                String title, detail;
                int percent = -1;

                if (monoAtStart) {
                    title = activity.getString(R.string.l2lethal_opening_quick);
                    detail = time;
                    percent = -2;
                }
                else {
                    long size = dirSize(monoDir);
                    if (size != lastSize) { lastSize = size; lastChange = now; }
                    if (size <= 0) {
                        percent = -2;
                        title = activity.getString(R.string.l2lethal_boot_title);
                        detail = activity.getString(R.string.l2lethal_boot_detail, time);
                        if (elapsed > STUCK_BOOT_MS) detail += "\n" + activity.getString(R.string.l2lethal_stuck);
                    }
                    else if (size < MONO_TOTAL_BYTES * 98 / 100) {
                        percent = (int) Math.min(99, size * 100 / MONO_TOTAL_BYTES);
                        title = activity.getString(R.string.l2lethal_net_title, percent);
                        samples.addLast(new long[]{now, size});
                        while (samples.size() > 2 && now - samples.peekFirst()[0] > 30000) samples.removeFirst();
                        String eta = activity.getString(R.string.l2lethal_eta_calc);
                        long[] first = samples.peekFirst();
                        if (now - first[0] >= 8000 && size > first[1]) {
                            double bytesPerMs = (double) (size - first[1]) / (now - first[0]);
                            long leftMs = (long) ((MONO_TOTAL_BYTES - size) / bytesPerMs);
                            int min = (int) Math.ceil(leftMs / 60000.0);
                            eta = leftMs < 60000 ? activity.getString(R.string.l2lethal_eta_less) : activity.getString(R.string.l2lethal_eta_min, min);
                        }
                        detail = activity.getString(R.string.l2lethal_net_detail,
                            (int) (size / 1000000), (int) (MONO_TOTAL_BYTES / 1000000), eta, time);
                        if (now - lastChange > STUCK_NO_PROGRESS_MS) detail += "\n" + activity.getString(R.string.l2lethal_stuck);
                    }
                    else {
                        percent = -2;
                        title = activity.getString(R.string.l2lethal_opening);
                        detail = time;
                    }
                }

                dialog.setStatusOnUiThread(title, percent, detail);
                if (now - lastLog >= 15000) { lastLog = now; log("[L2] " + time + " .NET=" + (lastSize < 0 ? "ok" : (lastSize / 1000000) + "MB") + " | " + title); }
                if (!buttonShown && !monoAtStart && elapsed > SHOW_LOG_BUTTON_MS) {
                    buttonShown = true;
                    dialog.setActionOnUiThread(activity.getString(R.string.l2lethal_send_log), () -> shareLog(activity));
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "L2LethalStartupMonitor");
        t.setDaemon(true);
        t.start();
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static long dirSize(File dir) {
        if (!dir.isDirectory()) return 0;
        long total = 0;
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(dir);
        while (!stack.isEmpty()) {
            File[] list = stack.pop().listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.isDirectory()) stack.push(f);
                else total += f.length();
            }
        }
        return total;
    }
}
