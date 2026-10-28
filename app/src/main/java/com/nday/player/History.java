package com.nday.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Watch times recorded on this device, most recent first. The server stores which videos were
 * watched (getVideoList "progress") but not when, so these timestamps order the history screen.
 */
public final class History {
    private static final String PREFS = "history";
    private static final String KEY = "entries";
    private static final String KEY_HIDDEN = "hidden";
    private static final int MAX_ENTRIES = 200;

    public static final class Entry {
        public Video video;
        public long positionSec;
        public long durationSec;
        public long watchedAt;

        public int percent() {
            return durationSec <= 0 ? 0 : (int) Math.min(100, positionSec * 100 / durationSec);
        }
    }

    private final SharedPreferences prefs;

    public History(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Entry> all() {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public Entry get(int videoId) {
        for (Entry e : all()) if (e.video.id == videoId) return e;
        return null;
    }

    /** Moves the video to the top and records its position. */
    public void record(Video v, long positionSec, long durationSec) {
        List<Entry> list = all();
        Entry entry = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).video.id == v.id) {
                entry = list.remove(i);
                break;
            }
        }
        if (entry == null) entry = new Entry();
        entry.video = v;
        if (positionSec >= 0) entry.positionSec = positionSec;
        if (durationSec > 0) entry.durationSec = durationSec;
        entry.watchedAt = System.currentTimeMillis();
        list.add(0, entry);
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        save(list);
        java.util.Set<String> hidden = prefs.getStringSet(KEY_HIDDEN, null);
        if (hidden != null && hidden.contains(String.valueOf(v.id))) {
            hidden = new java.util.HashSet<>(hidden);
            hidden.remove(String.valueOf(v.id));
            prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        }
    }

    /**
     * Hides videos from the history screen on this device (the server keeps their progress and
     * has no API to delete it). Watching a video again un-hides it.
     */
    public void hide(java.util.Collection<Integer> videoIds) {
        java.util.Set<String> hidden = new java.util.HashSet<>(prefs.getStringSet(KEY_HIDDEN,
                java.util.Collections.<String>emptySet()));
        List<Entry> list = all();
        for (Integer id : videoIds) {
            hidden.add(String.valueOf(id));
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).video.id == id) {
                    list.remove(i);
                    break;
                }
            }
        }
        save(list);
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
    }

    public boolean isHidden(int videoId) {
        return prefs.getStringSet(KEY_HIDDEN, java.util.Collections.<String>emptySet())
                .contains(String.valueOf(videoId));
    }

    private void save(List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (Entry e : list) arr.put(toJson(e));
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    private static JSONObject toJson(Entry e) {
        JSONObject o = new JSONObject();
        try {
            Video v = e.video;
            o.put("id", v.id);
            o.put("title", v.title);
            o.put("author", v.author);
            o.put("date", v.date);
            o.put("pic", v.pic);
            o.put("picOrig", v.picOrig);
            o.put("free", v.free);
            o.put("pos", e.positionSec);
            o.put("dur", e.durationSec);
            o.put("at", e.watchedAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Entry fromJson(JSONObject o) {
        Video v = new Video();
        v.id = o.optInt("id");
        v.title = Video.str(o, "title");
        v.author = Video.str(o, "author");
        v.date = Video.str(o, "date");
        v.pic = o.isNull("pic") ? null : o.optString("pic", null);
        v.picOrig = o.isNull("picOrig") ? null : o.optString("picOrig", null);
        v.free = o.optBoolean("free");
        Entry e = new Entry();
        e.video = v;
        e.positionSec = o.optLong("pos");
        e.durationSec = o.optLong("dur");
        e.watchedAt = o.optLong("at");
        return e;
    }
}
