package com.nday.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.security.MessageDigest;

/** Persistent app state: auth token, preferred playback speed, device id. */
public final class Session {
    public static final float[] SPEEDS = {1.0f, 1.3f, 1.5f, 1.75f, 2.0f};

    private static final String PREFS = "nday";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_SPEED = "speed";

    private final SharedPreferences prefs;
    private final Context context;

    /** Cached /api/user flags, refreshed by MainActivity. */
    public static boolean fullView;
    public static String userName;
    public static String paidUntil;

    public Session(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSignedIn() {
        String t = prefs.getString(KEY_TOKEN, null);
        return t != null && !t.isEmpty() && !t.equals("000");
    }

    /** The user token, or the guest token like the original getData() fallback. */
    public String token() {
        return isSignedIn() ? prefs.getString(KEY_TOKEN, null) : Api.GUEST_TOKEN;
    }

    public void setToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    public void signOut() {
        prefs.edit().remove(KEY_TOKEN).apply();
        fullView = false;
        userName = null;
        paidUntil = null;
    }

    public float speed() {
        return prefs.getFloat(KEY_SPEED, 1.0f);
    }

    public void setSpeed(float speed) {
        prefs.edit().putFloat(KEY_SPEED, speed).apply();
    }

    public String deviceId() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return Build.BRAND + "-" + Build.MODEL + "__" + md5(androidId == null ? "" : androidId) + "_"
                + BuildConfig.VERSION_NAME;
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
