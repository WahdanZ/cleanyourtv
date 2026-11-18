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
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

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
 * It does nothing else: no events are read, nothing is collected.
 */
public class CursorService extends AccessibilityService {
    static final int PORT = 8791;
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

    @Override protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        screenW = b.width(); screenH = b.height();
        x = screenW / 2f; y = screenH / 2f;
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
        AdbKeeper.watch(this);   // this service starts at boot: switch Wireless debugging back on
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (pointer != null) wm.removeView(pointer); } catch (Exception ignored) { }
        super.onDestroy();
    }

    String token() {
        try {
            JSONObject c = new JSONObject(getSharedPreferences("home", MODE_PRIVATE).getString("cfg", "{}"));
            JSONObject cur = c.optJSONObject("cursor");
            String t = cur == null ? "" : cur.optString("token", "");
            if (!t.isEmpty()) return t;
        } catch (Exception ignored) { }
        // Not seen yet (Home hasn't opened since the app was updated): ask the helper, as Home would.
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(BuildConfig.HELPER + "/api/cc").openConnection();
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
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    void client(Socket c) {
        try (Socket sock = c; BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"))) {
            String first = in.readLine(), tok = token();
            if (first == null || tok.isEmpty() || !first.equals("auth " + tok)) { sock.getOutputStream().write("no\n".getBytes()); return; }
            sock.getOutputStream().write("ok\n".getBytes());
            String line;
            while ((line = in.readLine()) != null) {
                final String[] p = line.trim().split("\\s+");
                if (p.length == 0) continue;
                if (p[0].equals("p")) {   // answered here, at once: the pointer's place in screen pixels
                    sock.getOutputStream().write(("pos " + Math.round(x) + " " + Math.round(y) + "\n").getBytes());
                    continue;
                }
                ui.post(() -> run(p));
            }
        } catch (Exception ignored) { }
    }

    float num(String[] p, int i) { try { return Float.parseFloat(p[i]); } catch (Exception e) { return 0; } }

    void run(String[] p) {
        switch (p[0]) {
            case "m": move(num(p, 1), num(p, 2)); break;
            case "c": show(); tap(false); break;
            case "l": show(); tap(true); break;
            case "s": show(); scroll(num(p, 1), num(p, 2)); break;
            case "h": hide.run(); break;
            default: break;
        }
    }

    void show() {
        if (pointer == null) return;
        if (!shown) { pointer.setVisibility(View.VISIBLE); shown = true; }
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, HIDE_AFTER);
    }

    void move(float dx, float dy) {
        x = Math.max(0, Math.min(screenW - 1, x + dx));
        y = Math.max(0, Math.min(screenH - 1, y + dy));
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
