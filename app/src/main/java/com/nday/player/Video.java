package com.nday.player;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** One video item, parsed from getVideoList / getCollection / getVideo responses. */
public final class Video implements Serializable {
    public static final String DRM_WIDEVINE = "widevine";
    public static final String DRM_CLEARKEY = "clearkey";
    public static final String DRM_PLAYREADY = "playready";

    private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    public int id;
    public String title = "";
    public String author = "";
    public String date = "";
    /** Raw "yyyy-MM-dd HH:mm:ss" publish date, for sorting. */
    public String dateStart = "";
    public int year;
    public String description = "";
    public String views = "";
    public String likes = "";
    public String pic;
    public String picOrig;
    public String ksPleer;
    public String videoId;
    public String drmType;
    public String drmServer;
    public String url;
    public boolean free;
    public boolean canWatch;
    public boolean isLiked;
    public int currentSec;
    public int progress;

    public static Video fromJson(JSONObject o) {
        Video v = new Video();
        v.id = o.optInt("id");
        v.title = str(o, "name");
        v.description = str(o, "description");
        v.views = o.has("viewsCount") ? str(o, "viewsCount") : str(o, "views");
        v.free = truthy(o.opt("free"));
        v.canWatch = truthy(o.opt("can_watch"));
        v.isLiked = truthy(o.opt("is_liked"));
        v.currentSec = o.optInt("curent_sec");
        v.progress = o.optInt("progress");
        v.dateStart = str(o, "date_start");
        v.date = formatDate(v.dateStart);
        if (v.dateStart.length() >= 4) {
            try {
                v.year = Integer.parseInt(v.dateStart.substring(0, 4));
            } catch (NumberFormatException ignored) {
            }
        }
        String poster = str(o, "ks_poster");
        if (!poster.isEmpty()) {
            v.pic = Api.STORAGE_MD + poster;
            v.picOrig = Api.STORAGE_ORIG + poster;
        }
        v.ksPleer = emptyToNull(str(o, "ks_pleer"));
        v.videoId = emptyToNull(str(o, "video_id"));
        v.drmType = emptyToNull(str(o, "drmtype"));
        v.drmServer = emptyToNull(str(o, "drmserver"));
        v.url = emptyToNull(str(o, "url"));
        JSONArray liked = o.optJSONArray("liked");
        if (liked != null && liked.length() > 0 && liked.optJSONObject(0) != null) {
            v.likes = str(liked.optJSONObject(0), "count");
        }
        JSONArray authors = o.optJSONArray("authors");
        if (authors != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < authors.length() && i < 3; i++) {
                JSONObject a = authors.optJSONObject(i);
                if (a == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(str(a, "name"));
            }
            v.author = sb.toString();
        }
        return v;
    }

    /** True when the server says this account may play the video. */
    public boolean playable(boolean fullView) {
        return canWatch || fullView || free;
    }

    /**
     * DASH manifest URL, derived exactly like the original PlayerScreen (module 916):
     * kinescope links become https://kinescope.io/&lt;id&gt;/master.mpd, other links are rebuilt
     * into the CDN manifest path.
     */
    public String manifestUrl() {
        String p = ksPleer;
        if (p == null) return url;
        if (p.contains("kinescope.io")) {
            return "https://kinescope.io/" + slice(p, 27, p.length()) + "/master.mpd";
        }
        return slice(p, 0, 8) + b64("NTM4ZDZiZWMtM2NkNy00MTEzLThjYjctZWNmMmJkMjAyYWYz")
                + slice(p, 44, 55) + slice(p, 62, 95) + slice(p, 95, 136)
                + b64("b24vbWFuaWZlc3QubXBk");
    }

    /** DRM scheme, as chosen by the original VideoPlayer (module 1017). */
    public String drmScheme() {
        if (drmType != null) return drmType.toLowerCase(Locale.ROOT);
        if (ksPleer == null) return null;
        return ksPleer.contains("kinescope.io") ? DRM_WIDEVINE : DRM_CLEARKEY;
    }

    public String licenseUrl() {
        if (drmServer != null) return drmServer;
        if (ksPleer == null) return null;
        if (ksPleer.contains("kinescope.io")) {
            return videoId == null ? null
                    : "https://license.kinescope.io/v1/vod/" + videoId + "/acquire/widevine?token=";
        }
        return "https://c3ee61a8-3f42-42ff-8870-cf6f483de808.selcdn.net/keys/unclear_key_p.php";
    }

    /** JavaScript String.prototype.slice semantics for non-negative indices. */
    private static String slice(String s, int from, int to) {
        int a = Math.min(from, s.length());
        int b = Math.min(to, s.length());
        return a >= b ? "" : s.substring(a, b);
    }

    private static String b64(String s) {
        return new String(Base64.decode(s, Base64.DEFAULT));
    }

    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !s.equals("0") && !s.equalsIgnoreCase("false");
    }

    static String formatDate(String s) {
        if (s == null || s.length() < 10) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(in.parse(s.substring(0, 10)));
            return c.get(java.util.Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(java.util.Calendar.MONTH)]
                    + " " + c.get(java.util.Calendar.YEAR);
        } catch (Exception e) {
            return s.substring(0, 10);
        }
    }

    public static String formatTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
