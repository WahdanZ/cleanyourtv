package com.cleanyourtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Chromecast's home screen, as a native app: the same design as the TV's launcher (web/index.html), drawn with
 * Android views. Apps come from Android; the settings (dock, rows, names), weather, quote, wallpaper and the VPN switch
 * come from the helper on the Mac (/api/cc and friends). Without the Mac it still shows the apps from its last settings.
 */
public class MainActivity extends Activity {
    static final String BASE = BuildConfig.HELPER;   // the Mac running helper/cyt_helper.py (set by build.sh)

    // The stage is 1920 x 1080 px; every number below is the TV page's.
    static final int W = 1920, H = 1080, COLS = 4;
    static final int DOCK_W = 400, DOCK_H = 225, DOCK_PITCH = 438, DOCK_LEFT = 103, DOCK_TOP = 690, BAND_PAD = 28;
    static final int TILE_W = 360, TILE_H = 202, PITCH = DOCK_PITCH, LEFT = DOCK_LEFT + (DOCK_W - TILE_W) / 2;
    static final int GRID_TOP = DOCK_TOP + DOCK_H + 2 * BAND_PAD + 40, ROW_PITCH = 270, TITLE_H = 60, FOCUS_Y = 640, RAIL_TOP = 230;
    static final String[] CC_DOCK = {"com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus"};

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(3);
    SharedPreferences prefs;
    JSONObject cfg = new JSONObject();
    volatile long helperDownUntil = 0;

    // ---------- views ----------
    FrameLayout stage, viewport, rail;
    ImageView wall, quotePic, wxIcon;
    View wallShade, gridShade, band, scrim, hero;
    FrameLayout glass;
    View glassSheen;
    TextView greeting, clock, ampm, date, heroName, quoteText, quoteBy, quoteSrc, wxTemp, wxInfo, credit, toast;
    LinearLayout pills, quoteBox, weatherBox, menuBox;
    final List<View> sectionViews = new ArrayList<>();

    // ---------- apps and layout ----------
    static class App { String id, name, vpn; boolean banner; long v; }
    static class Pos { int x, y, w, h, line, col; }
    static class Section { String title; int start, count, y; }
    List<App> apps = new ArrayList<>();
    int dockSize = 0;
    boolean rowsMode = false;
    final List<Section> sections = new ArrayList<>();
    final List<Pos> pos = new ArrayList<>();
    final List<List<Integer>> lines = new ArrayList<>();
    final List<FrameLayout> tiles = new ArrayList<>();
    final List<TextView> names = new ArrayList<>();
    final Map<String, Drawable> art = new HashMap<>();
    final Map<String, Integer> artBg = new HashMap<>();

    // ---------- focus ----------
    String area = "dock";   // dock, grid, pills, menu, move
    int index = 0, pill = 0, lastDock = 0;
    final List<View> pillViews = new ArrayList<>();
    final List<String> pillActions = new ArrayList<>();
    boolean glassPlaced = false;

    // ---------- menu ----------
    static class Item { String label, act, arg; Item(String l, String a, String g) { label = l; act = a; arg = g; } }
    final List<Item> menuItems = new ArrayList<>();
    final List<TextView> menuRows = new ArrayList<>();
    int menuIndex = 0;
    String menuKind = "tile";
    App menuApp;
    List<App> moveBackup;

    // ---------- data ----------
    String regionNow = null;
    JSONArray regionList = new JSONArray();
    String wallShown = "";
    boolean hasWallpaper = false;
    long quoteDay = -1, lastWeather = 0, lastWallpaper = 0;
    JSONArray builtInQuotes;
    boolean resumed = false;

    // =====================================================================================================
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        try { cfg = new JSONObject(prefs.getString("cfg", "{}")); } catch (Exception e) { cfg = new JSONObject(); }
        try { builtInQuotes = new JSONObject(readAsset("quotes.json")).getJSONArray("en"); } catch (Exception e) { builtInQuotes = new JSONArray(); }
        buildStage();
        loadApps();
        build();
        render();
        refreshAll(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.post(tick);
        // An app may have been installed or removed while you were away; the VPN may have changed from the TV.
        List<String> before = ids();
        loadApps();
        if (!before.equals(ids())) { build(); render(); }
        refreshAll(false);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home is open: close what is open and go back to the first tile.
        if (area.equals("menu")) closeMenu();
        if (area.equals("move")) endMove(false);
        area = "dock"; index = 0; lastDock = 0;
        render();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    List<String> ids() { List<String> l = new ArrayList<>(); for (App a : apps) l.add(a.id); return l; }

    // =====================================================================================================
    // Stage
    // =====================================================================================================
    final Map<Integer, Typeface> fonts = new HashMap<>();

    Typeface font(int weight) {
        Typeface t = fonts.get(weight);
        if (t == null) {
            try { t = new Typeface.Builder(getAssets(), "fonts/outfit.ttf").setFontVariationSettings("'wght' " + weight).build(); }
            catch (Exception e) { t = null; }
            if (t == null) t = Typeface.create("sans-serif", Typeface.NORMAL);
            fonts.put(weight, t);
        }
        return t;
    }

    TextView text(float px, int weight, int color, float spacingEm, boolean caps) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTypeface(font(weight));
        t.setTextColor(color);
        t.setLetterSpacing(spacingEm);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);                  // (this resets the caps transformation, so caps come after it)
        t.setAllCaps(caps);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static int white(double a) { return Color.argb((int) Math.round(a * 255), 255, 255, 255); }
    static int black(double a) { return Color.argb((int) Math.round(a * 255), 0, 0, 0); }

    static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x; lp.topMargin = y;
        return lp;
    }

    static GradientDrawable vertical(int[] colors, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        g.setCornerRadius(radius);
        return g;
    }

    static void round(View v, final float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
    }

    void shadow(TextView t, boolean on) { if (on) t.setShadowLayer(6, 0, 1, black(.6)); else t.setShadowLayer(0, 0, 0, 0); }

    void buildStage() {
        stage = new FrameLayout(this);
        // A calm background: dark, a little lighter at the top left.
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientCenter(0.18f, 0f);
        bg.setGradientRadius(1500);
        bg.setColors(new int[]{0xFF17171B, 0xFF050506, 0xFF000000}, new float[]{0f, .6f, 1f});
        stage.setBackground(bg);

        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setAlpha(0f);
        stage.addView(wall, new FrameLayout.LayoutParams(W, H));

        // Shades over the wallpaper: darker at the top, on the left behind the quote, and at the bottom behind the tiles.
        GradientDrawable s1 = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0});
        s1.setColors(new int[]{black(.62), black(.2), black(.18), black(.78), black(.9)}, new float[]{0f, .26f, .5f, .82f, 1f});
        GradientDrawable s2 = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0});
        s2.setColors(new int[]{black(.5), black(0), black(0)}, new float[]{0f, .62f, 1f});
        GradientDrawable s3 = new GradientDrawable();
        s3.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        s3.setGradientCenter(1f, .34f);
        s3.setGradientRadius(620);
        s3.setColors(new int[]{black(.55), black(0)});
        wallShade = new View(this);
        wallShade.setBackground(new LayerDrawable(new Drawable[]{s1, s2, s3}));
        wallShade.setAlpha(0f);
        stage.addView(wallShade, new FrameLayout.LayoutParams(W, H));
        gridShade = new View(this);
        gridShade.setBackgroundColor(black(.55));
        gridShade.setAlpha(0f);
        stage.addView(gridShade, new FrameLayout.LayoutParams(W, H));

        // Everything under the header scrolls in one rail, clipped under the header.
        viewport = new FrameLayout(this);
        viewport.setClipChildren(true);
        stage.addView(viewport, at(0, RAIL_TOP, W, H - RAIL_TOP));
        rail = new FrameLayout(this);
        rail.setClipChildren(false);
        rail.setTranslationY(-RAIL_TOP);
        viewport.addView(rail, at(0, 0, W, 6000));

        buildQuote();
        buildWeather();
        credit = text(24, 400, white(.55), 0, false);
        credit.setGravity(Gravity.END);
        credit.setMaxWidth(640);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.rightMargin = 103; cl.topMargin = 432;
        credit.setVisibility(View.GONE);
        rail.addView(credit, cl);

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        heroName = text(104, 300, Color.WHITE, -.02f, false);
        h.addView(heroName);
        TextView hint = text(26, 400, white(.5), .04f, false);
        hint.setText("OK to open  ·  hold OK for options");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMargins(6, -4, 0, 0);
        h.addView(hint, hl);
        hero = h;
        rail.addView(hero, at(108, 476, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        band = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setCornerRadius(32 + BAND_PAD);
        bd.setColor(Color.argb(217, 20, 20, 22));
        bd.setStroke(2, white(.12));
        band.setBackground(bd);
        rail.addView(band, at(0, 0, 0, 0));

        glass = new FrameLayout(this);
        GradientDrawable gd = vertical(new int[]{white(.24), white(.07)}, 36);
        gd.setStroke(3, white(.8));
        glass.setBackground(gd);
        glassSheen = new View(this);
        GradientDrawable sh = vertical(new int[]{white(.4), white(0)}, 0);
        sh.setCornerRadii(new float[]{33, 33, 33, 33, 0, 0, 0, 0});
        glassSheen.setBackground(sh);
        glass.addView(glassSheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10));
        glass.setAlpha(0f);
        glass.setTranslationZ(4);
        glass.setOutlineProvider(null);   // no shadow under the glass
        rail.addView(glass, at(0, 0, 10, 10));

        buildHeader();

        scrim = new View(this);
        scrim.setBackgroundColor(black(.6));
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        stage.addView(scrim, new FrameLayout.LayoutParams(W, H));
        menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setPadding(16, 16, 16, 16);
        GradientDrawable mb = vertical(new int[]{0xFF2A2A2E, 0xFF161618}, 40);
        mb.setStroke(2, white(.2));
        menuBox.setBackground(mb);
        menuBox.setVisibility(View.GONE);
        stage.addView(menuBox, new FrameLayout.LayoutParams(920, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        toast = text(28, 400, Color.WHITE, 0, false);
        toast.setPadding(40, 22, 40, 22);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(40);
        tb.setColor(Color.argb(235, 30, 30, 34));
        tb.setStroke(2, white(.15));
        toast.setBackground(tb);
        toast.setAlpha(0f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = 60;
        stage.addView(toast, tl);

        // Fit the 1920 x 1080 stage to the screen, whatever its size.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(stage, new FrameLayout.LayoutParams(W, H));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = Math.min(dm.widthPixels / (float) W, dm.heightPixels / (float) H);
        stage.setPivotX(0); stage.setPivotY(0);
        stage.setScaleX(scale); stage.setScaleY(scale);
        setContentView(root);
    }

    void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(24, 500, white(.55), .22f, true);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.leftMargin = 8;
        left.addView(greeting, gl);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        clock = text(104, 300, Color.WHITE, -.04f, false);
        row.addView(clock);
        ampm = text(30, 500, white(.7), .08f, true);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.setMargins(12, 0, 0, 10);
        row.addView(ampm, al);
        View div = new View(this);
        div.setBackgroundColor(white(.2));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(2, 52);
        dl.setMargins(32, 0, 32, 8);
        row.addView(div, dl);
        date = text(28, 400, white(.65), .14f, true);
        LinearLayout.LayoutParams dtl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtl.bottomMargin = 12;
        row.addView(date, dtl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = 6;
        left.addView(row, rl);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.bottomMargin = 8;
        header.addView(pills, pl);
        stage.addView(header, at(112, 52, W - 224, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuildPills();
    }

    void buildQuote() {
        quoteBox = new LinearLayout(this);
        quoteBox.setOrientation(LinearLayout.HORIZONTAL);
        quoteBox.setGravity(Gravity.CENTER_VERTICAL);
        quotePic = new ImageView(this);
        quotePic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        quotePic.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        quotePic.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(3, white(.22));
        quotePic.setForeground(ring);
        quotePic.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(116, 116);
        pl.rightMargin = 34;
        quoteBox.addView(quotePic, pl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        quoteText = new TextView(this);
        quoteText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 38);
        quoteText.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        quoteText.setTextColor(white(.93));
        quoteText.setLineSpacing(0, 1.12f);
        quoteText.setMaxLines(3);
        quoteText.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(quoteText, new LinearLayout.LayoutParams(1120, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.BOTTOM);
        quoteBy = text(24, 500, white(.55), .14f, true);
        meta.addView(quoteBy);
        quoteSrc = text(24, 400, white(.3), 0, false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = 18;
        meta.addView(quoteSrc, sl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = 14;
        col.addView(meta, ml);
        quoteBox.addView(col);
        quoteBox.setVisibility(View.GONE);
        rail.addView(quoteBox, at(108, 300, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void buildWeather() {
        weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setGravity(Gravity.END);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = new ImageView(this);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(76, 76);
        il.rightMargin = 18;
        top.addView(wxIcon, il);
        wxTemp = text(88, 300, Color.WHITE, -.03f, false);
        top.addView(wxTemp);
        weatherBox.addView(top);
        wxInfo = text(26, 400, white(.55), .04f, false);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = -10;
        weatherBox.addView(wxInfo, wl);
        weatherBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.rightMargin = 103; lp.topMargin = 296;
        rail.addView(weatherBox, lp);
    }

    // ---------- pills: Region (when the VPN switch is there) and Settings ----------
    void rebuildPills() {
        pills.removeAllViews();
        pillViews.clear(); pillActions.clear();
        if (cfg.optBoolean("region") && regionNow != null) addPill(R.drawable.ic_globe, regionLabel(), "region");
        addPill(R.drawable.ic_settings, "Settings", "settings");
        if (pill >= pillViews.size()) pill = pillViews.size() - 1;
    }

    void addPill(int icon, String label, String action) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(24, 0, 28, 0);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(34, 34));
        TextView t = text(27, 500, Color.WHITE, 0, false);
        t.setText(label);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = 12;
        p.addView(t, tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72);
        if (!pillViews.isEmpty()) lp.leftMargin = 16;
        pills.addView(p, lp);
        pillViews.add(p); pillActions.add(action);
        stylePill(p, false);
    }

    void stylePill(View v, boolean on) {
        LinearLayout p = (LinearLayout) v;
        GradientDrawable g;
        if (on) { g = new GradientDrawable(); g.setColor(white(.96)); g.setCornerRadius(36); }
        else { g = vertical(new int[]{white(.18), white(.08)}, 36); g.setStroke(2, white(.22)); }
        p.setBackground(g);
        int c = on ? 0xFF111111 : Color.WHITE;
        ((ImageView) p.getChildAt(0)).setColorFilter(c);
        ((TextView) p.getChildAt(1)).setTextColor(c);
        p.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(200).start();
    }

    String regionLabel() {
        for (int i = 0; i < regionList.length(); i++) {
            JSONObject r = regionList.optJSONObject(i);
            if (r != null && r.optString("id").equals(regionNow)) return r.optString("name").replaceAll("\\s*\\(.*\\)$", "");
        }
        return regionNow == null ? "" : regionNow.toUpperCase(Locale.ROOT);
    }

    // =====================================================================================================
    // Apps: Android's TV apps, arranged by the helper's settings (cached for when the Mac is away)
    // =====================================================================================================
    Set<String> removed() { return new HashSet<>(prefs.getStringSet("removed", Collections.<String>emptySet())); }

    static List<String> strings(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    void loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> hide = new HashSet<>(strings(cfg.optJSONArray("hidden")));
        hide.addAll(removed());
        JSONObject rename = cfg.optJSONObject("rename"), vpn = cfg.optJSONObject("app_vpn");
        List<App> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
        // (Almost) no TV apps (an emulator or a phone): the ordinary launcher apps instead.
        if (found.size() < 2) found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hide.contains(pkg) || !seen.add(pkg)) continue;
            App a = new App();
            a.id = pkg;
            a.name = rename != null && rename.has(pkg) ? rename.optString(pkg) : String.valueOf(ri.loadLabel(pm));
            a.vpn = vpn != null && vpn.has(pkg) ? vpn.optString(pkg) : null;
            a.banner = ri.activityInfo.banner != 0 || ri.activityInfo.applicationInfo.banner != 0;
            try { a.v = pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (Exception ignored) { }
            list.add(a);
        }
        // The dock first (your own order from Move, else the helper's, else the usual four), then the rows.
        List<String> dock = strings(jsonArray(prefs.getString("dock", null)));
        if (dock.isEmpty()) dock = strings(cfg.optJSONArray("dock"));
        if (dock.isEmpty()) { dock = new ArrayList<>(); Collections.addAll(dock, CC_DOCK); }
        List<App> docked = new ArrayList<>(), rest = new ArrayList<>();
        for (String id : dock) for (App a : list) if (a.id.equals(id) && docked.size() < COLS) docked.add(a);
        for (App a : list) if (!docked.contains(a)) rest.add(a);
        Collections.sort(rest, (x, y) -> x.name.compareToIgnoreCase(y.name));
        dockSize = docked.size();
        List<App> ordered = new ArrayList<>(docked);
        sections.clear();
        JSONArray rows = cfg.optJSONArray("rows");
        rowsMode = rows != null && rows.length() > 0;
        if (rows != null) for (int r = 0; r < rows.length(); r++) {
            JSONObject row = rows.optJSONObject(r);
            if (row == null) continue;
            List<App> picked = new ArrayList<>();
            for (String id : strings(row.optJSONArray("apps")))
                for (int k = 0; k < rest.size(); k++) if (rest.get(k).id.equals(id)) { picked.add(rest.remove(k)); break; }
            if (picked.isEmpty()) continue;
            Section s = new Section();
            s.title = row.optString("title", ""); s.start = ordered.size(); s.count = picked.size();
            sections.add(s);
            ordered.addAll(picked);
        }
        if (!rest.isEmpty()) {
            Section s = new Section();
            s.title = rowsMode ? "More" : ""; s.start = ordered.size(); s.count = rest.size();
            sections.add(s);
            ordered.addAll(rest);
        }
        apps = ordered;
        if (index >= apps.size()) index = Math.max(0, apps.size() - 1);
    }

    static JSONArray jsonArray(String s) {
        if (s == null) return null;
        try { return new JSONArray(s); } catch (Exception e) { return null; }
    }

    // Every tile gets a place and a line: line 0 is the dock, then each section's tiles wrap in lines of four.
    void layout() {
        pos.clear(); lines.clear();
        lines.add(new ArrayList<Integer>());
        for (int i = 0; i < dockSize; i++) {
            Pos p = new Pos();
            p.x = DOCK_LEFT + i * DOCK_PITCH; p.y = DOCK_TOP + BAND_PAD; p.w = DOCK_W; p.h = DOCK_H; p.line = 0; p.col = i;
            pos.add(p); lines.get(0).add(i);
        }
        int y = GRID_TOP;
        for (Section s : sections) {
            if (!s.title.isEmpty()) { s.y = y; y += TITLE_H; }
            for (int k = 0; k < s.count; k++) {
                if (k % COLS == 0) { if (k > 0) y += ROW_PITCH; lines.add(new ArrayList<Integer>()); }
                Pos p = new Pos();
                p.x = LEFT + (k % COLS) * PITCH; p.y = y; p.w = TILE_W; p.h = TILE_H; p.line = lines.size() - 1; p.col = k % COLS;
                pos.add(p); lines.get(p.line).add(s.start + k);
            }
            y += ROW_PITCH;
        }
    }

    void build() {
        layout();
        for (View v : tiles) rail.removeView(v);
        for (View v : names) rail.removeView(v);
        for (View v : sectionViews) rail.removeView(v);
        tiles.clear(); names.clear(); sectionViews.clear();
        for (Section s : sections) {
            if (s.title.isEmpty()) continue;
            TextView t = text(26, 500, white(.5), .16f, true);
            t.setText(s.title);
            sectionViews.add(t);
            rail.addView(t, at(LEFT + 6, s.y, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        int dock = Math.min(dockSize, apps.size());
        FrameLayout.LayoutParams bl = at(DOCK_LEFT - BAND_PAD - 2, DOCK_TOP, dock > 0 ? (dock - 1) * DOCK_PITCH + DOCK_W + 2 * BAND_PAD + 4 : 0, DOCK_H + 2 * BAND_PAD);
        band.setLayoutParams(bl);
        int glassAt = rail.indexOfChild(glass);
        for (int i = 0; i < apps.size(); i++) {
            App app = apps.get(i);
            Pos p = pos.get(i);
            FrameLayout t = new FrameLayout(this);
            t.setBackgroundColor(0xFF1C1C1E);
            round(t, 32);
            ImageView img = new ImageView(this);
            img.setScaleType(app.banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            t.addView(img, app.banner ? new FrameLayout.LayoutParams(p.w, p.h)
                    : new FrameLayout.LayoutParams(p.h, p.h, Gravity.CENTER));
            TextView label = text(30, 500, Color.WHITE, 0, false);
            label.setText(app.name);
            label.setGravity(Gravity.CENTER);
            t.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.setPivotX(p.w / 2f); t.setPivotY(p.h / 2f);
            rail.addView(t, glassAt++, at(p.x, p.y, p.w, p.h));
            tiles.add(t);
            showArt(app, t);

            TextView n = text(28, 500, white(.95), .02f, false);
            n.setText(app.name);
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            n.setAlpha(0f);
            n.setTranslationY(-12);
            rail.addView(n, glassAt++, at(p.x, p.y + p.h + 22, p.w, ViewGroup.LayoutParams.WRAP_CONTENT));
            names.add(n);
        }
        glassPlaced = false;
    }

    // The app's TV banner (16:9, fills the tile), or its icon on its own edge colour.
    void showArt(final App app, final FrameLayout tile) {
        final String key = app.id + "@" + app.v;
        Drawable d = art.get(key);
        if (d != null) { paintArt(app, tile, d, artBg.get(key)); return; }
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Drawable got = null;
            Integer bg = null;
            try {
                Intent li = pm.getLeanbackLaunchIntentForPackage(app.id);
                ComponentName cn = li == null ? null : li.getComponent();
                if (app.banner) {
                    if (cn != null) got = pm.getActivityBanner(cn);
                    if (got == null) got = pm.getApplicationBanner(app.id);
                }
                if (got == null) {
                    got = cn != null ? pm.getActivityIcon(cn) : pm.getApplicationIcon(app.id);
                    bg = edgeColour(got);
                }
            } catch (Exception ignored) { }
            final Drawable fd = got;
            final Integer fbg = bg;
            if (fd == null) return;
            ui.post(() -> {
                art.put(key, fd);
                if (fbg != null) artBg.put(key, fbg);
                if (tile.getParent() != null) paintArt(app, tile, fd, fbg);
            });
        });
    }

    void paintArt(App app, FrameLayout tile, Drawable d, Integer bg) {
        ((ImageView) tile.getChildAt(0)).setImageDrawable(d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d);
        tile.getChildAt(1).setVisibility(View.GONE);
        if (bg != null) tile.setBackgroundColor(bg);
    }

    static Integer edgeColour(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Drawable c = d.getConstantState() != null ? d.getConstantState().newDrawable() : d;
            c.setBounds(0, 0, 24, 24);
            c.draw(new Canvas(b));
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 24; y++) for (int x : new int[]{0, 23}) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 200) continue;
                r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++;
            }
            b.recycle();
            return n > 20 ? Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================================================
    // Focus
    // =====================================================================================================
    boolean onTop() { return area.equals("pills") || (area.equals("menu") && !menuKind.equals("tile")); }
    App focused() { return onTop() || index >= apps.size() ? null : apps.get(index); }
    final DecelerateInterpolator decel = new DecelerateInterpolator(2f);

    void render() {
        boolean top = onTop();
        for (int i = 0; i < pillViews.size(); i++) stylePill(pillViews.get(i), top && i == pill);
        for (int i = 0; i < tiles.size(); i++) {
            boolean on = !top && i == index;
            boolean lift = on && area.equals("move");
            View t = tiles.get(i);
            float s = lift ? 1.16f : on ? 1.1f : 1f;
            if (t.getScaleX() != s || t.getTranslationY() != (lift ? -14 : 0))
                t.animate().scaleX(s).scaleY(s).translationY(lift ? -14 : 0).setDuration(280).setInterpolator(new OvershootInterpolator(1.4f)).start();
            t.setTranslationZ(on ? 2 : 0);
        }
        for (int i = 0; i < names.size(); i++) {
            boolean show = !top && i == index && i >= dockSize;
            View n = names.get(i);
            n.animate().alpha(show ? 1f : 0f).translationY(show ? 0 : -12).setDuration(250).start();
        }
        boolean inDock = !top && index < dockSize;
        Pos p = index < pos.size() ? pos.get(index) : null;
        int shift = top || inDock || p == null ? 0 : FOCUS_Y - p.y;
        rail.animate().translationY(-RAIL_TOP + shift).setDuration(500).setInterpolator(decel).start();
        App app = focused();
        if (inDock && app != null) heroName.setText(app.name);
        hero.animate().alpha(inDock ? 1f : 0f).setDuration(300).start();
        gridShade.animate().alpha(hasWallpaper && !inDock && !top ? 1f : 0f).setDuration(600).start();
        if (app != null && p != null && !area.equals("move")) glassTo(p);
        else glass.animate().alpha(0f).setDuration(200).start();
    }

    // The glass highlight, on a slightly springy move.
    void glassTo(Pos p) {
        int gw = Math.round(p.w * 1.1f + 24), gh = Math.round(p.h * 1.1f + 24);
        float x = p.x + p.w / 2f - gw / 2f, y = p.y + p.h / 2f - gh / 2f;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) glass.getLayoutParams();
        if (lp.width != gw || lp.height != gh) {
            lp.width = gw; lp.height = gh; lp.leftMargin = 0; lp.topMargin = 0;
            glass.setLayoutParams(lp);
            FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) glassSheen.getLayoutParams();
            sl.height = Math.round(gh * .45f);
            glassSheen.setLayoutParams(sl);
        }
        if (!glassPlaced) {
            glass.setTranslationX(x); glass.setTranslationY(y);
            glassPlaced = true;
        } else {
            glass.animate().translationX(x).translationY(y).setDuration(420).setInterpolator(new OvershootInterpolator(.9f)).start();
        }
        glass.animate().alpha(1f).setDuration(200).start();
    }

    void move(int dx, int dy) {
        int n = apps.size(), dock = Math.min(dockSize, n);
        if (area.equals("pills")) {
            if (dx != 0) pill = Math.max(0, Math.min(pillViews.size() - 1, pill + dx));
            if (dy > 0 && n > 0) {
                area = "dock";
                index = dock > 0 ? Math.min(lastDock, dock - 1) : (lines.size() > 1 ? lines.get(1).get(0) : 0);
            }
            render();
            return;
        }
        if (index >= pos.size()) { render(); return; }
        Pos p = pos.get(index);
        if (dx != 0 && index + dx >= 0 && index + dx < pos.size() && pos.get(index + dx).line == p.line) index += dx;
        if (dy < 0 && p.line == 0) { lastDock = index; area = "pills"; render(); return; }
        else if (dy != 0) {
            int l = p.line + dy;
            if (l >= 0 && l < lines.size() && !lines.get(l).isEmpty()) {
                if (p.line == 0) lastDock = index;
                List<Integer> target = lines.get(l);
                index = target.get(Math.min(p.col, target.size() - 1));
            }
        }
        area = index < dockSize ? "dock" : "grid";
        render();
    }

    // =====================================================================================================
    // Keys. OK opens; holding OK (or Menu) opens the tile menu; Back closes or returns to the dock.
    // =====================================================================================================
    boolean okPending = false;
    final Runnable longPress = () -> {
        if (okPending && (area.equals("dock") || area.equals("grid"))) { okPending = false; openTileMenu(); }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) arrow(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) arrow(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) arrow(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) arrow(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && e.getRepeatCount() == 0) { okPending = true; ui.postDelayed(longPress, 600); }
                else if (up && okPending) { okPending = false; ui.removeCallbacks(longPress); activate(); }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up && (area.equals("dock") || area.equals("grid"))) openTileMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (up) back();
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    void arrow(int dx, int dy) {
        if (area.equals("menu")) {
            if (dy != 0) { menuIndex = Math.max(0, Math.min(menuItems.size() - 1, menuIndex + dy)); renderMenu(); }
            return;
        }
        if (area.equals("move")) { moveKey(dx); return; }
        move(dx, dy);
    }

    void back() {
        if (area.equals("menu")) { closeMenu(); return; }
        if (area.equals("move")) { endMove(false); return; }
        if (area.equals("pills") || index >= dockSize) {
            area = "dock";
            index = Math.max(0, Math.min(lastDock, dockSize - 1));
            render();
        }
    }

    void activate() {
        if (area.equals("menu")) { menuChoose(); return; }
        if (area.equals("move")) { endMove(true); return; }
        if (area.equals("pills")) {
            String a = pillActions.get(pill);
            if (a.equals("region")) openRegionMenu();
            else startSafely(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
        App app = focused();
        if (app == null) return;
        final View t = tiles.get(index);
        t.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).withEndAction(this::render).start();
        open(app);
    }

    // The helper switches the VPN first when the app has a country (OSN+ -> UAE), or back after one; then it opens.
    void open(final App app) {
        String to = null;
        if (app.vpn != null && regionNow != null && !regionNow.equals(app.vpn)) to = regionName(app.vpn);
        if (to != null) toast("Switching the VPN to " + to + " for " + app.name + "…");
        if (!helperUp()) { launch(app); return; }
        final boolean announced = to != null;
        final boolean[] done = {false};
        ui.postDelayed(() -> { if (!done[0] && !announced) toast("Switching the VPN back first…"); }, 1500);
        io.execute(() -> {
            try { post("/api/cc-open", new JSONObject().put("appId", app.id).toString(), 90_000); } catch (Exception ignored) { }
            ui.post(() -> { done[0] = true; launch(app); refreshRegion(); });
        });
    }

    void launch(App app) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(app.id);
        if (i == null) i = pm.getLaunchIntentForPackage(app.id);
        if (i == null || !startSafely(i)) toast("Could not open " + app.name);
    }

    boolean startSafely(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (Exception e) { return false; }
    }

    String regionName(String id) {
        for (int i = 0; i < regionList.length(); i++) {
            JSONObject r = regionList.optJSONObject(i);
            if (r != null && r.optString("id").equals(id)) return r.optString("name");
        }
        return id.toUpperCase(Locale.ROOT);
    }

    // =====================================================================================================
    // Menus: the tile menu and the Region menu, a glass panel in the middle
    // =====================================================================================================
    void showMenu(String title, List<Item> items) {
        menuItems.clear(); menuItems.addAll(items);
        menuRows.clear();
        menuBox.removeAllViews();
        TextView t = text(26, 500, white(.55), .14f, true);
        t.setText(title);
        t.setPadding(32, 18, 32, 14);
        menuBox.addView(t);
        for (Item it : items) {
            TextView r = text(32, 400, white(.88), 0, false);
            r.setText(it.label);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(32, 0, 32, 0);
            menuBox.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 84));
            menuRows.add(r);
        }
        menuIndex = 0;
        area = "menu";
        scrim.setVisibility(View.VISIBLE);
        menuBox.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(200).start();
        menuBox.setAlpha(0f);
        menuBox.setTranslationY(20);
        menuBox.animate().alpha(1f).translationY(0).setDuration(200).start();
        renderMenu();
        render();
    }

    void renderMenu() {
        for (int i = 0; i < menuRows.size(); i++) {
            TextView r = menuRows.get(i);
            boolean on = i == menuIndex;
            if (on) { GradientDrawable g = new GradientDrawable(); g.setColor(white(.96)); g.setCornerRadius(28); r.setBackground(g); }
            else r.setBackground(null);
            r.setTextColor(on ? 0xFF111111 : white(.88));
        }
    }

    void closeMenu() {
        area = !menuKind.equals("tile") ? "pills" : index < dockSize ? "dock" : "grid";
        menuKind = "tile";
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        menuBox.setVisibility(View.GONE);
        render();
    }

    void openTileMenu() {
        App app = focused();
        if (app == null) return;
        menuApp = app; menuKind = "tile";
        List<Item> items = new ArrayList<>();
        if (index < dockSize) items.add(new Item("Move", "move", null));
        items.add(new Item("Remove from Home", "remove", null));
        if (prefs.getString("dock", null) != null) items.add(new Item("Reset the order", "reset-order", null));
        int n = removed().size();
        if (n > 0) items.add(new Item("Restore removed apps (" + n + ")", "restore", null));
        items.add(new Item("App info", "info", null));
        items.add(new Item("Cancel", "cancel", null));
        showMenu(app.name, items);
    }

    void openRegionMenu() {
        menuKind = "region";
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < regionList.length(); i++) {
            JSONObject r = regionList.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id");
            items.add(new Item(r.optString("name") + (id.equals(regionNow) ? "  ✓" : ""), "region", id));
        }
        items.add(new Item("Cancel", "cancel", null));
        showMenu("Streaming region", items);
    }

    void menuChoose() {
        Item it = menuItems.get(menuIndex);
        App app = menuApp;
        closeMenu();
        switch (it.act) {
            case "move": startMove(); break;
            case "region": setRegion(it.arg); break;
            case "remove": {
                Set<String> r = removed(); r.add(app.id);
                prefs.edit().putStringSet("removed", r).apply();
                toast(app.name + " removed from Home. It is still installed on the Chromecast.");
                reload();
                break;
            }
            case "restore": prefs.edit().remove("removed").apply(); reload(); break;
            case "reset-order": prefs.edit().remove("dock").apply(); toast("Back to the original order."); reload(); break;
            case "info": startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.id))); break;
            default: break;
        }
    }

    void reload() { loadApps(); build(); area = index < dockSize ? "dock" : "grid"; render(); }

    // Move: the dock's tiles swap places with left and right; OK keeps the order, Back puts it back.
    void startMove() {
        moveBackup = new ArrayList<>(apps);
        area = "move";
        render();
        toast("Use the arrows to move " + apps.get(index).name + ", then OK to place it. Back cancels.");
    }

    void moveKey(int dx) {
        int j = index + dx;
        if (dx == 0 || j < 0 || j >= dockSize) return;
        Collections.swap(apps, index, j);
        index = j;
        build();
        render();
    }

    void endMove(boolean keep) {
        if (!keep) apps = moveBackup;
        else {
            JSONArray d = new JSONArray();
            for (int i = 0; i < dockSize; i++) d.put(apps.get(i).id);
            prefs.edit().putString("dock", d.toString()).apply();
            toast("Saved. Your tiles stay in this order.");
        }
        area = "dock";
        build();
        render();
    }

    void setRegion(final String id) {
        toast("Switching the VPN… (up to 20 seconds)");
        final String name = regionName(id);
        io.execute(() -> {
            String msg;
            try {
                JSONObject r = new JSONObject(post("/api/region", new JSONObject().put("region", id).toString(), 90_000));
                if (r.optBoolean("ok")) {
                    regionNow = r.optString("region", id);
                    msg = name.replaceAll("\\s*\\(.*\\)$", "") + ": done. Close and reopen Netflix or Disney+ to see it.";
                } else msg = "Could not switch: " + r.optString("error", "no answer");
            } catch (Exception e) {
                msg = "Cannot reach the Mac, so the VPN did not change";
            }
            final String m = msg;
            ui.post(() -> { rebuildPills(); render(); toast(m); });
        });
    }

    final Runnable hideToast = () -> toast.animate().alpha(0f).setDuration(300).start();

    void toast(String msg) {
        toast.setText(msg);
        toast.animate().alpha(1f).setDuration(300).start();
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 3500);
    }

    // =====================================================================================================
    // Clock, greeting, date
    // =====================================================================================================
    int lastMinute = -1;
    static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar c = Calendar.getInstance();
            int m = c.get(Calendar.MINUTE);
            if (m != lastMinute) {
                lastMinute = m;
                int h = c.get(Calendar.HOUR_OF_DAY);
                String mm = String.format(Locale.ROOT, "%02d", m);
                if (cfg.optBoolean("clock_24h")) { clock.setText(String.format(Locale.ROOT, "%02d", h) + ":" + mm); ampm.setText(""); }
                else { clock.setText(((h % 12) == 0 ? 12 : h % 12) + ":" + mm); ampm.setText(h < 12 ? "am" : "pm"); }
                ampm.setVisibility(cfg.optBoolean("clock_24h") ? View.GONE : View.VISIBLE);
                date.setText(DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + "  " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]);
                String g = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
                String name = cfg.optString("greeting_name", "");
                greeting.setText(name.isEmpty() ? g : g + ", " + name);
                showQuote();   // a new one at midnight
                long now = System.currentTimeMillis();
                if (now - lastWeather > 15 * 60_000) refreshWeather();
                if (now - lastWallpaper > 60 * 60_000) refreshWallpaper();
            }
            if (resumed) ui.postDelayed(this, 1000);
        }
    };

    // =====================================================================================================
    // Data from the helper
    // =====================================================================================================
    boolean helperUp() { return System.currentTimeMillis() > helperDownUntil; }

    String get(String path, int readTimeout) throws IOException { return new String(fetch(path, null, readTimeout), StandardCharsets.UTF_8); }
    String post(String path, String json, int readTimeout) throws IOException { return new String(fetch(path, json, readTimeout), StandardCharsets.UTF_8); }

    byte[] fetch(String path, String body, int readTimeout) throws IOException {
        if (!helperUp()) throw new IOException("helper away");
        HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("http") ? path : BASE + path).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(readTimeout);
        c.setUseCaches(false);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) try (InputStream s = in) { byte[] b = new byte[16384]; int n; while ((n = s.read(b)) > 0) out.write(b, 0, n); }
            if (code >= 400 && body == null) throw new IOException("HTTP " + code);
            return out.toByteArray();
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            if (!(e instanceof java.net.SocketTimeoutException) || body == null) helperDownUntil = System.currentTimeMillis() + 30_000;
            throw e;
        } finally {
            c.disconnect();
        }
    }

    void refreshAll(boolean first) {
        io.execute(() -> {
            helperDownUntil = 0;   // try the Mac again
            try {
                JSONObject c = new JSONObject(get("/api/cc", 8000));
                prefs.edit().putString("cfg", c.toString()).apply();
                ui.post(() -> {
                    boolean changed = !c.toString().equals(cfg.toString());
                    cfg = c;
                    if (changed || first) { lastMinute = -1; loadApps(); build(); render(); tick.run(); }
                    quoteDay = -1; showQuote();
                    refreshWeather(); refreshWallpaper(); refreshRegion();
                });
            } catch (Exception e) {
                // The Mac is away (asleep, or the helper restarting): its last settings stay; try again in a minute.
                ui.post(() -> { quoteDay = -1; showQuote(); });
                ui.removeCallbacks(retryHelper);
                ui.postDelayed(retryHelper, 60_000);
            }
        });
    }

    final Runnable retryHelper = () -> { if (resumed) refreshAll(false); };

    void refreshRegion() {
        if (!cfg.optBoolean("region")) { regionNow = null; rebuildPills(); render(); return; }
        io.execute(() -> {
            String now = null;
            JSONArray list = new JSONArray();
            try {
                JSONObject r = new JSONObject(get("/api/region", 15_000));
                now = r.isNull("region") ? null : r.optString("region", null);
                list = r.optJSONArray("regions") != null ? r.optJSONArray("regions") : list;
            } catch (Exception ignored) { }
            final String fn = now;
            final JSONArray fl = list;
            ui.post(() -> {
                boolean same = fn == null ? regionNow == null : fn.equals(regionNow);
                regionNow = fn; regionList = fl;
                if (!same || pillViews.size() != (fn != null ? 2 : 1)) { rebuildPills(); render(); }
            });
        });
    }

    // ---------- weather (Open-Meteo, through the helper) ----------
    static final int[][] WX_CODES = {{0}, {1, 2}, {3}, {45, 48}, {51, 53, 55, 56, 57}, {61, 63, 65, 66, 67, 80, 81, 82}, {71, 73, 75, 77, 85, 86}, {95, 96, 99}};
    static final String[] WX_LABEL = {"Clear", "Partly cloudy", "Cloudy", "Fog", "Drizzle", "Rain", "Snow", "Thunderstorm"};
    static final int[] WX_ICON = {R.drawable.wx_sun, R.drawable.wx_part, R.drawable.wx_cloud, R.drawable.wx_fog, R.drawable.wx_rain, R.drawable.wx_rain, R.drawable.wx_snow, R.drawable.wx_storm};

    void refreshWeather() {
        lastWeather = System.currentTimeMillis();
        if (!cfg.optBoolean("weather")) { weatherBox.setVisibility(View.GONE); return; }
        io.execute(() -> {
            JSONObject w = null;
            try { w = new JSONObject(get("/api/weather", 15_000)); } catch (Exception ignored) { }
            final JSONObject fw = w;
            ui.post(() -> {
                if (fw == null || !fw.has("temp")) { weatherBox.setVisibility(View.GONE); return; }
                int code = fw.optInt("code"), k = 2;
                for (int i = 0; i < WX_CODES.length; i++) for (int c : WX_CODES[i]) if (c == code) k = i;
                int icon = WX_ICON[k];
                boolean day = fw.optBoolean("day", true);
                if (!day && icon == R.drawable.wx_sun) icon = R.drawable.wx_moon;
                else if (!day && icon == R.drawable.wx_part) icon = R.drawable.wx_cloud;
                wxIcon.setImageResource(icon);
                wxTemp.setText(fw.optInt("temp") + "°");
                String city = fw.optString("city", "");
                wxInfo.setText((city.isEmpty() ? "" : city + "  ·  ") + WX_LABEL[k] + "  ·  " + fw.optInt("high") + "° / " + fw.optInt("low") + "°");
                weatherBox.setVisibility(View.VISIBLE);
            });
        });
    }

    // ---------- quote of the day (ZenQuotes through the helper; the built-in list without it) ----------
    void showQuote() {
        if (!cfg.optBoolean("quotes", true)) { quoteBox.setVisibility(View.GONE); return; }
        final long day = LocalDate.now().toEpochDay();
        if (day == quoteDay) return;
        quoteDay = day;
        io.execute(() -> {
            JSONObject q = null;
            Bitmap pic = null;
            try {
                q = new JSONObject(get("/api/quote", 15_000));
                String img = q.optString("image", "");
                if (!img.isEmpty()) { byte[] b = fetch(img, null, 15_000); pic = BitmapFactory.decodeByteArray(b, 0, b.length); }
            } catch (Exception ignored) { }
            final JSONObject fq = q;
            final Bitmap fp = pic;
            ui.post(() -> {
                if (fq != null && !fq.optString("text").isEmpty()) paintQuote(fq.optString("text"), fq.optString("by"), fq.optString("source"), fp);
                else {
                    JSONArray one = builtInQuotes.length() > 0 ? builtInQuotes.optJSONArray((int) (day % builtInQuotes.length())) : null;
                    if (one != null) paintQuote(one.optString(0), one.optString(1), "", null);
                    // The internet quote didn't come: try again in 10 minutes.
                    ui.postDelayed(() -> { if (quoteDay == day) { quoteDay = -1; showQuote(); } }, 10 * 60_000);
                }
            });
        });
    }

    void paintQuote(String text, String by, String source, Bitmap pic) {
        quoteText.setText("“" + text + "”");
        quoteBy.setText("— " + by);
        quoteSrc.setText(source == null || source.isEmpty() ? "" : "Quote via " + source);
        quotePic.setImageBitmap(pic);
        quotePic.setVisibility(pic != null ? View.VISIBLE : View.GONE);
        quoteText.getLayoutParams().width = pic != null ? 1120 - 150 : 1120;
        quoteText.requestLayout();
        quoteBox.setVisibility(View.VISIBLE);
    }

    // ---------- wallpaper of the day (Bing's photo, kept by the helper) ----------
    void refreshWallpaper() {
        lastWallpaper = System.currentTimeMillis();
        if (!cfg.optBoolean("wallpaper")) return;
        io.execute(() -> {
            try {
                JSONObject w = new JSONObject(get("/api/wallpaper", 15_000));
                final String image = w.optString("image", "");
                if (image.isEmpty() || image.equals(wallShown)) return;
                byte[] b = fetch(image, null, 60_000);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                final String cr = !w.optString("credit").isEmpty() ? w.optString("credit") : w.optString("title", "");
                if (bm == null) return;
                ui.post(() -> {
                    wallShown = image;
                    wall.setImageBitmap(bm);
                    wall.animate().alpha(1f).setDuration(1200).start();
                    wallShade.animate().alpha(1f).setDuration(600).start();
                    hasWallpaper = true;
                    credit.setText(cr);
                    credit.setVisibility(cr.isEmpty() ? View.GONE : View.VISIBLE);
                    for (TextView t : new TextView[]{greeting, clock, ampm, date, quoteText, quoteBy, quoteSrc, wxTemp, wxInfo, credit, heroName}) shadow(t, true);
                    render();
                });
            } catch (Exception ignored) { }
        });
    }

    String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "{}";
        }
    }
}
