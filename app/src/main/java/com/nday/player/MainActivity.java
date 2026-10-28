package com.nday.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int MODE_VIDEOS = 0;
    private static final int MODE_COLLECTIONS = 1;
    private static final int MODE_COLLECTION = 2;
    private static final int MODE_HISTORY = 3;
    private static final int MODE_YEARS = 4;
    private static final int MODE_YEAR = 5;

    private Session session;
    private History history;
    private GridView grid;
    private TextView title;
    private TextView message;
    private ProgressBar loading;
    private Button btnVideos, btnCollections, btnHistory, btnYears, btnProfile, btnSpeed;
    private final CardAdapter adapter = new CardAdapter();

    private int mode = MODE_VIDEOS;
    private int collectionId;
    private String collectionTitle;
    private int year;
    /** Grid position in the parent list (collections / years), restored on Back. */
    private int parentPosition;
    private int requestSeq;
    /** Grid position to restore once the next list load completes. */
    private int pendingSelection = -1;

    /**
     * Full getVideoList result (all videos, newest first), shared by videos/history/years.
     * Static so a phone rotation does not download the ~600 KB list again.
     */
    private static List<Video> allVideos;
    private static boolean allVideosStale = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        session = new Session(this);
        history = new History(this);

        grid = findViewById(R.id.grid);
        title = findViewById(R.id.title);
        message = findViewById(R.id.message);
        loading = findViewById(R.id.loading);
        btnVideos = findViewById(R.id.btn_videos);
        btnCollections = findViewById(R.id.btn_collections);
        btnHistory = findViewById(R.id.btn_history);
        btnYears = findViewById(R.id.btn_years);
        btnProfile = findViewById(R.id.btn_profile);
        btnSpeed = findViewById(R.id.btn_speed);

        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> openCard(adapter.items.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (mode != MODE_HISTORY) return false;
            editHistory(position);
            return true;
        });

        btnVideos.setOnClickListener(v -> show(MODE_VIDEOS, getString(R.string.all_videos)));
        btnCollections.setOnClickListener(v -> show(MODE_COLLECTIONS, getString(R.string.collections)));
        btnHistory.setOnClickListener(v -> show(MODE_HISTORY, getString(R.string.history_title)));
        btnYears.setOnClickListener(v -> show(MODE_YEARS, getString(R.string.years)));
        btnProfile.setOnClickListener(v -> onProfile());
        btnSpeed.setOnClickListener(v -> cycleDefaultSpeed());
        message.setOnClickListener(v -> {
            allVideosStale = true;
            reload();
        });

        if (savedInstanceState != null) {
            collectionId = savedInstanceState.getInt("collectionId");
            collectionTitle = savedInstanceState.getString("collectionTitle");
            year = savedInstanceState.getInt("year");
            parentPosition = savedInstanceState.getInt("parentPosition");
            pendingSelection = savedInstanceState.getInt("position", -1);
            title.setText(savedInstanceState.getCharSequence("heading"));
            mode = savedInstanceState.getInt("mode");
            reload();
        } else {
            show(MODE_VIDEOS, getString(R.string.all_videos));
            btnVideos.requestFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("mode", mode);
        out.putInt("collectionId", collectionId);
        out.putString("collectionTitle", collectionTitle);
        out.putInt("year", year);
        out.putInt("parentPosition", parentPosition);
        out.putInt("position", grid.getFirstVisiblePosition());
        out.putCharSequence("heading", title.getText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSpeedButton();
        loadUser();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Refresh watch progress after returning from the player.
        allVideosStale = true;
        reload();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && (mode == MODE_COLLECTION || mode == MODE_YEAR)) {
            pendingSelection = parentPosition;
            if (mode == MODE_COLLECTION) show(MODE_COLLECTIONS, getString(R.string.collections));
            else show(MODE_YEARS, getString(R.string.years));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && mode == MODE_HISTORY && grid.hasFocus()) {
            editHistory(grid.getSelectedItemPosition());
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            cycleDefaultSpeed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void show(int newMode, String heading) {
        mode = newMode;
        title.setText(heading);
        reload();
    }

    private int listKey() {
        return mode == MODE_COLLECTION ? collectionId : mode == MODE_YEAR ? year : 0;
    }

    private void reload() {
        final int seq = ++requestSeq;
        final int m = mode;
        final int key = listKey();
        final int keepPosition = grid.getSelectedItemPosition();
        final boolean sameList = !adapter.items.isEmpty() && adapter.mode == m && adapter.key == key;
        message.setVisibility(View.GONE);

        boolean fromVideoList = m == MODE_VIDEOS || m == MODE_HISTORY || m == MODE_YEARS || m == MODE_YEAR;
        if (fromVideoList && allVideos != null && !allVideosStale) {
            showCards(m, key, buildFromVideos(m), sameList, keepPosition);
            return;
        }
        if (!sameList) {
            adapter.set(m, key, new ArrayList<>());
            loading.setVisibility(View.VISIBLE);
        }
        String path = fromVideoList ? "/api/getVideoList"
                : m == MODE_COLLECTIONS ? "/api/getCollectionList"
                : "/api/getCollection/" + collectionId;
        Api.get(path, session.token(), (result, error) -> {
            if (seq != requestSeq) return;
            loading.setVisibility(View.GONE);
            if (error != null) {
                if (!sameList) showMessage(getString(R.string.error_connect) + "\n" + error.getMessage());
                return;
            }
            List<Card> cards;
            if (fromVideoList) {
                allVideos = parseVideos(result, "vdeos");
                allVideosStale = false;
                cards = buildFromVideos(m);
            } else if (m == MODE_COLLECTIONS) {
                cards = parseCollections(result);
            } else {
                cards = new ArrayList<>();
                for (Video v : parseVideos(result, "videos")) cards.add(videoCard(v, null));
            }
            showCards(m, key, cards, sameList, keepPosition);
        });
    }

    private void showCards(int m, int key, List<Card> cards, boolean sameList, int keepPosition) {
        loading.setVisibility(View.GONE);
        adapter.set(m, key, cards);
        if (cards.isEmpty()) {
            showMessage(getString(m == MODE_HISTORY ? R.string.history_empty : R.string.empty));
        } else {
            int sel = pendingSelection >= 0 ? pendingSelection : sameList ? keepPosition : -1;
            if (sel >= 0) grid.setSelection(Math.min(sel, cards.size() - 1));
        }
        pendingSelection = -1;
    }

    private List<Card> buildFromVideos(int m) {
        List<Card> cards = new ArrayList<>();
        if (m == MODE_VIDEOS) {
            for (Video v : allVideos) cards.add(videoCard(v, null));
        } else if (m == MODE_YEAR) {
            for (Video v : allVideos) if (v.year == year) cards.add(videoCard(v, null));
        } else if (m == MODE_YEARS) {
            Map<Integer, List<Video>> byYear = new LinkedHashMap<>();
            for (Video v : allVideos) {
                if (v.year == 0) continue;
                List<Video> list = byYear.get(v.year);
                if (list == null) byYear.put(v.year, list = new ArrayList<>());
                list.add(v);
            }
            List<Integer> years = new ArrayList<>(byYear.keySet());
            java.util.Collections.sort(years, java.util.Collections.reverseOrder());
            for (int y : years) {
                List<Video> list = byYear.get(y);
                Card c = new Card();
                c.year = y;
                c.title = String.valueOf(y);
                c.subtitle = getString(R.string.videos_count, String.valueOf(list.size()));
                c.pic = list.get(0).pic;
                cards.add(c);
            }
        } else if (m == MODE_HISTORY) {
            cards = buildHistory();
        }
        return cards;
    }

    /**
     * Everything the account has watched anywhere (server "progress"), plus anything played in this
     * app. Videos with a watch time recorded here come first, most recent first; the rest follow
     * by publish date, since the server does not say when they were watched.
     */
    private List<Card> buildHistory() {
        Map<Integer, Video> byId = new HashMap<>();
        for (Video v : allVideos) byId.put(v.id, v);
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM, HH:mm", new Locale("ru"));

        List<Card> cards = new ArrayList<>();
        java.util.Set<Integer> added = new java.util.HashSet<>();
        for (History.Entry e : history.all()) {
            Video v = byId.containsKey(e.video.id) ? byId.get(e.video.id) : e.video;
            int percent = Math.max(v.progress, e.percent());
            Card c = videoCard(v, getString(R.string.watched_at, fmt.format(new Date(e.watchedAt)))
                    + (percent > 0 ? " · " + percent + "%" : ""));
            c.progress = percent;
            cards.add(c);
            added.add(v.id);
        }
        for (Video v : allVideos) {
            if (v.progress <= 0 || added.contains(v.id) || history.isHidden(v.id)) continue;
            cards.add(videoCard(v, v.date + " · " + v.progress + "%"));
        }
        return cards;
    }

    private Card videoCard(Video v, String subtitle) {
        Card c = new Card();
        c.video = v;
        c.title = v.title;
        c.pic = v.pic;
        c.subtitle = subtitle != null ? subtitle
                : v.date + (v.author.isEmpty() ? "" : (v.date.isEmpty() ? "" : " · ") + v.author);
        c.progress = v.progress;
        c.free = v.free;
        return c;
    }

    private static List<Video> parseVideos(Object result, String key) {
        List<Video> list = new ArrayList<>();
        JSONObject obj = result instanceof JSONObject ? (JSONObject) result : null;
        JSONArray arr = obj == null ? null : obj.optJSONArray(key);
        if (arr == null && obj != null) arr = obj.optJSONArray("videos");
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(Video.fromJson(o));
        }
        return list;
    }

    private List<Card> parseCollections(Object result) {
        List<Card> cards = new ArrayList<>();
        JSONArray arr = result instanceof JSONArray ? (JSONArray) result : null;
        if (arr == null && result instanceof JSONObject) arr = ((JSONObject) result).optJSONArray("data");
        if (arr == null) return cards;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Card c = new Card();
            c.collectionId = o.optInt("id");
            c.title = Video.str(o, "name");
            String poster = Video.str(o, "poster");
            if (!poster.isEmpty()) c.pic = Api.STORAGE_MD + poster;
            JSONArray count = o.optJSONArray("videos_count");
            if (count != null && count.optJSONObject(0) != null) {
                c.subtitle = getString(R.string.videos_count, Video.str(count.optJSONObject(0), "count"));
            }
            cards.add(c);
        }
        return cards;
    }

    private void editHistory(int position) {
        if (position < 0 || position >= adapter.items.size()) return;
        final Card c = adapter.items.get(position);
        new AlertDialog.Builder(this)
                .setTitle(c.title)
                .setItems(new String[]{getString(R.string.history_remove), getString(R.string.history_clear)},
                        (d, which) -> {
                            List<Integer> ids = new ArrayList<>();
                            if (which == 0) {
                                ids.add(c.video.id);
                            } else {
                                for (Card card : adapter.items) ids.add(card.video.id);
                            }
                            history.hide(ids);
                            pendingSelection = position;
                            reload();
                            if (adapter.items.isEmpty()) btnHistory.requestFocus();
                        })
                .show();
    }

    private void openCard(Card c) {
        if (c.video != null) {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_VIDEO, c.video);
            if (mode == MODE_COLLECTION) {
                i.putExtra(DetailActivity.EXTRA_PLAYLIST, adapter.videoIds());
                i.putExtra(DetailActivity.EXTRA_COLLECTION_TITLE, collectionTitle);
            }
            startActivity(i);
        } else if (c.year != 0) {
            parentPosition = grid.getSelectedItemPosition();
            year = c.year;
            show(MODE_YEAR, getString(R.string.year_title, c.year));
        } else {
            parentPosition = grid.getSelectedItemPosition();
            collectionId = c.collectionId;
            collectionTitle = c.title;
            show(MODE_COLLECTION, getString(R.string.collection_title, c.title));
        }
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void loadUser() {
        if (!session.isSignedIn()) {
            btnProfile.setText(R.string.sign_in);
            return;
        }
        Api.get("/api/user", session.token(), (result, error) -> {
            if (result instanceof JSONObject) {
                JSONObject u = (JSONObject) result;
                Session.userName = Video.str(u, "name");
                Session.paidUntil = Video.formatDate(Video.str(u, "paid_until"));
                Session.fullView = Video.truthy(u.opt("is_full_view"));
                btnProfile.setText(Session.userName.isEmpty() ? getString(R.string.profile) : Session.userName);
            } else if (error instanceof Api.HttpException && ((Api.HttpException) error).code == 401) {
                session.signOut();
                btnProfile.setText(R.string.sign_in);
            }
        });
        btnProfile.setText(Session.userName == null || Session.userName.isEmpty()
                ? getString(R.string.profile) : Session.userName);
    }

    private void onProfile() {
        if (!session.isSignedIn()) {
            startActivity(new Intent(this, AuthActivity.class));
            return;
        }
        String info = (Session.userName == null ? "" : Session.userName) + "\n\n"
                + (Session.paidUntil != null && !Session.paidUntil.isEmpty()
                ? getString(R.string.paid_until, Session.paidUntil)
                : Session.fullView ? "" : getString(R.string.not_paid));
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile)
                .setMessage(info.trim())
                .setPositiveButton(R.string.close, null)
                .setNegativeButton(R.string.sign_out, (d, w) -> {
                    session.signOut();
                    btnProfile.setText(R.string.sign_in);
                    allVideosStale = true;
                    reload();
                })
                .show();
    }

    private void cycleDefaultSpeed() {
        float cur = session.speed();
        float next = Session.SPEEDS[0];
        for (int i = 0; i < Session.SPEEDS.length; i++) {
            if (Math.abs(Session.SPEEDS[i] - cur) < 0.01f) {
                next = Session.SPEEDS[(i + 1) % Session.SPEEDS.length];
                break;
            }
        }
        session.setSpeed(next);
        updateSpeedButton();
    }

    private void updateSpeedButton() {
        btnSpeed.setText(getString(R.string.speed_button, formatSpeed(session.speed())));
    }

    static String formatSpeed(float s) {
        String t = String.format(Locale.ROOT, "%.2f", s);
        while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".")) t += "0";
        return t + "×";
    }

    static final class Card {
        Video video;
        int collectionId;
        int year;
        String title = "";
        String subtitle = "";
        String pic;
        int progress;
        boolean free;
    }

    private final class CardAdapter extends BaseAdapter {
        final List<Card> items = new ArrayList<>();
        int mode = -1;
        /** Collection id or year the list belongs to, 0 for top-level lists. */
        int key;

        void set(int mode, int key, List<Card> cards) {
            this.mode = mode;
            this.key = key;
            items.clear();
            items.addAll(cards);
            notifyDataSetChanged();
        }

        int[] videoIds() {
            int[] ids = new int[items.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = items.get(i).video == null ? 0 : items.get(i).video.id;
            return ids;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_card, parent, false);
            Card c = items.get(position);
            TextView titleView = v.findViewById(R.id.card_title);
            titleView.setText(c.title);
            // Year cards: big centered number instead of a two-line title.
            titleView.setTextSize(c.year != 0 ? 22 : 14);
            TextView sub = v.findViewById(R.id.card_subtitle);
            sub.setText(c.subtitle);
            sub.setVisibility(c.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            v.findViewById(R.id.card_free).setVisibility(c.free ? View.VISIBLE : View.GONE);
            ProgressBar p = v.findViewById(R.id.card_progress);
            p.setVisibility(c.progress > 0 ? View.VISIBLE : View.GONE);
            p.setProgress(c.progress);
            ImageLoader.load((ImageView) v.findViewById(R.id.card_image), c.pic, 400);
            return v;
        }
    }
}
