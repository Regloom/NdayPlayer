package com.nday.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

/** Video info screen: loads /api/getVideo/{id}, offers play / resume / like. */
public class DetailActivity extends Activity {
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_COLLECTION_TITLE = "collection_title";

    /** Resume only past this point, like the original (M = 30 s). */
    static final int MIN_RESUME_SEC = 30;

    private Session session;
    private Video video;
    private int[] playlist;
    private String collectionTitle;

    private TextView title, meta, description, status;
    private ImageView poster;
    private Button btnPlay, btnRestart, btnLike, btnAuth, btnPrev, btnNext;
    private boolean liked;
    /** False until getVideo has answered for the current id (list items lack can_watch). */
    private boolean loaded;
    private int loadSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        session = new Session(this);
        video = (Video) (savedInstanceState != null
                ? savedInstanceState.getSerializable(EXTRA_VIDEO)
                : getIntent().getSerializableExtra(EXTRA_VIDEO));
        playlist = getIntent().getIntArrayExtra(EXTRA_PLAYLIST);
        collectionTitle = getIntent().getStringExtra(EXTRA_COLLECTION_TITLE);

        title = findViewById(R.id.detail_title);
        meta = findViewById(R.id.detail_meta);
        description = findViewById(R.id.detail_description);
        status = findViewById(R.id.detail_status);
        poster = findViewById(R.id.detail_poster);
        btnPlay = findViewById(R.id.btn_play);
        btnRestart = findViewById(R.id.btn_restart);
        btnLike = findViewById(R.id.btn_like);
        btnAuth = findViewById(R.id.btn_auth);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);

        btnPlay.setOnClickListener(v -> play(true));
        btnRestart.setOnClickListener(v -> play(false));
        btnLike.setOnClickListener(v -> like());
        btnAuth.setOnClickListener(v -> startActivity(new Intent(this, AuthActivity.class)));
        btnPrev.setOnClickListener(v -> step(-1));
        btnNext.setOnClickListener(v -> step(1));

        TextView coll = findViewById(R.id.detail_collection);
        if (playlist != null && playlist.length > 1) {
            coll.setText(getString(R.string.collection_title, collectionTitle == null ? "" : collectionTitle));
            coll.setVisibility(View.VISIBLE);
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
        }

        bind();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putSerializable(EXTRA_VIDEO, video);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final int seq = ++loadSeq;
        final int id = video.id;
        Api.get("/api/getVideo/" + id, session.token(), (result, error) -> {
            if (seq != loadSeq || id != video.id) return;
            if (result instanceof JSONObject && ((JSONObject) result).has("id")) {
                boolean firstLoad = !loaded;
                video = Video.fromJson((JSONObject) result);
                loaded = true;
                bind();
                // Before loading only "Войти" was visible and took focus; move it to playback.
                if (firstLoad && btnPlay.getVisibility() == View.VISIBLE) btnPlay.requestFocus();
            } else if (error != null) {
                status.setText(getString(R.string.error_connect) + "\n" + error.getMessage());
                status.setVisibility(View.VISIBLE);
            }
        });
    }

    private void bind() {
        title.setText(video.title);
        StringBuilder m = new StringBuilder();
        append(m, video.author);
        append(m, video.date);
        if (!video.views.isEmpty()) append(m, getString(R.string.views, video.views));
        if (!video.likes.isEmpty()) append(m, getString(R.string.likes, video.likes));
        if (video.free) append(m, getString(R.string.free_video));
        meta.setText(m);
        description.setText(video.description);
        ImageLoader.load(poster, video.picOrig != null ? video.picOrig : video.pic, 1280);

        liked = video.isLiked;
        btnLike.setText(liked ? R.string.liked : R.string.like);
        btnLike.setEnabled(!liked);

        boolean signedIn = session.isSignedIn();
        boolean playable = video.playable(Session.fullView) && video.manifestUrl() != null;
        int resumeSec = resumeSec();
        boolean resume = resumeSec >= MIN_RESUME_SEC;

        btnPlay.setVisibility(playable ? View.VISIBLE : View.GONE);
        btnPlay.setText(resume ? getString(R.string.resume_from, Video.formatTime(resumeSec * 1000L))
                : getString(R.string.watch));
        btnRestart.setVisibility(playable && resume ? View.VISIBLE : View.GONE);
        btnLike.setVisibility(playable && signedIn ? View.VISIBLE : View.GONE);
        btnAuth.setVisibility(signedIn ? View.GONE : View.VISIBLE);

        status.setVisibility(View.GONE);
        if (!loaded) {
            btnPlay.setVisibility(View.GONE);
            btnRestart.setVisibility(View.GONE);
            btnLike.setVisibility(View.GONE);
        } else if (!playable) {
            status.setText(!signedIn
                    ? (video.free ? R.string.need_auth_free : R.string.need_auth_paid)
                    : R.string.need_payment);
            status.setVisibility(View.VISIBLE);
        }

        View focus = getCurrentFocus();
        if (focus == null || focus.getVisibility() != View.VISIBLE) {
            if (playable) btnPlay.requestFocus();
            else if (!signedIn) btnAuth.requestFocus();
            else if (btnNext.getVisibility() == View.VISIBLE) btnNext.requestFocus();
        }
    }

    /** Server position (signed in), else the local history position unless the video was finished. */
    private int resumeSec() {
        if (video.currentSec >= MIN_RESUME_SEC) return video.currentSec;
        History.Entry e = new History(this).get(video.id);
        if (e == null || e.durationSec <= 0 || e.positionSec > e.durationSec - MIN_RESUME_SEC) return 0;
        return (int) e.positionSec;
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) return;
        if (sb.length() > 0) sb.append("   ·   ");
        sb.append(s);
    }

    private void play(boolean resume) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_VIDEO, video);
        i.putExtra(PlayerActivity.EXTRA_START_SEC, resume ? resumeSec() : 0);
        startActivity(i);
    }

    private void like() {
        if (liked) return;
        liked = true;
        btnLike.setText(R.string.liked);
        btnLike.setEnabled(false);
        btnPlay.requestFocus();
        Api.post("/api/getVideo/" + video.id + "/like", session.token(), null, null);
    }

    private void step(int delta) {
        if (playlist == null || playlist.length == 0) return;
        int idx = 0;
        for (int i = 0; i < playlist.length; i++) if (playlist[i] == video.id) idx = i;
        idx = (idx + delta + playlist.length) % playlist.length;
        Video next = new Video();
        next.id = playlist[idx];
        video = next;
        loaded = false;
        bind();
        load();
    }
}
