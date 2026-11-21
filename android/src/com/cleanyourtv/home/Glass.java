package com.cleanyourtv.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

/**
 * The "Glass" look of the Classic Home (home_layout.look = "glass"): cards, buttons and the dock drawn as frosted glass
 * over the photo of the day.
 *
 * Blurring what is behind a view every frame is too much for the Chromecast's chip, and the only thing behind these
 * panels is the photo, which changes once a day. So the photo is frosted once ({@link #setWallpaper}): shrunk to an
 * eighth, blurred, its colours made a little richer. Each panel ({@link Panel}) draws the part of that frosted copy that
 * lies under it (scaled back up, smooth), then a light tint and a bright rim that catches the light at the top left.
 */
final class Glass {
    private Glass() { }

    static final int SCALE = 8;           // the frosted copy is an eighth of the screen
    static Bitmap frost;                  // null: no photo (yet); panels are a plain light tint then
    static View stage;                    // the 1920 x 1080 view the photo fills; panels find their place in it
    static View stage2;                   // the Home layouts' own 1920 x 1080 stage (NativeHome), same place and size
    static int version;                   // changes with each new photo, so panels make their shader again

    /** A new photo of the day (or null when there is none): frost it once, for every panel. */
    static void setWallpaper(Bitmap wall, int w, int h) {
        if (wall == null) { frost = null; version++; return; }
        int fw = w / SCALE, fh = h / SCALE;
        Bitmap small = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        // Like the photo's own view: scaled to cover the screen, centred.
        float s = Math.max(fw / (float) wall.getWidth(), fh / (float) wall.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((fw - wall.getWidth() * s) / 2f, (fh - wall.getHeight() * s) / 2f);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.6f);                                    // glass makes the colours behind it richer
        ColorMatrix bright = new ColorMatrix(new float[]{1.06f, 0, 0, 0, 6, 0, 1.06f, 0, 0, 6, 0, 0, 1.06f, 0, 6, 0, 0, 0, 1, 0});
        cm.postConcat(bright);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(wall, m, p);
        int[] px = new int[fw * fh];
        small.getPixels(px, 0, fw, 0, 0, fw, fh);
        for (int i = 0; i < 3; i++) boxBlur(px, fw, fh, 3);       // three box blurs: close to a smooth (Gaussian) one
        small.setPixels(px, 0, fw, 0, 0, fw, fh);
        frost = small;
        version++;
    }

    /** One horizontal and one vertical box blur of radius r, in place. */
    static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[Math.max(w, h)];
        for (int y = 0; y < h; y++) blurLine(px, y * w, 1, w, r, tmp);
        for (int x = 0; x < w; x++) blurLine(px, x, w, h, r, tmp);
    }

    private static void blurLine(int[] px, int start, int step, int n, int r, int[] tmp) {
        int sr = 0, sg = 0, sb = 0, div = 2 * r + 1;
        for (int i = -r; i <= r; i++) {
            int c = px[start + Math.max(0, Math.min(n - 1, i)) * step];
            sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
        }
        for (int i = 0; i < n; i++) {
            tmp[i] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
            int out = px[start + Math.max(0, i - r) * step], in = px[start + Math.min(n - 1, i + r + 1) * step];
            sr += ((in >> 16) & 255) - ((out >> 16) & 255);
            sg += ((in >> 8) & 255) - ((out >> 8) & 255);
            sb += (in & 255) - (out & 255);
        }
        for (int i = 0; i < n; i++) px[start + i * step] = tmp[i];
    }

    /** A view's top left corner in the stage, moves (translation) included. False if it isn't on the stage. */
    static boolean place(View v, float[] out) {
        float x = 0, y = 0;
        while (v != null && v != stage && v != stage2) {
            x += v.getLeft() + v.getTranslationX();
            y += v.getTop() + v.getTranslationY();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        out[0] = x; out[1] = y;
        return v != null;
    }

    /** A frosted glass panel: the background of a card, a button or the dock. */
    static final class Panel extends Drawable {
        final View host;
        final float radius;
        private final Paint frostP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tintP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plainP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), rim = new RectF();
        private final Matrix m = new Matrix();
        private final float[] at = new float[2];
        private BitmapShader shader;
        private int shaderVersion = -1;
        private int alpha = 255;
        private final int darken;                 // 0-255: a dark film over the frosted photo, for small text on bright photos
        private final Paint darkP = new Paint(Paint.ANTI_ALIAS_FLAG);

        Panel(View host, float radius) { this(host, radius, 0); }
        Panel(View host, float radius, int darken) {
            this.host = host;
            this.radius = radius;
            this.darken = darken;
            darkP.setColor(0xFF08101A);
            rimP.setStyle(Paint.Style.STROKE);
            rimP.setStrokeWidth(2f);
            plainP.setColor(MainActivity.white(.10));
        }

        @Override protected void onBoundsChange(Rect b) {
            r.set(b);
            rim.set(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1);
            // Lighter at the top, clearer in the middle: light falling on curved glass.
            tintP.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{MainActivity.white(.20), MainActivity.white(.06), MainActivity.white(.10)}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
            // The rim: bright at the top left where the light hits, faint along the bottom, a glint at the bottom right.
            rimP.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{MainActivity.white(.85), MainActivity.white(.18), MainActivity.white(.06), MainActivity.white(.40)}, new float[]{0f, .3f, .65f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            if (r.isEmpty()) return;
            int a = alpha;
            if (frost != null && place(host, at)) {
                if (shaderVersion != version) {
                    shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    frostP.setShader(shader);
                    shaderVersion = version;
                }
                // The frosted photo at its place on the screen: this panel shows what lies right under it.
                m.setScale(SCALE, SCALE);
                m.postTranslate(-at[0], -at[1]);
                shader.setLocalMatrix(m);
                frostP.setAlpha(a);
                c.drawRoundRect(r, radius, radius, frostP);
                if (darken > 0) { darkP.setAlpha(darken * a / 255); c.drawRoundRect(r, radius, radius, darkP); }
            } else {
                plainP.setAlpha(a * 26 / 255);
                c.drawRoundRect(r, radius, radius, plainP);
            }
            tintP.setAlpha(a);
            c.drawRoundRect(r, radius, radius, tintP);
            rimP.setAlpha(a);
            c.drawRoundRect(rim, radius - 1, radius - 1, rimP);
        }

        @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
