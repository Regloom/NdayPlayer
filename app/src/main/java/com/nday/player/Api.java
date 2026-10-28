package com.nday.player;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * nday.club REST API, as used by the original com.nday app (index.android.bundle, modules 587/663/664/916/1017/1024/1027).
 * All requests carry Origin/Referer of nday.club and "Authorization: Bearer <token>".
 */
public final class Api {
    public static final String BASE = "https://nday.club";
    public static final String STORAGE_MD = BASE + "/storage/md/";
    public static final String STORAGE_ORIG = BASE + "/storage/orig/";

    /** Token used by the original app when no user is signed in (from local.properties, see README). */
    public static final String GUEST_TOKEN = BuildConfig.NDAY_GUEST_TOKEN;
    /** Static bearer token for the TV code pairing endpoints (from local.properties, see README). */
    static final String TV_CODE_TOKEN = BuildConfig.NDAY_TV_CODE_TOKEN;

    /** Headers the original player sent with manifest, segment and license requests. */
    public static final String MEDIA_REFERER = BASE;
    public static final String MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; arm_64; Android 8.0.0; PRA-TL10) AppleWebKit/537.36 (KHTML, lick Gecko) "
                    + "Chrome/108.0.0.0 SA/3 Mobile Safari/537.36";

    public interface Callback {
        /** Called on the main thread; result is a parsed JSON value (JSONObject, JSONArray, String, ...) or null on error. */
        void done(Object result, Exception error);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Api() {}

    public static void get(String path, String token, Callback cb) {
        request("GET", path, token, null, cb);
    }

    public static void post(String path, String token, JSONObject body, Callback cb) {
        request("POST", path, token, body, cb);
    }

    public static void request(final String method, final String path, final String token,
                               final JSONObject body, final Callback cb) {
        POOL.execute(() -> {
            Object result = null;
            Exception error = null;
            try {
                result = execute(method, path.startsWith("http") ? path : BASE + path, token, body);
            } catch (Exception e) {
                error = e;
            }
            if (cb != null) {
                final Object r = result;
                final Exception e = error;
                MAIN.post(() -> cb.done(r, e));
            }
        });
    }

    private static Object execute(String method, String url, String token, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Origin", "nday.club");
            c.setRequestProperty("Referer", BASE);
            c.setRequestProperty("Content-type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                c.setDoOutput(true);
                byte[] bytes = body.toString().getBytes("UTF-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) {
                throw new HttpException(code, text);
            }
            text = text.trim();
            if (text.isEmpty()) {
                return null;
            }
            return new JSONTokener(text).nextValue();
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }

    public static final class HttpException extends Exception {
        public final int code;

        HttpException(int code, String body) {
            super("HTTP " + code + (body == null || body.isEmpty() ? "" : ": " + body));
            this.code = code;
        }
    }
}
