package com.nday.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Fullscreen player. Every remote key is handled in {@link #dispatchKeyEvent}, so play/pause,
 * seeking and speed changes work whether the control overlay is visible or not.
 *
 * <ul>
 *   <li>OK / Play-Pause: pause or resume</li>
 *   <li>Left / Right, Rewind / Fast-forward: seek (hold to accelerate)</li>
 *   <li>Up / Down: playback speed up / down</li>
 *   <li>Menu: speed list</li>
 * </ul>
 */
public class PlayerActivity extends Activity {
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_START_SEC = "start_sec";

    private static final long OVERLAY_TIMEOUT_MS = 4000;
    private static final long SEEK_COMMIT_MS = 600;
    private static final long REPORT_INTERVAL_SEC = 30;

    private final Handler handler = new Handler();
    private Session session;
    private History history;
    private long lastHistorySaveMs;
    private Video video;

    private SimpleExoPlayer player;
    private FrameLayout videoFrame;
    private SurfaceView surface;
    private View overlay;
    private TextView title, position, duration, speedLabel, speedBadge, centerIcon, errorView;
    private SeekBar seekBar;
    private ProgressBar buffering;
    private Button btnPlayPause, btnSpeed;
    /** Touchscreen device (phone/tablet): show touch controls and accept gestures. */
    private boolean touch;
    /** The user is dragging the seek bar; don't overwrite it from the player position. */
    private boolean dragging;

    private float speed;
    private float videoAspect;
    private long resumePositionMs;
    private boolean seeking;
    private long seekDeltaMs;
    private long lastSeekEventTime;
    private long lastReportedSec = -1;
    private boolean hasError;

    private final Runnable hideOverlay = () -> {
        if (player != null && player.getPlayWhenReady() && !hasError && !dragging) overlay.setVisibility(View.GONE);
    };
    private final Runnable hideBadge = () -> speedBadge.setVisibility(View.GONE);
    private final Runnable hideCenterIcon = () -> centerIcon.setVisibility(View.GONE);
    private final Runnable commitSeek = this::commitSeek;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            maybeReportProgress();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        session = new Session(this);
        history = new History(this);
        video = (Video) getIntent().getSerializableExtra(EXTRA_VIDEO);
        int startSec = getIntent().getIntExtra(EXTRA_START_SEC, 0);
        resumePositionMs = startSec >= DetailActivity.MIN_RESUME_SEC ? startSec * 1000L : 0;
        speed = session.speed();

        videoFrame = findViewById(R.id.video_frame);
        surface = findViewById(R.id.surface);
        overlay = findViewById(R.id.overlay);
        title = findViewById(R.id.player_title);
        position = findViewById(R.id.player_position);
        duration = findViewById(R.id.player_duration);
        speedLabel = findViewById(R.id.player_speed);
        speedBadge = findViewById(R.id.speed_badge);
        centerIcon = findViewById(R.id.center_icon);
        errorView = findViewById(R.id.player_error);
        seekBar = findViewById(R.id.player_seek);
        buffering = findViewById(R.id.buffering);

        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSpeed = findViewById(R.id.btn_speed);
        touch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        if (touch) {
            setupTouch();
        } else {
            seekBar.setThumb(null);
        }

        title.setText(video.title);
        updateSpeedLabel();
        videoFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyAspect();
        });
    }

    private void setupTouch() {
        findViewById(R.id.touch_controls).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.player_hints)).setText(R.string.player_hints_touch);
        btnPlayPause.setOnClickListener(v -> togglePlay());
        btnSpeed.setOnClickListener(v -> chooseSpeed());
        findViewById(R.id.btn_rewind).setOnClickListener(v -> seekTap(-10_000));
        findViewById(R.id.btn_forward).setOnClickListener(v -> seekTap(10_000));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                long dur = player.getDuration();
                if (dur > 0) position.setText(Video.formatTime(dur * progress / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                handler.removeCallbacks(hideOverlay);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && player.getDuration() > 0) {
                    seeking = false;
                    player.seekTo(player.getDuration() * bar.getProgress() / 1000);
                }
                showOverlay();
            }
        });

        // Tap toggles the overlay; double tap on the left/right third seeks, in the middle pauses.
        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (overlay.getVisibility() == View.VISIBLE && player != null && player.getPlayWhenReady()) {
                    handler.removeCallbacks(hideOverlay);
                    overlay.setVisibility(View.GONE);
                } else {
                    showOverlay();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (hasError) {
                    retry();
                    return true;
                }
                float third = videoFrame.getWidth() / 3f;
                if (e.getX() < third) seekTap(-10_000);
                else if (e.getX() > 2 * third) seekTap(10_000);
                else togglePlay();
                return true;
            }
        });
        findViewById(R.id.player_root).setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
    }

    /** One touch seek step; repeated taps accumulate like repeated remote presses. */
    private void seekTap(long deltaMs) {
        addSeekDelta(deltaMs);
        flashCenter(deltaMs < 0 ? "« 10" : "10 »");
        scheduleSeekCommit();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initPlayer();
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            if (seeking) commitSeek();
            resumePositionMs = player.getCurrentPosition();
            saveHistory();
            reportProgress();
            player.release();
            player = null;
        }
    }

    private void initPlayer() {
        hasError = false;
        errorView.setVisibility(View.GONE);

        DefaultHttpDataSourceFactory http = new DefaultHttpDataSourceFactory(Api.MEDIA_USER_AGENT, null,
                15000, 20000, true);
        http.getDefaultRequestProperties().set("Referer", Api.MEDIA_REFERER);
        http.getDefaultRequestProperties().set("Origin", Api.MEDIA_REFERER);

        String url = video.manifestUrl();
        if (url == null) {
            showError(getString(R.string.error_no_stream));
            return;
        }
        Uri uri = Uri.parse(url);
        android.util.Log.i("NdayPlayer", "stream=" + url + " drm=" + video.drmScheme() + " license=" + video.licenseUrl());

        DrmSessionManager drm = DrmSessionManager.DRM_UNSUPPORTED;
        String scheme = video.drmScheme();
        String license = video.licenseUrl();
        if (scheme != null && license != null) {
            UUID uuid = Video.DRM_WIDEVINE.equals(scheme) ? C.WIDEVINE_UUID
                    : Video.DRM_PLAYREADY.equals(scheme) ? C.PLAYREADY_UUID : C.CLEARKEY_UUID;
            if (!FrameworkMediaDrm.isCryptoSchemeSupported(uuid)) {
                showError(getString(R.string.error_drm_unsupported, scheme));
                return;
            }
            HttpMediaDrmCallback callback = new HttpMediaDrmCallback(license, http);
            callback.setKeyRequestProperty("Referer", Api.MEDIA_REFERER);
            callback.setKeyRequestProperty("Origin", Api.MEDIA_REFERER);
            drm = new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(callback);
        }

        MediaSource source;
        int type = Util.inferContentType(uri);
        if (type == C.TYPE_DASH || video.ksPleer != null) {
            source = new DashMediaSource.Factory(http).setDrmSessionManager(drm).createMediaSource(uri);
        } else if (type == C.TYPE_HLS) {
            source = new HlsMediaSource.Factory(http).setDrmSessionManager(drm).createMediaSource(uri);
        } else {
            source = new ProgressiveMediaSource.Factory(http).setDrmSessionManager(drm).createMediaSource(uri);
        }

        player = new SimpleExoPlayer.Builder(this, new PcmRenderersFactory(this))
                .setTrackSelector(new DefaultTrackSelector(this))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setVideoSurfaceView(surface);
        player.setPlaybackParameters(new PlaybackParameters(speed));
        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int state) {
                buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_ENDED) {
                    saveHistory();
                    reportProgress();
                    showOverlay();
                }
                updateProgress();
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                resumePositionMs = player.getCurrentPosition();
                showError(describe(error));
            }
        });
        player.addVideoListener(new VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                           float pixelWidthHeightRatio) {
                videoAspect = height == 0 ? 0 : width * pixelWidthHeightRatio / height;
                applyAspect();
            }
        });
        player.setMediaSource(source, resumePositionMs);
        player.prepare();
        history.record(video, resumePositionMs / 1000, -1);
        player.setPlayWhenReady(true);
        showOverlay();
    }

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (first) {
                    if (hasError) retry();
                    else togglePlay();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (first) setPlaying(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (first) setPlaying(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seekBy(-1, event);
                else scheduleSeekCommit();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seekBy(1, event);
                else scheduleSeekCommit();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (first) stepSpeed(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (first) stepSpeed(-1);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (first) chooseSpeed();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            setPlaying(true);
            return;
        }
        setPlaying(!player.getPlayWhenReady());
    }

    private void setPlaying(boolean play) {
        if (player == null) return;
        player.setPlayWhenReady(play);
        if (!play) {
            saveHistory();
            reportProgress();
        }
        flashCenter(play ? "▶" : "❚❚");
        showOverlay();
    }

    /**
     * One press moves 10 s. While held, the jump grows with the hold time (not the key-repeat
     * count, which differs between remotes): ~20 s of video per second held, ~60 s/s after 2 s,
     * ~180 s/s after 5 s. The offset is applied relative to the position at commit time, so the
     * target does not drift while the video keeps playing.
     */
    private void seekBy(int direction, KeyEvent event) {
        if (player == null) return;
        long step;
        if (event.getRepeatCount() == 0) {
            step = 10_000;
        } else {
            long held = event.getEventTime() - event.getDownTime();
            long sinceLast = Math.min(200, Math.max(0, event.getEventTime() - lastSeekEventTime));
            long rate = held < 2000 ? 20 : held < 5000 ? 60 : 180;
            step = rate * sinceLast;
        }
        lastSeekEventTime = event.getEventTime();
        addSeekDelta(direction * step);
    }

    private void addSeekDelta(long deltaMs) {
        if (player == null) return;
        if (!seeking) {
            seeking = true;
            seekDeltaMs = 0;
        }
        seekDeltaMs += deltaMs;
        // Keep the delta within the media so reversing direction responds immediately.
        long cur = player.getCurrentPosition();
        long dur = player.getDuration();
        seekDeltaMs = Math.max(-cur, seekDeltaMs);
        if (dur != C.TIME_UNSET && dur > 0) seekDeltaMs = Math.min(dur - 1000 - cur, seekDeltaMs);
        handler.removeCallbacks(commitSeek);
        showOverlay();
        updateProgress();
    }

    /** Position the pending seek would land on, or -1 when no seek is pending. */
    private long pendingSeekTarget() {
        return seeking && player != null ? Math.max(0, player.getCurrentPosition() + seekDeltaMs) : -1;
    }

    private void scheduleSeekCommit() {
        handler.removeCallbacks(commitSeek);
        if (seeking) handler.postDelayed(commitSeek, SEEK_COMMIT_MS);
    }

    private void commitSeek() {
        long target = pendingSeekTarget();
        seeking = false;
        if (target >= 0) player.seekTo(target);
        updateProgress();
    }

    // ---------------------------------------------------------------- speed

    private void stepSpeed(int direction) {
        int idx = 0;
        for (int i = 0; i < Session.SPEEDS.length; i++) {
            if (Math.abs(Session.SPEEDS[i] - speed) < 0.01f) idx = i;
        }
        idx = Math.max(0, Math.min(Session.SPEEDS.length - 1, idx + direction));
        setSpeed(Session.SPEEDS[idx]);
    }

    private void chooseSpeed() {
        String[] labels = new String[Session.SPEEDS.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = MainActivity.formatSpeed(Session.SPEEDS[i]);
            if (Math.abs(Session.SPEEDS[i] - speed) < 0.01f) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.speed)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    setSpeed(Session.SPEEDS[which]);
                    d.dismiss();
                })
                .show();
    }

    private void setSpeed(float s) {
        speed = s;
        session.setSpeed(s);
        // Pitch stays 1.0: voices sound normal at any speed.
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(s));
        updateSpeedLabel();
        speedBadge.setText(getString(R.string.speed_badge, MainActivity.formatSpeed(s)));
        speedBadge.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBadge);
        handler.postDelayed(hideBadge, 1500);
        showOverlay();
    }

    private void updateSpeedLabel() {
        speedLabel.setText(getString(R.string.speed_badge, MainActivity.formatSpeed(speed)));
        btnSpeed.setText(MainActivity.formatSpeed(speed));
    }

    // ---------------------------------------------------------------- UI

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
        updateProgress();
    }

    private void flashCenter(String symbol) {
        centerIcon.setText(symbol);
        centerIcon.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCenterIcon);
        handler.postDelayed(hideCenterIcon, 800);
    }

    private void updateProgress() {
        if (player == null || overlay.getVisibility() != View.VISIBLE || dragging) return;
        btnPlayPause.setText(player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                ? "❚❚" : "▶");
        long dur = player.getDuration();
        long target = pendingSeekTarget();
        long pos = target >= 0 ? target : player.getCurrentPosition();
        position.setText(target >= 0
                ? (seekDeltaMs >= 0 ? "» " : "« ") + Video.formatTime(pos)
                : Video.formatTime(pos));
        if (dur == C.TIME_UNSET || dur <= 0) {
            duration.setText("--:--");
            seekBar.setProgress(0);
            seekBar.setSecondaryProgress(0);
            return;
        }
        duration.setText(Video.formatTime(dur));
        seekBar.setMax(1000);
        seekBar.setProgress((int) (pos * 1000 / dur));
        seekBar.setSecondaryProgress((int) (player.getBufferedPosition() * 1000 / dur));
    }

    private void applyAspect() {
        int w = videoFrame.getWidth(), h = videoFrame.getHeight();
        if (w == 0 || h == 0 || videoAspect <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if ((float) w / h > videoAspect) {
            lp.height = h;
            lp.width = Math.round(h * videoAspect);
        } else {
            lp.width = w;
            lp.height = Math.round(w / videoAspect);
        }
        surface.setLayoutParams(lp);
    }

    private void showError(String text) {
        hasError = true;
        buffering.setVisibility(View.GONE);
        errorView.setText(text + "\n\n" + getString(R.string.retry_hint));
        errorView.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
    }

    private void retry() {
        if (player != null) {
            player.release();
            player = null;
        }
        initPlayer();
    }

    private String describe(ExoPlaybackException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String type = e.type == ExoPlaybackException.TYPE_SOURCE ? getString(R.string.error_source)
                : e.type == ExoPlaybackException.TYPE_RENDERER ? getString(R.string.error_renderer)
                : getString(R.string.error_playback);
        return type + "\n" + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /**
     * Always decodes audio to PCM. With HDMI passthrough (AC-3/E-AC-3 to an AV receiver) the
     * audio sink cannot time-stretch, so speed changes would be ignored.
     */
    private static final class PcmRenderersFactory extends DefaultRenderersFactory {
        PcmRenderersFactory(Context context) {
            super(context);
        }

        @Override
        protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                           boolean enableAudioTrackPlaybackParams, boolean enableOffload) {
            return new DefaultAudioSink(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES,
                    new DefaultAudioSink.DefaultAudioProcessorChain(), enableFloatOutput, false, false);
        }
    }

    // ---------------------------------------------------------------- progress sync

    private void maybeReportProgress() {
        if (player == null || !player.isPlaying()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastHistorySaveMs >= 10_000) saveHistory();
        long sec = player.getCurrentPosition() / 1000;
        if (lastReportedSec < 0 || Math.abs(sec - lastReportedSec) >= REPORT_INTERVAL_SEC) {
            reportProgress();
        }
    }

    /** Stores the current position in the local watch history. */
    private void saveHistory() {
        if (player == null) return;
        lastHistorySaveMs = android.os.SystemClock.elapsedRealtime();
        long dur = player.getDuration();
        long durSec = dur == C.TIME_UNSET ? -1 : dur / 1000;
        long sec = player.getPlaybackState() == Player.STATE_ENDED && durSec > 0
                ? durSec : player.getCurrentPosition() / 1000;
        history.record(video, sec, durSec);
    }

    /** POST /api/getVideo/{id}/progress like the original (skipped for the guest token, positions under 30 s). */
    private void reportProgress() {
        if (player == null || !session.isSignedIn()) return;
        long sec = player.getCurrentPosition() / 1000;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || sec < DetailActivity.MIN_RESUME_SEC) return;
        long durSec = dur / 1000;
        if (player.getPlaybackState() == Player.STATE_ENDED) sec = durSec;
        lastReportedSec = sec;
        try {
            JSONObject body = new JSONObject();
            body.put("curent_sec", sec);
            body.put("duration_sec", durSec);
            Api.post("/api/getVideo/" + video.id + "/progress", session.token(), body, null);
        } catch (Exception ignored) {
        }
    }
}
