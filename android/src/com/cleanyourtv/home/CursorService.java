package com.cleanyourtv.home;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The trackpad on the phone: a pointer drawn over any app, moved, clicked and scrolled from the admin panel.
 *
 * Google TV shows no pointer for injected mouse events, and only an accessibility service may tap inside other apps,
 * so this is one (the helper switches it on over adb the first time the trackpad is used). It listens on PORT for the
 * helper only: the first line must be "auth <token>" (the token comes with the helper's settings, /api/cc "cursor").
 * Then one command per line:  m dx dy  (move)   p  (where is it: answers "pos x y")   h  (hide);
 * and  c  (click)   l  (long press)   s dx dy  (scroll) as accessibility gestures, which Google TV ignores: there the
 * helper asks "p" and taps over adb instead.
 * Without adb (Wireless debugging off) the remote comes here too:  k <key>  (arrows, ok, back, home, volume, media,
 * wake, power)   o <package>  (open an app)   u <address>  (open a web address).
 * Now playing (with MediaWatch allowed):  np  answers "np {json}" (app, title, length, position…)
 * art  answers "art <base64 jpeg>" (or "art -")   seek <ms>  jumps there.
 * log  answers "log <base64 of the last events, one per line>": what this service did, for the panel's Logs.
 * It does nothing else: no events are read, nothing is collected.
 */
public class CursorService extends AccessibilityService {
    static final int PORT = 8791;
    static volatile CursorService instance;   // for the Chromecast's own server (Hub.kt)
    static volatile String front = "";        // the app in front (from window changes: its name only, nothing on screen)
    static final long HIDE_AFTER = 4000;

    final Handler ui = new Handler(Looper.getMainLooper());
    WindowManager wm;
    View pointer;
    WindowManager.LayoutParams lp;
    volatile float x, y;
    int screenW = 1920, screenH = 1080;
    boolean shown;
    ServerSocket server;
    volatile boolean running;

    final Runnable hide = () -> { if (pointer != null) { pointer.setVisibility(View.GONE); shown = false; } };

    /** The last 400 things this service did, in memory only (the panel's Logs > Chromecast). */
    static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();
    static final java.text.SimpleDateFormat HMS = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);
    static void L(String msg) {
        String line = HMS.format(new java.util.Date()) + " " + msg;
        synchronized (LOG) { LOG.addLast(line); while (LOG.size() > 400) LOG.removeFirst(); }
        android.util.Log.i("HomeTrackpad", msg);
    }
    static String logText() {
        StringBuilder b = new StringBuilder();
        synchronized (LOG) { for (String l : LOG) b.append(l).append('\n'); }
        try { return Base64.encodeToString(b.toString().getBytes("UTF-8"), Base64.NO_WRAP); } catch (Exception e) { return ""; }
    }

    @Override protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        screenW = b.width(); screenH = b.height();
        x = screenW / 2f; y = screenH / 2f;
        L("service started: screen " + screenW + "x" + screenH + ", pointer at the middle");
        pointer = new Arrow(this);
        lp = new WindowManager.LayoutParams(Arrow.W, Arrow.H, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        pointer.setVisibility(View.GONE);
        try { wm.addView(pointer, lp); } catch (Exception ignored) { }
        running = true;
        new Thread(this::serve, "trackpad").start();
        instance = this;
        Hub.start(this);         // the Chromecast's own server (port 8765): Home's settings and the phone panel
        AdbKeeper.watch(this);   // this service starts at boot: switch Wireless debugging back on
        Vpn.restore(this);       // and the VPN country that was on
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String p = event.getPackageName().toString();
            if (!p.equals("android") && !p.startsWith("com.android.systemui")) front = p;
        }
    }

    /** A command from the Chromecast's own server, as from the helper's socket (one line). */
    void cmd(String line) {
        final String[] p = line.trim().split("\\s+");
        if (p.length == 0) return;
        if (p[0].equals("u2") && p.length > 2) { ui.post(() -> openIn(p[1], p[2])); return; }
        ui.post(() -> run(p));
    }

    void openIn(String pkg, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { openUrl(url); }
    }

    static java.util.List<String> logLines() { synchronized (LOG) { return new java.util.ArrayList<>(LOG); } }
    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent i) { L("service unbound"); return super.onUnbind(i); }

    @Override public void onDestroy() {
        L("service stopped");
        if (instance == this) instance = null;
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (pointer != null) wm.removeView(pointer); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** The key the helper gave this app: kept here once learnt ("ptoken"), so it stays when Home uses the Chromecast's
     *  own server (which has no key of its own to give). */
    String token() {
        android.content.SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        try {
            JSONObject c = new JSONObject(prefs.getString("cfg", "{}"));
            JSONObject cur = c.optJSONObject("cursor");
            String t = cur == null ? "" : cur.optString("token", "");
            if (!t.isEmpty()) { if (!t.equals(prefs.getString("ptoken", ""))) prefs.edit().putString("ptoken", t).apply(); return t; }
        } catch (Exception ignored) { }
        String kept = prefs.getString("ptoken", "");
        if (!kept.isEmpty()) return kept;
        // Not seen yet (Home hasn't opened since the app was updated): ask the helper, as Home would.
        try {
            String helper = Hub.helperUrl(this);
            if (helper.isEmpty()) return "";
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(helper + "/api/cc").openConnection();
            h.setConnectTimeout(4000); h.setReadTimeout(6000);
            try (java.io.InputStream in = h.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                JSONObject cur = new JSONObject(out.toString("UTF-8")).optJSONObject("cursor");
                return cur == null ? "" : cur.optString("token", "");
            }
        } catch (Exception e) { return ""; }
    }

    void serve() {
        while (running) {
            try (ServerSocket s = new ServerSocket(PORT, 2, InetAddress.getByName("0.0.0.0"))) {
                server = s;
                while (running) {
                    Socket c = s.accept();
                    c.setTcpNoDelay(true);
                    new Thread(() -> client(c), "trackpad-client").start();
                }
            } catch (Exception e) {
                L("listening on " + PORT + " failed: " + e);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    void client(Socket c) {
        try (Socket sock = c; BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"))) {
            String first = in.readLine(), tok = token();
            String who = String.valueOf(sock.getInetAddress()).replace("/", "");
            if (first == null || tok.isEmpty() || !first.equals("auth " + tok)) {
                L("refused " + who + (tok.isEmpty() ? " (no key yet)" : " (wrong key)"));
                sock.getOutputStream().write("no\n".getBytes()); return;
            }
            L("connected: " + who);
            sock.getOutputStream().write("ok\n".getBytes());
            String line;
            while ((line = in.readLine()) != null) {
                final String[] p = line.trim().split("\\s+");
                if (p.length == 0) continue;
                if (p[0].equals("p")) {   // answered here, at once: the pointer's place in screen pixels
                    sock.getOutputStream().write(("pos " + Math.round(x) + " " + Math.round(y) + "\n").getBytes());
                    continue;
                }
                if (p[0].equals("vpnstatus")) { sock.getOutputStream().write(("vpnstatus " + vpnStatus() + "\n").getBytes("UTF-8")); continue; }
                if (p[0].equals("vpnkey")) {   // the NordLynx key (from the helper, once): kept here, never logged
                    Vpn.prefs(this).edit().putString("key", p.length > 1 ? p[1] : "").apply();
                    L("vpn: NordLynx key " + (p.length > 1 && !p[1].isEmpty() ? "saved" : "removed")); continue;
                }
                if (p[0].equals("vpnapps")) {   // the apps that go through the VPN, comma separated; nothing: all
                    Vpn.prefs(this).edit().putString("apps", p.length > 1 ? p[1] : "").apply();
                    L("vpn: apps " + (p.length > 1 ? p[1] : "(all)")); continue;
                }
                if (p[0].equals("vpn")) {   // a country code, or off
                    final String cc = p.length > 1 ? p[1] : "off";
                    new Thread(() -> Vpn.set(getApplicationContext(), cc), "vpn").start(); continue;
                }
                if (p[0].equals("log")) { sock.getOutputStream().write(("log " + logText() + "\n").getBytes()); continue; }
                if (p[0].equals("np")) { sock.getOutputStream().write(("np " + nowPlaying() + "\n").getBytes("UTF-8")); continue; }
                if (p[0].equals("art")) { sock.getOutputStream().write(("art " + art() + "\n").getBytes()); continue; }
                ui.post(() -> run(p));
            }
            L("disconnected: " + who);
        } catch (Exception e) { L("connection error: " + e); }
    }

    float num(String[] p, int i) { try { return Float.parseFloat(p[i]); } catch (Exception e) { return 0; } }

    void run(String[] p) {
        if (!p[0].equals("m") && !p[0].equals("z")) L("cmd " + String.join(" ", p));
        switch (p[0]) {
            case "m": move(num(p, 1), num(p, 2)); break;
            case "c": show(); tap(false); break;
            case "l": show(); tap(true); break;
            case "s": show(); scroll(num(p, 1), num(p, 2)); break;
            case "h": hide.run(); break;
            case "k": if (p.length > 1) key(p[1]); break;
            case "o": if (p.length > 1) open(p[1]); break;
            case "u": if (p.length > 1) openUrl(p[1]); break;
            case "seek": { MediaController m = top(); if (m != null) m.getTransportControls().seekTo((long) num(p, 1)); break; }
            default: break;
        }
    }

    /** A remote key without adb: the arrows, OK, Back and Home as accessibility actions (Android 13+), the rest
     *  through the audio service and the media session in front. */
    /** Whether this launcher's Home is in front (then keys go straight to it). */
    static boolean homeInFront() { MainActivity m = MainActivity.instance; return m != null && m.resumed; }

    void key(String k) {
        MainActivity home = MainActivity.instance;
        if (home != null && home.remoteKey(k)) return;   // Home in front: as from the TV remote
        int ga = -1;
        switch (k) {
            case "up": ga = GLOBAL_ACTION_DPAD_UP; break;
            case "down": ga = GLOBAL_ACTION_DPAD_DOWN; break;
            case "left": ga = GLOBAL_ACTION_DPAD_LEFT; break;
            case "right": ga = GLOBAL_ACTION_DPAD_RIGHT; break;
            case "ok": ga = GLOBAL_ACTION_DPAD_CENTER; break;
            case "back": ga = GLOBAL_ACTION_BACK; break;
            case "home": ga = GLOBAL_ACTION_HOME; break;
            case "power": ga = GLOBAL_ACTION_LOCK_SCREEN; break;
            default: break;
        }
        if (ga >= 0) { boolean done = performGlobalAction(ga); if (!done) L("key " + k + ": Android refused it"); return; }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // As the remote's volume key does (FLAG_FROM_KEY, hidden): only then does Android pass it on to the TV over
        // HDMI-CEC, where the Chromecast's own volume is fixed at full.
        int vf = AudioManager.FLAG_SHOW_UI | 0x1000;
        switch (k) {
            case "volup": am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, vf); break;
            case "voldown": am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, vf); break;
            case "mute": am.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.USE_DEFAULT_STREAM_TYPE, vf); break;
            case "play": case "pause": media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "next": media(am, KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "prev": media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "rew": media(am, KeyEvent.KEYCODE_MEDIA_REWIND); break;
            case "ff": media(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); break;
            case "stop": media(am, KeyEvent.KEYCODE_MEDIA_STOP); break;
            case "wake": wake(); break;
            default: break;
        }
    }

    void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    @SuppressWarnings("deprecation")
    void wake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "cyt:wake");
            w.acquire(3000);
        } catch (Exception ignored) { }
    }

    void open(String pkg) {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    String vpnStatus() { return Vpn.status(this).toString(); }

    /** The media session in front: the first one playing, else the first one paused. */
    MediaController top() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> all = msm.getActiveSessions(new ComponentName(this, MediaWatch.class));
            MediaController paused = null;
            for (MediaController m : all) {
                PlaybackState st = m.getPlaybackState();
                if (st == null) continue;
                if (st.getState() == PlaybackState.STATE_PLAYING) return m;
                if (paused == null && st.getState() == PlaybackState.STATE_PAUSED) paused = m;
            }
            return paused;
        } catch (SecurityException e) {
            return null;
        }
    }

    String nowPlaying() {
        JSONObject o = new JSONObject();
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.getActiveSessions(new ComponentName(this, MediaWatch.class));
        } catch (SecurityException e) {
            try { o.put("error", "no access"); } catch (Exception ignored) { }
            return o.toString();
        } catch (Exception ignored) { }
        try {
            MediaController m = top();
            if (m == null) return o.put("playing", false).toString();
            PlaybackState st = m.getPlaybackState();
            MediaMetadata md = m.getMetadata();
            boolean playing = st.getState() == PlaybackState.STATE_PLAYING;
            long pos = st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0)
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            o.put("playing", true).put("app", m.getPackageName()).put("state", playing ? "playing" : "paused")
             .put("position", Math.max(0, pos));
            if (md != null) {
                String title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String sub = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                if (sub == null) sub = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (sub == null) sub = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                o.put("title", title == null ? "" : title).put("subtitle", sub == null ? "" : sub)
                 .put("duration", Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION)))
                 .put("art", picture(md) != null);
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    static Bitmap picture(MediaMetadata md) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    /** The picture of what's playing, as a JPEG at most 512 px wide, in base64; "-" when there is none. */
    String art() {
        try {
            MediaController m = top();
            Bitmap b = m == null || m.getMetadata() == null ? null : picture(m.getMetadata());
            if (b == null) return "-";
            if (b.getWidth() > 512) b = Bitmap.createScaledBitmap(b, 512, Math.max(1, b.getHeight() * 512 / b.getWidth()), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "-";
        }
    }

    void show() {
        if (pointer == null) return;
        if (!shown) { pointer.setVisibility(View.VISIBLE); shown = true; }
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, HIDE_AFTER);
    }

    void move(float dx, float dy) {
        float ox = x, oy = y;
        x = Math.max(0, Math.min(screenW - 1, x + dx));
        y = Math.max(0, Math.min(screenH - 1, y + dy));
        L("move " + Math.round(dx) + "," + Math.round(dy) + ": " + Math.round(ox) + "," + Math.round(oy) + " -> " + Math.round(x) + "," + Math.round(y)
                + (shown ? "" : " (was hidden)"));
        show();
        lp.x = Math.round(x - Arrow.TIP_X); lp.y = Math.round(y - Arrow.TIP_Y);   // the tip is where it clicks
        try { wm.updateViewLayout(pointer, lp); } catch (Exception ignored) { }
    }

    void tap(boolean longPress) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, longPress ? 700 : 40)).build();
        dispatchGesture(g, null, null);
    }

    /** A swipe from the pointer: the page moves with the fingers, like scrolling on a phone. */
    void scroll(float dx, float dy) {
        float ex = Math.max(1, Math.min(screenW - 2, x + dx)), ey = Math.max(1, Math.min(screenH - 2, y + dy));
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(ex, ey);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 180)).build();
        dispatchGesture(g, null, null);
    }

    /** The pointer: a dark arrow with a white edge and a soft shadow (like a Mac's), seen on light and dark screens. */
    static final class Arrow extends View {
        static final float K = 0.7f;                          // size on a 1920x1080 screen
        static final int W = Math.round(46 * K), H = Math.round(58 * K);
        static final float TIP_X = 6 * K, TIP_Y = 5 * K;      // where the tip is in the view
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path shape = new Path();
        Arrow(Context c) {
            super(c);
            float[] pts = {0, 0, 0, 37, 9.5f, 28.5f, 15.5f, 42.5f, 22, 39.7f, 16, 26, 29, 26};   // tip at 0,0
            shape.moveTo(TIP_X + pts[0] * K, TIP_Y + pts[1] * K);
            for (int i = 2; i < pts.length; i += 2) shape.lineTo(TIP_X + pts[i] * K, TIP_Y + pts[i + 1] * K);
            shape.close();
            edge.setColor(Color.WHITE);                       // the white edge, with the shadow under all of it
            edge.setStyle(Paint.Style.FILL_AND_STROKE);
            edge.setStrokeWidth(3.5f * K);
            edge.setStrokeJoin(Paint.Join.ROUND);
            edge.setShadowLayer(3.5f * K, 0, 1.5f * K, 0x80000000);
            fill.setColor(0xFF141414);
            fill.setStyle(Paint.Style.FILL);
            setLayerType(LAYER_TYPE_SOFTWARE, null);          // for the shadow
        }
        @Override protected void onDraw(Canvas c) { c.drawPath(shape, edge); c.drawPath(shape, fill); }
    }
}
