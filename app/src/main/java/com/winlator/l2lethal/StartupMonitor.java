package com.winlator.l2lethal;

import android.app.Activity;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.core.PreloaderDialog;

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

    private StartupMonitor() {}

    public static void start(final Activity activity, final PreloaderDialog dialog, final Container container) {
        final File monoDir = new File(container.getRootDir(), ".wine/drive_c/windows/mono");
        final boolean monoAtStart = new File(monoDir, "mono-2.0").isDirectory() && dirSize(monoDir) >= MONO_TOTAL_BYTES * 98 / 100;

        Thread t = new Thread(() -> {
            final long t0 = System.currentTimeMillis();
            long lastSize = -1, lastChange = t0;
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }

            while (dialog.isShowing() && !activity.isFinishing()) {
                long now = System.currentTimeMillis();
                long elapsed = now - t0;
                String time = formatTime(elapsed);
                String title, detail;
                int percent = -1;

                if (monoAtStart) {
                    title = activity.getString(R.string.l2lethal_opening);
                    detail = time;
                }
                else {
                    long size = dirSize(monoDir);
                    if (size != lastSize) { lastSize = size; lastChange = now; }
                    if (size <= 0) {
                        title = activity.getString(R.string.l2lethal_boot_title);
                        detail = activity.getString(R.string.l2lethal_boot_detail, time);
                        if (elapsed > STUCK_BOOT_MS) detail += "\n" + activity.getString(R.string.l2lethal_stuck);
                    }
                    else if (size < MONO_TOTAL_BYTES * 98 / 100) {
                        percent = (int) Math.min(99, size * 100 / MONO_TOTAL_BYTES);
                        title = activity.getString(R.string.l2lethal_net_title, percent);
                        detail = activity.getString(R.string.l2lethal_net_detail,
                            (int) (size / 1000000), (int) (MONO_TOTAL_BYTES / 1000000), time);
                        if (now - lastChange > STUCK_NO_PROGRESS_MS) detail += "\n" + activity.getString(R.string.l2lethal_stuck);
                    }
                    else {
                        percent = 100;
                        title = activity.getString(R.string.l2lethal_opening);
                        detail = time;
                    }
                }

                dialog.setStatusOnUiThread(title, percent, detail);
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
