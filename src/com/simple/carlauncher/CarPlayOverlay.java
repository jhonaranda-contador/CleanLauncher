package com.simple.carlauncher;

import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Floating pill shown over CarPlay (Zlink) so the launcher stays one tap away:
 * collapsed it is a round button with the time; expanded it shows the song with
 * previous/play/next and a Home button. Drag it to any corner; the corner is remembered.
 *
 * Runs only while MainActivity is paused. Visibility: with "usage access" granted it is
 * shown exactly while Zlink is the foreground app; without it, whenever the launcher
 * itself opened Zlink (EXTRA_FORCE).
 */
public class CarPlayOverlay extends Service implements NowPlaying.Listener {

    static final String CARPLAY_PACKAGE = "com.zjinnova.zlink";
    static final String EXTRA_FORCE = "force";
    private static final String PREFS = "carplay_overlay";
    private static final long POLL_MS = 1000, COLLAPSE_MS = 8000, CLOCK_MS = 5000;

    private final Handler handler = new Handler();
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private View pill, circle;
    private TextView circleHour, circleMin, time, title, artist;
    private ImageView art, playBtn;
    private NowPlaying nowPlaying;
    private boolean attached, expanded, force;
    private int corner; // 0 top-right, 1 top-left, 2 bottom-right, 3 bottom-left

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        nowPlaying = NowPlaying.get(this);
        corner = prefs().getInt("corner", 0);
        buildView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        force = intent != null && intent.getBooleanExtra(EXTRA_FORCE, false);
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_NOT_STICKY;
    }

    // ---------- Foreground app polling ----------

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String top = topPackage();
            boolean show = top == null ? force : CARPLAY_PACKAGE.equals(top);
            if (getPackageName().equals(top)) show = false;
            if (!Settings.canDrawOverlays(CarPlayOverlay.this)) show = false;
            if (show) attach(); else detach();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Foreground package via usage stats, or null when usage access is not granted. */
    private String topPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);
            if (list == null || list.isEmpty()) return null;
            UsageStats best = null;
            for (UsageStats u : list) {
                if (best == null || u.getLastTimeUsed() > best.getLastTimeUsed()) best = u;
            }
            return best == null ? null : best.getPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- View ----------

    private void buildView() {
        root = new FrameLayout(this);

        // Collapsed: round button with HH / mm
        circle = new LinearLayout(this);
        ((LinearLayout) circle).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) circle).setGravity(Gravity.CENTER);
        circle.setBackground(Ui.circle(0xE61E46DC));
        circleHour = Ui.text(this, "", 17, Color.WHITE);
        circleHour.setTypeface(Typeface.DEFAULT_BOLD);
        circleMin = Ui.text(this, "", 10, 0xFFDCE6FF);
        ((LinearLayout) circle).addView(circleHour);
        ((LinearLayout) circle).addView(circleMin);
        root.addView(circle, new FrameLayout.LayoutParams(dp(58), dp(58)));

        // Expanded: art | time + song | prev play next | home
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61E3CC8);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x40FFFFFF);
        p.setBackground(bg);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.circle(0x33FFFFFF));
        art.setImageResource(R.drawable.ic_music);
        art.setColorFilter(Color.WHITE);
        art.setPadding(dp(12), dp(12), dp(12), dp(12));
        p.addView(art, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(10), 0);
        time = Ui.text(this, "", 20, Color.WHITE);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setGravity(Gravity.START);
        title = Ui.text(this, "", 13, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        artist = Ui.text(this, "", 11, 0xFFDCE6FF);
        artist.setGravity(Gravity.START);
        texts.addView(time);
        texts.addView(title);
        texts.addView(artist);
        p.addView(texts, new LinearLayout.LayoutParams(dp(170), -2));

        p.addView(Ui.iconButton(this, R.drawable.ic_prev, 36, false, () -> { nowPlaying.skip(false); bump(); }),
                btn(36, 4));
        playBtn = Ui.iconButton(this, R.drawable.ic_play, 42, true, () -> { nowPlaying.playPause(); bump(); });
        p.addView(playBtn, btn(42, 4));
        p.addView(Ui.iconButton(this, R.drawable.ic_next, 36, false, () -> { nowPlaying.skip(true); bump(); }),
                btn(36, 4));
        p.addView(Ui.iconButton(this, R.drawable.ic_home, 40, false, this::goHome), btn(40, 10));
        pill = p;
        pill.setVisibility(View.GONE);
        root.addView(pill, new FrameLayout.LayoutParams(-2, -2));

        root.setOnTouchListener(new Drag());
    }

    private LinearLayout.LayoutParams btn(int size, int leftDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(size), dp(size));
        l.leftMargin = dp(leftDp);
        return l;
    }

    private void attach() {
        if (attached) return;
        if (!Settings.canDrawOverlays(this)) return;
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        lp = new WindowManager.LayoutParams(-2, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        placeInCorner();
        try {
            wm.addView(root, lp);
            attached = true;
        } catch (Exception e) {
            android.util.Log.e("CarPlayOverlay", "addView failed", e);
            return;
        }
        setExpanded(false);
        nowPlaying.addListener(this);
        onNowPlayingChanged();
        handler.post(clock);
    }

    private void detach() {
        if (!attached) return;
        handler.removeCallbacks(clock);
        handler.removeCallbacks(collapse);
        nowPlaying.removeListener(this);
        try { wm.removeView(root); } catch (Exception ignored) { }
        attached = false;
    }

    private void setExpanded(boolean e) {
        expanded = e;
        pill.setVisibility(e ? View.VISIBLE : View.GONE);
        circle.setVisibility(e ? View.GONE : View.VISIBLE);
        handler.removeCallbacks(collapse);
        if (e) handler.postDelayed(collapse, COLLAPSE_MS);
        root.post(this::placeInCorner);
    }

    private void bump() {
        handler.removeCallbacks(collapse);
        handler.postDelayed(collapse, COLLAPSE_MS);
    }

    private final Runnable collapse = () -> { if (attached) setExpanded(false); };

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            String h = new SimpleDateFormat("HH", Locale.getDefault()).format(now);
            String m = new SimpleDateFormat("mm", Locale.getDefault()).format(now);
            circleHour.setText(h);
            circleMin.setText(m);
            time.setText(h + ":" + m);
            handler.postDelayed(this, CLOCK_MS);
        }
    };

    private void placeInCorner() {
        if (lp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = root.getMeasuredWidth(), h = root.getMeasuredHeight();
        if (w == 0) { root.measure(0, 0); w = root.getMeasuredWidth(); h = root.getMeasuredHeight(); }
        int m = dp(16);
        boolean right = corner == 0 || corner == 2, bottom = corner >= 2;
        lp.x = right ? dm.widthPixels - w - m : m;
        lp.y = bottom ? dm.heightPixels - h - m : m + dp(28);
        if (attached) try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        detach();
    }

    /** Tap toggles; drag moves and snaps to the nearest corner. */
    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = lp.x; startY = lp.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) < dp(8)) return true;
                    moved = true;
                    lp.x = startX + (int) dx; lp.y = startY + (int) dy;
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        boolean right = e.getRawX() > dm.widthPixels / 2f;
                        boolean bottom = e.getRawY() > dm.heightPixels / 2f;
                        corner = (bottom ? 2 : 0) + (right ? 0 : 1);
                        prefs().edit().putInt("corner", corner).apply();
                        placeInCorner();
                    } else {
                        setExpanded(!expanded);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---------- Now playing ----------

    @Override
    public void onNowPlayingChanged() {
        NowPlaying.Track t = nowPlaying.track(nowPlaying.current() == null
                ? NowPlaying.Source.APP : nowPlaying.current());
        boolean has = t != null && !t.title.isEmpty();
        title.setText(has ? t.title : "Nada sonando");
        artist.setText(has ? t.artist : "");
        playBtn.setImageResource(t != null && t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        Bitmap b = t == null ? null : t.art;
        if (b != null) {
            art.setImageBitmap(b);
            art.setColorFilter(null);
            art.setPadding(0, 0, 0, 0);
        } else {
            art.setImageResource(R.drawable.ic_music);
            art.setColorFilter(Color.WHITE);
            art.setPadding(dp(12), dp(12), dp(12), dp(12));
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        detach();
        super.onDestroy();
    }
}
