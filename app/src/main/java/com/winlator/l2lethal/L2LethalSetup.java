package com.winlator.l2lethal;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUHelper;
import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * L2 Lethal: deja todo listo sin que el jugador toque nada.
 *  1. Crea el contenedor "L2 Lethal" (1920x1080; Turnip+Zink si el celular tiene GPU Adreno).
 *  2. Copia el launcher a C:\L2 Lethal\ y crea el acceso "L2 Lethal" (con el perfil de controles "Lineage 2").
 *  3. Baja Wine Mono (.NET) para que el launcher funcione; Wine lo instala solo la primera vez.
 *  4. Abre el launcher directo (al abrir la app desde el icono).
 */
public final class L2LethalSetup {
    public static final String CONTAINER_NAME = "L2 Lethal";
    public static final String SHORTCUT_NAME = "L2 Lethal";
    public static final String GAME_DIR = "L2 Lethal";
    public static final String LAUNCHER_EXE = "L2 Lethal Launcher.exe";
    public static final int CONTROLS_PROFILE_ID = 10; // assets/inputcontrols/profiles/controls-10.icp ("Lineage 2")
    private static final String SCREEN_SIZE = "1920x1080";

    private static final String MONO_FILE = "wine-mono-10.1.0-x86.msi";
    private static final String MONO_SHA256 = "c88c2431890bc32cacec8d7ea70e53a5ae4b95c8c55ca6e75ef8db0e4ccf1070";
    private static final String[] MONO_URLS = {
        "https://dl.winehq.org/wine/wine-mono/10.1.0/wine-mono-10.1.0-x86.msi",
        "https://github.com/brunodev85/winlator/raw/main/wine_addons/wine-mono-10.1.0-x86.msi"
    };

    /** true solo cuando el jugador abrio la app desde el icono (no al volver del juego). */
    public static boolean autoStartPending = false;
    private static boolean running = false;

    private L2LethalSetup() {}

    /** Se llama en el hilo principal cuando los archivos de sistema ya estan instalados. */
    public static void run(final MainActivity activity) {
        if (running) return;
        RootFS rootFS = RootFS.find(activity);
        if (!rootFS.isValid()) return;
        running = true;

        final ContainerManager manager = new ContainerManager(activity);
        Container found = null;
        for (Container c : manager.getContainers()) {
            if (CONTAINER_NAME.equals(c.getName())) { found = c; break; }
        }

        if (found != null) {
            prepare(activity, found);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", SCREEN_SIZE);
            if (isAdreno(activity)) data.put("graphicsDriver", "turnip,zink");
            final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
            dialog.show(R.string.l2lethal_preparing);
            manager.createContainerAsync(data, (container) -> {
                dialog.close();
                if (container == null) {
                    running = false;
                    AppUtils.showToast(activity, R.string.l2lethal_setup_error);
                    return;
                }
                prepare(activity, container);
            });
        }
        catch (Exception e) {
            running = false;
            AppUtils.showToast(activity, R.string.l2lethal_setup_error);
        }
    }

    private static boolean isAdreno(Context context) {
        try { return GPUHelper.getAdrenoModelId(context) > 0; }
        catch (Throwable e) { return false; }
    }

    private static void prepare(final MainActivity activity, final Container container) {
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        final File monoDir = new File(RootFS.find(activity).getRootDir(), "opt/wine/share/wine/mono");
        final File monoMsi = new File(monoDir, MONO_FILE);
        final File driveC = new File(container.getRootDir(), ".wine/drive_c");
        final boolean needMono = !new File(driveC, "windows/mono/mono-2.0").isDirectory() && !isValidMono(monoMsi);
        if (needMono) dialog.show(R.string.l2lethal_downloading_net);

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = true;
            try {
                // 1) launcher (no se pisa si ya existe: se actualiza solo)
                File gameDir = new File(driveC, GAME_DIR);
                File launcher = new File(gameDir, LAUNCHER_EXE);
                if (!launcher.isFile()) {
                    gameDir.mkdirs();
                    FileUtils.copy(activity, "l2lethal/" + LAUNCHER_EXE, launcher);
                }

                // 2) acceso directo "L2 Lethal" -> launcher, con los controles de Lineage 2
                File desktop = new File(container.getUserDir(), "Desktop");
                desktop.mkdirs();
                File shortcut = new File(desktop, SHORTCUT_NAME + ".desktop");
                if (!shortcut.isFile()) {
                    String iconName = saveIcon(activity, container) ? "l2lethal" : null;
                    String exe = "C:\\" + GAME_DIR + "\\" + LAUNCHER_EXE;
                    String esc = exe.replace("\\", "\\\\\\\\").replace(" ", "\\\\ ");
                    String content = "[Desktop Entry]\n" +
                        "Name=" + SHORTCUT_NAME + "\n" +
                        "Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine " + esc + "\n" +
                        "Type=Application\n" +
                        "StartupNotify=true\n" +
                        "Path=/home/xuser/.wine/dosdevices/c:/" + GAME_DIR + "\n" +
                        (iconName != null ? "Icon=" + iconName + "\n" : "") +
                        "StartupWMClass=" + LAUNCHER_EXE.toLowerCase() + "\n" +
                        "\n[Extra Data]\n" +
                        "controlsProfile=" + CONTROLS_PROFILE_ID + "\n";
                    FileUtils.writeString(shortcut, content);
                }

                // 3) Wine Mono (.NET): se deja donde Wine lo busca; Wine lo instala solo al abrir el launcher
                if (needMono) {
                    monoDir.mkdirs();
                    ok = downloadMono(activity, dialog, monoMsi);
                }
            }
            catch (Exception e) {
                ok = false;
            }

            final boolean success = ok;
            activity.runOnUiThread(() -> {
                dialog.close();
                running = false;
                if (!success) {
                    AppUtils.showToast(activity, R.string.l2lethal_net_error);
                    return;
                }
                if (autoStartPending) {
                    autoStartPending = false;
                    startGame(activity, container);
                }
            });
        });
    }

    public static void startGame(MainActivity activity, Container container) {
        File shortcut = new File(new File(container.getUserDir(), "Desktop"), SHORTCUT_NAME + ".desktop");
        if (!shortcut.isFile()) return;
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcut.getPath());
        activity.startActivity(intent);
    }

    private static boolean saveIcon(Context context, Container container) {
        try {
            Drawable d = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round);
            if (d == null) return false;
            for (int size : new int[]{64, 48, 32}) {
                File dir = container.getIconsDir(size);
                dir.mkdirs();
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(canvas);
                try (OutputStream out = new FileOutputStream(new File(dir, "l2lethal.png"))) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    private static boolean isValidMono(File file) {
        return file.isFile() && MONO_SHA256.equalsIgnoreCase(sha256(file));
    }

    private static boolean downloadMono(MainActivity activity, DownloadProgressDialog dialog, File target) {
        File tmp = new File(target.getPath() + ".part");
        for (String url : MONO_URLS) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) { // redireccion a otro dominio (GitHub)
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(loc).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(30000);
                    code = conn.getResponseCode();
                }
                if (code != 200) { conn.disconnect(); continue; }
                long total = conn.getContentLengthLong();
                long done = 0;
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int p = (int) (done * 100 / total);
                            if (p != last) { last = p; activity.runOnUiThread(() -> dialog.setProgress(p)); }
                        }
                    }
                }
                conn.disconnect();
                if (MONO_SHA256.equalsIgnoreCase(sha256(tmp))) {
                    if (target.exists()) target.delete();
                    return tmp.renameTo(target);
                }
            }
            catch (Exception e) {
                // probar con el siguiente servidor
            }
            tmp.delete();
        }
        return false;
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        }
        catch (Exception e) {
            return "";
        }
    }
}
