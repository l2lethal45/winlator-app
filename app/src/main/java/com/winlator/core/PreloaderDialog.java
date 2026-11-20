package com.winlator.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.winlator.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public synchronized void show(int textResId) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        dialog.show();
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
    }

    /** L2 Lethal: fondo del server, cuadro oscuro con borde dorado abajo al centro. */
    public void setL2StyleOnUiThread() {
        activity.runOnUiThread(() -> {
            synchronized (PreloaderDialog.this) {
                if (dialog == null) return;
                dialog.findViewById(R.id.L2Background).setVisibility(android.view.View.VISIBLE);
                android.view.View box = dialog.findViewById(R.id.L2Box);
                box.setBackgroundResource(R.drawable.l2lethal_loading_box);
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) box.getLayoutParams();
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = (int) (24 * activity.getResources().getDisplayMetrics().density);
                box.setLayoutParams(lp);
                ((TextView) dialog.findViewById(R.id.TextView)).setTextColor(0xFFE8C46A);
                ((TextView) dialog.findViewById(R.id.L2Detail)).setTextColor(0xFFF2EAD8);
                android.widget.ProgressBar bar = dialog.findViewById(R.id.L2ProgressBar);
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE8C46A));
            }
        });
    }

    /** L2 Lethal: titulo + barra (percent < 0 = sin barra) + detalle. Se puede llamar desde cualquier hilo. */
    public void setStatusOnUiThread(final String title, final int percent, final String detail) {
        activity.runOnUiThread(() -> {
            synchronized (PreloaderDialog.this) {
                if (dialog == null || !dialog.isShowing()) return;
                ((TextView)dialog.findViewById(R.id.TextView)).setText(title);
                android.widget.ProgressBar bar = dialog.findViewById(R.id.L2ProgressBar);
                bar.setVisibility(percent >= 0 || percent == -2 ? android.view.View.VISIBLE : android.view.View.GONE);
                bar.setIndeterminate(percent == -2);
                if (percent >= 0) bar.setProgress(percent);
                TextView tv = dialog.findViewById(R.id.L2Detail);
                tv.setVisibility(detail != null && !detail.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                tv.setText(detail != null ? detail : "");
            }
        });
    }

    /** L2 Lethal: boton opcional (label null = ocultar). */
    public void setActionOnUiThread(final String label, final Runnable action) {
        activity.runOnUiThread(() -> {
            synchronized (PreloaderDialog.this) {
                if (dialog == null || !dialog.isShowing()) return;
                android.widget.Button b = dialog.findViewById(R.id.L2Action);
                if (label == null) { b.setVisibility(android.view.View.GONE); return; }
                b.setText(label);
                b.setOnClickListener((v) -> action.run());
                b.setVisibility(android.view.View.VISIBLE);
            }
        });
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
