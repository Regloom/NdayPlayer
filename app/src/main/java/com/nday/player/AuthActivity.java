package com.nday.player;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.util.Collections;

/**
 * TV pairing, as in the original AuthorizationScreen (module 664): POST getTVCod returns a code,
 * the user enters it on nday.club ("Привязать ТВ") or scans the QR, and getTVCodCheck polled
 * every 5 s returns the user token.
 */
public class AuthActivity extends Activity {
    private static final long POLL_MS = 5000;
    private static final long CODE_LIFETIME_MS = 5 * 60 * 1000;

    private final Handler handler = new Handler();
    private Session session;
    private TextView codeView, timerView;
    private ImageView qrView;
    private String code;
    /** SystemClock.elapsedRealtime() when the current code expires. */
    private long codeExpiresAt;
    private int requestSeq;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (code == null) return;
            if (SystemClock.elapsedRealtime() >= codeExpiresAt) {
                requestCode();
                return;
            }
            updateTimer();
            final int seq = requestSeq;
            Api.get("/api/getTVCodCheck?code=" + code, Api.TV_CODE_TOKEN, (result, error) -> {
                if (seq != requestSeq) return;
                if (result instanceof String && !((String) result).isEmpty() && !"not found".equals(result)) {
                    session.setToken((String) result);
                    Toast.makeText(AuthActivity.this, R.string.signed_in, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                handler.postDelayed(poll, POLL_MS);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        session = new Session(this);
        codeView = findViewById(R.id.auth_code);
        timerView = findViewById(R.id.auth_timer);
        qrView = findViewById(R.id.auth_qr);
        // On a phone the code is entered on the same device, so offer to open the site directly.
        Button openSite = findViewById(R.id.btn_open_site);
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            openSite.setVisibility(View.VISIBLE);
            openSite.setOnClickListener(v -> {
                String url = code != null ? activateUrl() : Api.BASE + "/profile";
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(this, url, Toast.LENGTH_LONG).show();
                }
            });
        }
        Button refresh = findViewById(R.id.btn_refresh);
        refresh.setOnClickListener(v -> requestCode());
        refresh.requestFocus();
        if (savedInstanceState != null) {
            code = savedInstanceState.getString("code");
            codeExpiresAt = savedInstanceState.getLong("expires");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("code", code);
        out.putLong("expires", codeExpiresAt);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Returning from the browser (phone) or a rotation keeps the code the user may have entered.
        if (code != null && SystemClock.elapsedRealtime() < codeExpiresAt) {
            showCode();
            handler.post(poll);
        } else {
            requestCode();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        requestSeq++;
        handler.removeCallbacks(poll);
    }

    private void requestCode() {
        handler.removeCallbacks(poll);
        final int seq = ++requestSeq;
        code = null;
        codeView.setText("····");
        timerView.setText("");
        JSONObject body = new JSONObject();
        try {
            body.put("device_id", session.deviceId());
        } catch (Exception ignored) {
        }
        Api.post("/api/getTVCod", Api.TV_CODE_TOKEN, body, (result, error) -> {
            if (seq != requestSeq) return;
            if (result instanceof String && !"not found".equals(result)) {
                code = (String) result;
            } else if (result != null && !(result instanceof JSONObject)) {
                code = String.valueOf(result);
            }
            if (code == null) {
                codeView.setText("----");
                timerView.setText(getString(R.string.error_connect)
                        + (error == null ? "" : "\n" + error.getMessage()));
                return;
            }
            codeExpiresAt = SystemClock.elapsedRealtime() + CODE_LIFETIME_MS;
            showCode();
            handler.postDelayed(poll, POLL_MS);
        });
    }

    private void showCode() {
        codeView.setText(code);
        qrView.setImageBitmap(qr(activateUrl(), 360));
        updateTimer();
    }

    private String activateUrl() {
        return Api.BASE + "/profile/activate_tv?code=" + code;
    }

    private void updateTimer() {
        long left = Math.max(0, codeExpiresAt - SystemClock.elapsedRealtime());
        timerView.setText(getString(R.string.code_timer, Video.formatTime(left)));
    }

    static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }
}
