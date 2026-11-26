package com.winlator.l2lethal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L2 Lethal: al abrir la app revisa el ultimo Release de GitHub. Si trae un versionCode mayor
 * (el workflow escribe "versionCode=NN" en las notas), avisa, baja el APK y abre el instalador de Android.
 */
public final class UpdateChecker {
    private static final String LATEST_URL = "https://api.github.com/repos/l2lethal45/winlator-app/releases/latest";
    private static final String APK_NAME = "L2Lethal.apk";
    private static final Pattern VERSION_CODE = Pattern.compile("versionCode=(\\d+)");
    private static boolean checked = false;

    private UpdateChecker() {}

    public static void checkOnStart(final Activity activity) {
        if (checked) return;
        checked = true;
        final File apk = new File(new File(activity.getFilesDir(), "update"), APK_NAME);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int current = AppUtils.getVersionCode(activity);
                JSONObject release = new JSONObject(httpGet(LATEST_URL));
                Matcher m = VERSION_CODE.matcher(release.optString("body", ""));
                if (!m.find()) return;
                int remote = Integer.parseInt(m.group(1));
                if (remote <= current) {
                    if (apk.exists()) apk.delete(); // ya actualizado: borrar el APK bajado
                    return;
                }
                String url = null;
                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (APK_NAME.equals(a.optString("name"))) { url = a.optString("browser_download_url"); break; }
                }
                if (url == null || url.isEmpty()) return;
                final String apkUrl = url;
                activity.runOnUiThread(() -> showDialog(activity, apkUrl, apk));
            }
            catch (Exception e) {
                // sin internet o GitHub no responde: se revisa la proxima vez
            }
        });
    }

    private static void showDialog(final Activity activity, final String url, final File apk) {
        if (activity.isFinishing()) return;
        L2LethalSetup.autoStartPending = false; // que no abra el juego encima del aviso
        new AlertDialog.Builder(activity)
            .setTitle(R.string.l2lethal_update_title)
            .setMessage(R.string.l2lethal_update_message)
            .setCancelable(false)
            .setPositiveButton(R.string.l2lethal_update_now, (d, w) -> download(activity, url, apk))
            .setNegativeButton(R.string.l2lethal_update_later, null)
            .show();
    }

    private static void download(final Activity activity, final String url, final File apk) {
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.l2lethal_update_downloading);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = false;
            File tmp = new File(apk.getPath() + ".part");
            try {
                apk.getParentFile().mkdirs();
                HttpURLConnection conn = open(url);
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
                ok = (total <= 0 || done == total) && (!apk.exists() || apk.delete()) && tmp.renameTo(apk);
            }
            catch (Exception e) {
                ok = false;
            }
            if (!ok) tmp.delete();
            final boolean success = ok;
            activity.runOnUiThread(() -> {
                dialog.close();
                if (success) install(activity, apk);
                else AppUtils.showToast(activity, R.string.l2lethal_update_error);
            });
        });
    }

    private static void install(Activity activity, File apk) {
        try {
            // si es la primera vez, el instalador de Android pide permiso de "fuentes desconocidas" y luego sigue solo
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        }
        catch (Exception e) {
            AppUtils.showToast(activity, R.string.l2lethal_update_error);
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 5; i++) { // GitHub redirige a otro dominio (objects.githubusercontent.com)
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "L2Lethal-Android");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                url = conn.getHeaderField("Location");
                conn.disconnect();
                continue;
            }
            if (code != 200) { conn.disconnect(); throw new Exception("HTTP " + code); }
            return conn;
        }
        throw new Exception("demasiadas redirecciones");
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
        finally {
            conn.disconnect();
        }
    }
}
