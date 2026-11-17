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
    static final int W = 1920, H = 1080;
    static final int DOCK_LEFT = 103, DOCK_TOP = 690, BAND_PAD = 28, TITLE_H = 60, FOCUS_Y = 640, RAIL_TOP = 0;
    // Dock and row tiles: Big (4 across), Normal (5) or Small (6), chosen in the admin panel's Layout tab.
    int dockCols = 4, DOCK_W = 400, DOCK_H = 225, DOCK_PITCH = 438;
    int rowCols = 4, TILE_W = 360, TILE_H = 202, PITCH = 438, LEFT = DOCK_LEFT + 20, ROW_PITCH = 270;
    int GRID_TOP = DOCK_TOP + DOCK_H + 2 * BAND_PAD + 40;

    static int colsFor(String size) { return "small".equals(size) ? 6 : "normal".equals(size) ? 5 : 4; }

    /** Tile sizes from the layout; true if they changed (the tiles must be built again). */
    boolean dockFull = false;   // the dock runs the whole width; its apps widen to fill it

    boolean applyTileSizes(JSONObject lay) {
        dockFull = lay != null && lay.optBoolean("dock_full");
        int dc = colsFor(lay == null ? "" : lay.optString("dock")), rc = colsFor(lay == null ? "" : lay.optString("rows"));
        if (dc == dockCols && rc == rowCols) return false;   // (dock_full needs no new sizes: layout() reads it)
        dockCols = dc;
        DOCK_PITCH = 1752 / dc; DOCK_W = DOCK_PITCH * 400 / 438; DOCK_H = DOCK_W * 9 / 16;
        rowCols = rc;
        PITCH = 1752 / rc; TILE_W = PITCH * 360 / 438; TILE_H = TILE_W * 9 / 16; LEFT = DOCK_LEFT + (PITCH - TILE_W) / 2;
        ROW_PITCH = TILE_H + 68;
        GRID_TOP = DOCK_TOP + DOCK_H + 2 * BAND_PAD + 40;
        return true;
    }
    // The Home layout grid (helper/layout.py): 12 x 6 cells of 144 x 104 from (96, 40), above the dock.
    static final int GX = 96, GY = 40, CW = 144, RH = 104;
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
    LinearLayout pills, quoteBox, weatherBox, menuBox, adminBox;
    ImageView adminQr;
    TextView adminUrl;
    final List<View> sectionViews = new ArrayList<>();

    // ---------- apps and layout ----------
    static class App {
        String id, name, vpn; boolean banner; long v;
        String kind = "app";            // app, addon (an add-on's tile), item (a picture in an add-on's row)
        String emoji, color, addonKind, image;
        Object action;                  // an item's action: one step or a list of steps
    }
    JSONObject addonHome = new JSONObject();   // /api/addons/home: widgets, rows and tiles of the enabled add-ons
    static class Pos { int x, y, w, h, line, col; float fs; }   // fs: how much it grows when focused (0: by its size)
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
        try { addonHome = new JSONObject(prefs.getString("addons", "{}")); } catch (Exception e) { addonHome = new JSONObject(); }
        try { builtInQuotes = new JSONObject(readAsset("quotes.json")).getJSONArray("en"); } catch (Exception e) { builtInQuotes = new JSONArray(); }
        buildStage();
        applyLayout();
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
        if (area.equals("screen") && screenKind.equals("slideshow")) ui.postDelayed(slideNext, 4000);
        renderWidgets();
        // An app may have been installed or removed while you were away; the VPN may have changed from the TV.
        List<String> before = ids();
        loadApps();
        if (!before.equals(ids())) { build(); render(); }
        refreshAll(false);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(slideNext);
        stopWidgets();
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home is open: close what is open and go back to the first tile.
        if (area.equals("screen")) closeScreen();
        if (area.equals("admin")) closeAdmin();
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
        stage.setClipChildren(false);   // focused buttons and tiles grow a little past their own area
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
        // Its own clip: the stage lets focused buttons grow past their area, which would let the rail show under the header.
        viewport.setClipBounds(new android.graphics.Rect(-40, 0, W + 40, H - RAIL_TOP + 40));
        stage.addView(viewport, at(0, RAIL_TOP, W, H - RAIL_TOP));
        rail = new FrameLayout(this);
        rail.setClipChildren(false);
        rail.setTranslationY(-RAIL_TOP);
        viewport.addView(rail, at(0, 0, W, 6000));

        // All the layout blocks share one layer: it fades out while you browse the rows (they scroll away anyway).
        blocksLayer = new FrameLayout(this);
        blocksLayer.setClipChildren(false);
        rail.addView(blocksLayer, at(0, 0, W, 2000));
        buildQuote();
        buildWeather();
        buildWidgets();
        credit = text(24, 400, white(.55), 0, false);
        credit.setGravity(Gravity.END);
        credit.setMaxWidth(640);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.rightMargin = 96; cl.topMargin = 214;
        credit.setVisibility(View.GONE);
        creditCl = cl;

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        heroName = text(104, 300, Color.WHITE, -.02f, false);
        h.addView(heroName, new LinearLayout.LayoutParams(1000, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = text(26, 400, white(.5), .04f, false);
        hint.setText("OK to open  ·  hold OK for options");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMargins(6, -4, 0, 0);
        h.addView(hint, hl);
        hero = h;
        blocksLayer.addView(hero, at(GX + 12, GY + 4 * RH + 20, 7 * CW, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        buildEdit();
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

        buildAdminBox();
        buildScreen();

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

    FrameLayout blocksLayer;
    LinearLayout clockBlock, clockRow;
    View clockDivider;
    TextView miniClock;
    View topShade2;
    FrameLayout.LayoutParams creditCl;

    void buildHeader() {
        // The clock block: greeting, time and date. Where it sits and how big it is comes from the layout.
        clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setPadding(16, 12, 0, 0);
        greeting = text(24, 500, white(.55), .22f, true);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.leftMargin = 8;
        clockBlock.addView(greeting, gl);
        clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(104, 300, Color.WHITE, -.04f, false);
        clockRow.addView(clock);
        ampm = text(30, 500, white(.7), .08f, true);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.setMargins(12, 0, 0, 10);
        clockRow.addView(ampm, al);
        clockDivider = new View(this);
        clockDivider.setBackgroundColor(white(.2));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(2, 52);
        dl.setMargins(32, 0, 32, 8);
        clockRow.addView(clockDivider, dl);
        date = text(28, 400, white(.65), .14f, true);
        LinearLayout.LayoutParams dtl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtl.bottomMargin = 12;
        clockRow.addView(date, dtl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = 6;
        clockBlock.addView(clockRow, rl);
        blocksLayer.addView(clockBlock, at(GX, GY, 6 * CW, ViewGroup.LayoutParams.WRAP_CONTENT));

        // While you browse the rows, the blocks scroll away: a shade keeps the buttons readable, and a small clock stays.
        topShade2 = new View(this);
        topShade2.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{black(.92), black(.6), black(0)}));
        topShade2.setAlpha(0f);
        stage.addView(topShade2, new FrameLayout.LayoutParams(W, 300));
        miniClock = text(56, 300, Color.WHITE, -.03f, false);
        miniClock.setAlpha(0f);
        stage.addView(miniClock, at(112, 132, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(credit, creditCl);

        pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setClipChildren(false);
        pills.setClipToPadding(false);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72, Gravity.TOP | Gravity.END);
        pl.rightMargin = 96; pl.topMargin = 128;
        stage.addView(pills, pl);
        rebuildPills();
    }

    // ---------- the Home layout: where each block sits (admin panel → Layout) ----------
    static final String CLASSIC = "{\"clock\":{\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"on\":true},\"quote\":{\"x\":0,\"y\":2,\"w\":8,\"h\":2,\"on\":true},"
            + "\"weather\":{\"x\":9,\"y\":2,\"w\":3,\"h\":2,\"on\":true},\"hero\":{\"x\":0,\"y\":4,\"w\":7,\"h\":2,\"on\":true},"
            + "\"widgets\":{\"x\":8,\"y\":4,\"w\":4,\"h\":2,\"on\":true}}";
    boolean heroOn = true;
    int clockRows = 2;
    int quoteW = 1120;

    JSONObject block(JSONObject blocks, String id) {
        JSONObject b = blocks.optJSONObject(id);
        if (b == null) try { b = new JSONObject(CLASSIC).getJSONObject(id); } catch (Exception e) { b = new JSONObject(); }
        return b;
    }

    void applyLayout() {
        JSONObject lay = cfg.optJSONObject("layout"), blocks = lay == null ? null : lay.optJSONObject("blocks");
        applyTileSizes(lay);   // the caller builds the tiles again
        if (blocks == null) try { blocks = new JSONObject(CLASSIC); } catch (Exception e) { return; }
        // Clock: Small (one row) is time and date side by side; Big (three rows) is a large time.
        JSONObject c = block(blocks, "clock");
        place(clockBlock, c, false);
        int ch = c.optInt("h", 2);
        clockRows = ch;
        greeting.setVisibility(ch >= 2 ? View.VISIBLE : View.GONE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, ch >= 3 ? 210 : ch == 2 ? 104 : 64);
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_PX, ch >= 3 ? 44 : ch == 2 ? 30 : 22);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, ch >= 3 ? 34 : ch == 2 ? 28 : 22);
        clockDivider.setVisibility(ch >= 3 ? View.GONE : View.VISIBLE);
        clockRow.setOrientation(ch >= 3 ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, ch >= 3 ? 30 : 24);
        // Quote: as wide as its block.
        JSONObject q = block(blocks, "quote");
        place(quoteBox, q, false);
        quoteW = q.optInt("w", 8) * CW - 24;
        quoteText.setTextSize(TypedValue.COMPLEX_UNIT_PX, q.optInt("w", 8) >= 8 ? 38 : 32);
        if (quoteText.getLayoutParams() != null) { quoteText.getLayoutParams().width = quotePic.getVisibility() == View.VISIBLE ? quoteW - 150 : quoteW; quoteText.requestLayout(); }
        // Weather: right-aligned in its block; Small leaves out the line under the temperature.
        JSONObject wx = block(blocks, "weather");
        place(weatherBox, wx, false);
        boolean small = wx.optInt("h", 2) < 2;
        wxInfo.setVisibility(small ? View.GONE : View.VISIBLE);
        wxTemp.setTextSize(TypedValue.COMPLEX_UNIT_PX, small ? 60 : 88);
        ViewGroup.LayoutParams il = wxIcon.getLayoutParams();
        il.width = il.height = small ? 56 : 76;
        wxIcon.setLayoutParams(il);
        // App name (the focused dock app) and the widget cards.
        JSONObject h = block(blocks, "hero");
        heroOn = h.optBoolean("on", true);
        place(hero, h, false);
        heroName.getLayoutParams().width = h.optInt("w", 7) * CW;
        heroName.requestLayout();
        // Widget blocks: a four-row block shows two cards stacked.
        for (Slot s : slots) {
            JSONObject w = s.id.equals("widgets") ? block(blocks, s.id) : blocks.optJSONObject(s.id);
            if (w == null) { s.on = false; s.box.setVisibility(View.GONE); continue; }
            s.on = w.optBoolean("on", true);
            s.cards = Math.max(1, w.optInt("h", 2) / 2);
            s.pick = new java.util.HashSet<>();
            JSONArray pk = w.optJSONArray("addons");
            if (pk != null) for (int i = 0; i < pk.length(); i++) s.pick.add(pk.optString(i));
            place(s.box, w, false);
        }
        renderWidgets();
    }

    /** A block at its grid place, or gone. */
    void place(View v, JSONObject b, boolean fullHeight) {
        if (!b.optBoolean("on", true)) { v.setVisibility(View.GONE); return; }
        FrameLayout.LayoutParams lp = at(GX + b.optInt("x") * CW, GY + b.optInt("y") * RH, b.optInt("w", 4) * CW,
                fullHeight ? b.optInt("h", 2) * RH : ViewGroup.LayoutParams.WRAP_CONTENT);
        v.setLayoutParams(lp);
        if (v != weatherBox && v != quoteBox && !isSlotBox(v)) v.setVisibility(View.VISIBLE);
        else if (v == weatherBox) v.setVisibility(weatherBox.getTag() == null ? View.GONE : View.VISIBLE);
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
        blocksLayer.addView(quoteBox, at(GX + 12, GY + 2 * RH + 40, 8 * CW, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        blocksLayer.addView(weatherBox, at(GX + 9 * CW, GY + 2 * RH, 3 * CW, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------- pills: Region (when the VPN switch is there) and Settings ----------
    void rebuildPills() {
        pills.removeAllViews();
        pillViews.clear(); pillActions.clear();
        if (cfg.optBoolean("region") && regionNow != null) addPill(R.drawable.ic_globe, regionLabel(), "region");
        addPill(R.drawable.ic_qr, "Admin", "admin");
        addPill(R.drawable.ic_edit, "", "edit");
        addPill(R.drawable.ic_settings, "Settings", "settings");
        if (pill >= pillViews.size()) pill = pillViews.size() - 1;
    }

    void addPill(int icon, String label, String action) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(label.isEmpty() ? 19 : 24, 0, label.isEmpty() ? 19 : 28, 0);   // no label: a round button
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(34, 34));
        TextView t = text(27, 500, Color.WHITE, 0, false);
        t.setText(label);
        if (label.isEmpty()) t.setVisibility(View.GONE);
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
        // Full-screen and quick-action add-ons: tiles like apps (placed, renamed and hidden the same way).
        JSONArray atiles = addonHome.optJSONArray("tiles");
        if (atiles != null) for (int i = 0; i < atiles.length(); i++) {
            JSONObject t = atiles.optJSONObject(i);
            if (t == null || hide.contains(t.optString("id"))) continue;
            App a = new App();
            a.id = t.optString("id"); a.kind = "addon"; a.addonKind = t.optString("kind");
            a.name = rename != null && rename.has(a.id) ? rename.optString(a.id) : t.optString("name");
            a.emoji = t.optString("emoji"); a.color = t.optString("color", "#3a3a44"); a.banner = true;
            list.add(a);
        }
        // The dock first (your own order from Move, else the helper's, else the usual four), then the rows.
        List<String> dock = strings(jsonArray(prefs.getString("dock", null)));
        if (dock.isEmpty()) dock = strings(cfg.optJSONArray("dock"));
        if (dock.isEmpty()) { dock = new ArrayList<>(); Collections.addAll(dock, CC_DOCK); }
        List<App> docked = new ArrayList<>(), rest = new ArrayList<>();
        for (String id : dock) for (App a : list) if (a.id.equals(id) && docked.size() < dockCols) docked.add(a);
        for (App a : list) if (!docked.contains(a)) rest.add(a);
        Collections.sort(rest, (x, y) -> x.name.compareToIgnoreCase(y.name));
        dockSize = docked.size();
        List<App> ordered = new ArrayList<>(docked);
        sections.clear();
        // Add-on rows (YouTube channels, top movies…) come right after the dock.
        JSONArray arows = addonHome.optJSONArray("rows");
        if (arows != null) for (int r = 0; r < arows.length(); r++) {
            JSONObject row = arows.optJSONObject(r);
            JSONArray its = row == null ? null : row.optJSONArray("items");
            if (its == null || its.length() == 0) continue;
            Section s = new Section();
            s.title = row.optString("title"); s.start = ordered.size(); s.count = its.length();
            sections.add(s);
            for (int k = 0; k < its.length(); k++) {
                JSONObject it = its.optJSONObject(k);
                App a = new App();
                a.id = "item:" + row.optString("id") + ":" + k; a.kind = "item"; a.banner = true;
                a.name = it.optString("title"); a.image = it.optString("image"); a.action = it.opt("action");
                ordered.add(a);
            }
        }
        JSONArray rows = cfg.optJSONArray("rows");
        rowsMode = (rows != null && rows.length() > 0) || !sections.isEmpty();
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
        int gap = DOCK_PITCH - DOCK_W, span = (dockCols - 1) * DOCK_PITCH + DOCK_W;
        int w = dockFull && dockSize > 0 ? (span - (dockSize - 1) * gap) / dockSize : DOCK_W;
        for (int i = 0; i < dockSize; i++) {
            Pos p = new Pos();
            p.x = DOCK_LEFT + i * (w + gap); p.y = DOCK_TOP + BAND_PAD; p.w = w; p.h = DOCK_H; p.line = 0; p.col = i;
            if (dockFull) p.fs = Math.min(1.1f, 1f + (gap - 10f) / w);   // wide tiles grow less, so they don't cover their neighbours
            pos.add(p); lines.get(0).add(i);
        }
        int y = GRID_TOP;
        for (Section s : sections) {
            if (!s.title.isEmpty()) { s.y = y; y += TITLE_H; }
            for (int k = 0; k < s.count; k++) {
                if (k % rowCols == 0) { if (k > 0) y += ROW_PITCH; lines.add(new ArrayList<Integer>()); }
                Pos p = new Pos();
                p.x = LEFT + (k % rowCols) * PITCH; p.y = y; p.w = TILE_W; p.h = TILE_H; p.line = lines.size() - 1; p.col = k % rowCols;
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
        int bandW = dock <= 0 ? 0 : dockFull ? (dockCols - 1) * DOCK_PITCH + DOCK_W + 2 * BAND_PAD + 4
                : pos.get(dock - 1).x + pos.get(dock - 1).w - DOCK_LEFT + 2 * BAND_PAD + 4;
        FrameLayout.LayoutParams bl = at(DOCK_LEFT - BAND_PAD - 2, DOCK_TOP, bandW, DOCK_H + 2 * BAND_PAD);
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
            TextView label = text(Math.max(20, 30 * p.h / 202), 500, Color.WHITE, 0, false);
            label.setText(app.name);
            label.setGravity(Gravity.CENTER);
            t.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.setPivotX(p.w / 2f); t.setPivotY(p.h / 2f);
            rail.addView(t, glassAt++, at(p.x, p.y, p.w, p.h));
            tiles.add(t);
            if ("addon".equals(app.kind)) {   // its emoji on its colour
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{parseColor(app.color, 0xFF3A3A44), darker(parseColor(app.color, 0xFF3A3A44))});
                t.setBackground(g);
                label.setText(app.emoji);
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(p.h * .42f));
                label.setTypeface(Typeface.DEFAULT);
            } else if ("item".equals(app.kind)) {   // a picture from the internet
                label.setVisibility(View.GONE);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                loadImage(app.image, img, p.w);
            } else showArt(app, t);

            TextView n = text(p.h < 180 ? 24 : 28, 500, white(.95), .02f, false);
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
    boolean onTop() { return area.equals("pills") || area.equals("admin") || area.equals("screen") || area.equals("edit") || (area.equals("menu") && !menuKind.equals("tile")); }
    App focused() { return onTop() || index >= apps.size() ? null : apps.get(index); }
    final DecelerateInterpolator decel = new DecelerateInterpolator(2f);

    void render() {
        boolean top = onTop();
        for (int i = 0; i < pillViews.size(); i++) stylePill(pillViews.get(i), top && i == pill);
        for (int i = 0; i < tiles.size(); i++) {
            boolean on = !top && i == index;
            boolean lift = on && area.equals("move");
            View t = tiles.get(i);
            float s = lift ? 1.16f : on ? (i < pos.size() ? focusScale(pos.get(i)) : 1.1f) : 1f;
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
        boolean browsing = shift != 0;
        topShade2.animate().alpha(browsing ? 1f : 0f).setDuration(400).start();
        blocksLayer.animate().alpha(browsing ? 0f : 1f).setDuration(browsing ? 200 : 400).start();
        miniClock.animate().alpha(browsing ? 1f : 0f).setDuration(400).start();
        credit.animate().alpha(browsing ? 0f : 1f).setDuration(300).start();
        App app = focused();
        if (inDock && app != null) heroName.setText(app.name);
        hero.animate().alpha(inDock && heroOn ? 1f : 0f).setDuration(300).start();
        gridShade.animate().alpha(hasWallpaper && !inDock && !top ? 1f : 0f).setDuration(600).start();
        if (app != null && p != null && !area.equals("move")) glassTo(p);
        else glass.animate().alpha(0f).setDuration(200).start();
    }

    // The glass highlight, on a slightly springy move.
    /** Big tiles grow 10% when focused; smaller ones (closer together) 6%, so they don't cover their neighbours. */
    static float focusScale(Pos p) { return p.fs > 0 ? p.fs : p.w >= 330 ? 1.1f : 1.06f; }

    void glassTo(Pos p) {
        float fs = focusScale(p);
        int pad = fs > 1.08f ? 24 : 14;
        int gw = Math.round(p.w * fs + pad), gh = Math.round(p.h * fs + pad);
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
        if (area.equals("admin")) return;
        if (area.equals("edit")) { editArrow(dx, dy); return; }
        if (area.equals("screen")) { screenArrow(dx, dy); return; }
        if (area.equals("menu")) {
            if (dy != 0) { menuIndex = Math.max(0, Math.min(menuItems.size() - 1, menuIndex + dy)); renderMenu(); }
            return;
        }
        if (area.equals("move")) { moveKey(dx); return; }
        move(dx, dy);
    }

    void back() {
        if (area.equals("edit")) { editBack(); return; }
        if (area.equals("screen")) { closeScreen(); return; }
        if (area.equals("admin")) { closeAdmin(); return; }
        if (area.equals("menu")) { closeMenu(); return; }
        if (area.equals("move")) { endMove(false); return; }
        if (area.equals("pills") || index >= dockSize) {
            area = "dock";
            index = Math.max(0, Math.min(lastDock, dockSize - 1));
            render();
        }
    }

    void activate() {
        if (area.equals("edit")) { editOk(); return; }
        if (area.equals("screen")) { screenOk(); return; }
        if (area.equals("admin")) { closeAdmin(); return; }
        if (area.equals("menu")) { menuChoose(); return; }
        if (area.equals("move")) { endMove(true); return; }
        if (area.equals("pills")) {
            String a = pillActions.get(pill);
            if (a.equals("region")) openRegionMenu();
            else if (a.equals("admin")) openAdmin();
            else if (a.equals("edit")) openEdit();
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
        if ("addon".equals(app.kind)) {
            if ("screen".equals(app.addonKind)) openScreen(app.id.substring(6));
            else runAddon(app.id.substring(6), app.name);
            return;
        }
        if ("item".equals(app.kind)) { runSteps(app.action); return; }
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
    // =====================================================================================================
    // Add-ons: widgets (a card on the right), rows (after the dock), tiles (full-screen pages, quick actions)
    // =====================================================================================================
    long lastAddons = 0;

    static int parseColor(String c, int fallback) {
        try { return Color.parseColor(c); } catch (Exception e) { return fallback; }
    }

    static int darker(int c) {
        return Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10);
    }

    /** Runs on the io thread. */
    void refreshAddons() {
        lastAddons = System.currentTimeMillis();
        try {
            JSONObject h = new JSONObject(get("/api/addons/home", 15_000));
            ui.post(() -> {
                boolean layoutChanged = !String.valueOf(h.optJSONArray("rows")).equals(String.valueOf(addonHome.optJSONArray("rows")))
                        || !String.valueOf(h.optJSONArray("tiles")).equals(String.valueOf(addonHome.optJSONArray("tiles")));
                addonHome = h;
                prefs.edit().putString("addons", h.toString()).apply();
                if (layoutChanged && !area.equals("move")) { loadApps(); build(); render(); }
                renderWidgets();
            });
        } catch (Exception ignored) { }
    }

    /** A widget block on the layout: the add-ons picked for it (none picked: every widget no other block has),
     *  one card, or two stacked when the block is four rows tall. With more than fit, the next ones in turn. */
    final class Slot {
        final String id;
        final LinearLayout box = new LinearLayout(MainActivity.this);   // the cards, one under the other
        boolean on;
        int cards = 1, page = 0, shownPage, shownPages;
        java.util.Set<String> pick = new java.util.HashSet<>();
        java.util.List<JSONObject> shown = new ArrayList<>();
        final Runnable next = () -> { page++; renderSlot(this); };
        Slot(String id) {
            this.id = id;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setVisibility(View.GONE);
        }
    }
    static final String[] SLOT_IDS = {"widgets", "widgets2", "widgets3"};
    final Slot[] slots = new Slot[SLOT_IDS.length];

    void buildWidgets() {
        for (int i = 0; i < SLOT_IDS.length; i++) {
            slots[i] = new Slot(SLOT_IDS[i]);
            blocksLayer.addView(slots[i].box, at(GX + 8 * CW, GY + 4 * RH, 4 * CW, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    boolean isSlotBox(View v) {
        for (Slot s : slots) if (s != null && s.box == v) return true;
        return false;
    }

    LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(28, 16, 28, 18);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(28);
        bg.setColor(black(.45));
        bg.setStroke(2, white(.12));
        card.setBackground(bg);
        return card;
    }

    /** The widgets a block shows, in the admin panel's order. */
    java.util.List<JSONObject> widgetsFor(Slot s) {
        java.util.List<JSONObject> out = new ArrayList<>();
        JSONArray ws = addonHome.optJSONArray("widgets");
        if (ws == null) return out;
        java.util.Set<String> others = new java.util.HashSet<>();
        for (Slot o : slots) if (o != null && o != s && o.on) others.addAll(o.pick);
        for (int i = 0; i < ws.length(); i++) {
            JSONObject w = ws.optJSONObject(i);
            if (w == null) continue;
            String id = w.optString("id");
            if (s.pick.isEmpty() ? !others.contains(id) : s.pick.contains(id)) out.add(w);
        }
        return out;
    }

    void renderWidgets() {
        for (Slot s : slots) if (s != null) renderSlot(s);
    }

    void stopWidgets() {
        for (Slot s : slots) if (s != null) ui.removeCallbacks(s.next);
    }

    void renderSlot(Slot s) {
        ui.removeCallbacks(s.next);
        java.util.List<JSONObject> list = widgetsFor(s);
        if (!s.on || list.isEmpty()) { s.box.setVisibility(View.GONE); s.box.removeAllViews(); s.shown = new ArrayList<>(); return; }
        final int k = Math.min(s.cards, list.size()), pages = (list.size() + k - 1) / k, p = s.page % pages;
        final java.util.List<JSONObject> now = new ArrayList<>(list.subList(p * k, Math.min(list.size(), p * k + k)));
        Runnable swap = () -> {
            fillSlot(s, now, p, pages);
            s.box.animate().alpha(1f).setDuration(350).start();
        };
        if (s.box.getVisibility() != View.VISIBLE || s.box.getChildCount() == 0) {
            s.box.setAlpha(0f);
            s.box.setVisibility(View.VISIBLE);
            swap.run();
        } else s.box.animate().alpha(0f).setDuration(220).withEndAction(swap).start();
        if (pages > 1 && resumed) ui.postDelayed(s.next, Math.max(4, addonHome.optInt("widget_seconds", 10)) * 1000L);
    }

    /** The block's cards; the first one has a dot per turn. */
    void fillSlot(Slot s, java.util.List<JSONObject> now, int page, int pages) {
        s.shown = now; s.shownPage = page; s.shownPages = pages;
        s.box.removeAllViews();
        for (int i = 0; i < now.size(); i++) {
            LinearLayout card = newCard();
            fillWidget(card, now.get(i), page, i == 0 ? pages : 0);
            s.box.addView(card, below(i == 0 ? 0 : 16));
        }
    }

    /** The minute changed: redraw the cards on screen (countdowns), without moving on to the next ones. */
    void refreshWidgetTimes() {
        for (Slot s : slots) if (s != null && s.box.getVisibility() == View.VISIBLE && !s.shown.isEmpty()) fillSlot(s, s.shown, s.shownPage, s.shownPages);
    }

    void fillWidget(LinearLayout card, JSONObject w, int at, int count) {
        // Header: the add-on's emoji on its colour, the title, and a dot per card.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView emo = new TextView(this);
        emo.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        emo.setGravity(Gravity.CENTER);
        emo.setText(w.optString("emoji"));
        GradientDrawable eb = new GradientDrawable();
        eb.setCornerRadius(10);
        eb.setColor(parseColor(w.optString("color"), 0xFF3A3A44));
        emo.setBackground(eb);
        head.addView(emo, new LinearLayout.LayoutParams(38, 38));
        TextView title = text(21, 500, white(.7), .12f, true);
        title.setText(w.optString("title"));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = 14;
        head.addView(title, tl);
        if (count > 1) for (int i = 0; i < count; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == at ? white(.9) : white(.28));
            dot.setBackground(d);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(i == at ? 10 : 8, i == at ? 10 : 8);
            dl.leftMargin = 7;
            head.addView(dot, dl);
        }
        card.addView(head);
        String kind = w.optString("kind");
        if (kind.equals("matches") && w.optJSONArray("matches") != null && w.optJSONArray("matches").length() > 0) fillMatches(card, w);
        else if (kind.equals("prayer") && w.optJSONObject("times") != null) fillPrayer(card, w);
        else if (kind.equals("headlines") && w.optJSONArray("items") != null) fillHeadlines(card, w);
        else fillLines(card, w);
        if (hasWallpaper) shadowAll(card);
    }

    void shadowAll(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && v.getBackground() == null) shadow((TextView) v, true);
            else if (v instanceof ViewGroup) shadowAll((ViewGroup) v);
        }
    }

    LinearLayout.LayoutParams below(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    /** Any add-on: up to three lines of text, each with a smaller note after it. */
    void fillLines(LinearLayout card, JSONObject w) {
        JSONArray lines = w.optJSONArray("lines");
        if (lines == null) return;
        for (int i = 0; i < Math.min(3, lines.length()); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) continue;
            TextView t = text(26, 400, white(.92), 0, false);
            String sub = l.optString("sub");
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(l.optString("text"));
            if (!sub.isEmpty()) {
                int from = sb.length();
                sb.append("   ").append(sub);
                sb.setSpan(new android.text.style.ForegroundColorSpan(l.optBoolean("live") ? 0xFFFF6B6B : white(.5)), from, sb.length(), 0);
            }
            t.setText(sb);
            card.addView(t, below(i == 0 ? 12 : 6));
        }
    }

    /** Football: home logo and name, the score or kick-off in the middle, the away side; live games marked. */
    void fillMatches(LinearLayout card, JSONObject w) {
        JSONArray ms = w.optJSONArray("matches");
        for (int i = 0; i < Math.min(2, ms.length()); i++) {   // two fit above the dock
            JSONObject m = ms.optJSONObject(i);
            JSONObject h = m.optJSONObject("home"), a = m.optJSONObject("away");
            if (h == null || a == null) continue;
            String state = m.optString("state");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(teamLogo(h.optString("logo")), new LinearLayout.LayoutParams(34, 34));
            TextView hn = text(24, 500, white(.92), 0, false);
            hn.setText(h.optString("name"));
            hn.setGravity(Gravity.END);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            hl.leftMargin = 10;
            row.addView(hn, hl);
            // The middle: "2 – 1" (and a red LIVE badge), or the kick-off time and how long until it.
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView big = text(state.equals("pre") ? 24 : 28, state.equals("pre") ? 500 : 600, Color.WHITE, 0, false);
            TextView small = text(17, 600, white(.55), .06f, true);
            if (state.equals("pre")) {
                big.setText(new java.text.SimpleDateFormat(isToday(m.optLong("start")) ? "HH:mm" : "EEE HH:mm", Locale.ENGLISH).format(new java.util.Date(m.optLong("start"))));
                small.setText(until(m.optLong("start")));
            } else {
                big.setText(h.optString("score") + " – " + a.optString("score"));
                if (state.equals("in")) {
                    small.setText("LIVE " + m.optString("detail").replaceAll("[^0-9'+:]", "").trim());
                    small.setTextColor(Color.WHITE);
                    small.setPadding(10, 2, 10, 2);
                    GradientDrawable live = new GradientDrawable();
                    live.setCornerRadius(8);
                    live.setColor(0xFFE53935);
                    small.setBackground(live);
                } else small.setText("Full time");
            }
            mid.addView(big);
            mid.addView(small);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(150, ViewGroup.LayoutParams.WRAP_CONTENT);
            ml.leftMargin = 8; ml.rightMargin = 8;
            row.addView(mid, ml);
            TextView an = text(24, 500, white(.92), 0, false);
            an.setText(a.optString("name"));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            al.rightMargin = 10;
            row.addView(an, al);
            row.addView(teamLogo(a.optString("logo")), new LinearLayout.LayoutParams(34, 34));
            card.addView(row, below(i == 0 ? 12 : 8));
        }
    }

    ImageView teamLogo(String url) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadImage(url, iv, 68);
        return iv;
    }

    static boolean isToday(long ms) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(ms);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** "in 45 min", "in 3 h 20 min", "in 2 days"; "now" once it's time. */
    static String until(long ms) {
        long min = (ms - System.currentTimeMillis()) / 60_000;
        if (min <= 0) return "now";
        if (min < 60) return "in " + min + " min";
        if (min < 24 * 60) return "in " + (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
        long days = (min + 12 * 60) / (24 * 60);
        return "in " + days + (days == 1 ? " day" : " days");
    }

    /** Prayer times: the next one large with its countdown, and all five below with the next one lit. */
    void fillPrayer(LinearLayout card, JSONObject w) {
        JSONObject times = w.optJSONObject("times"), tomorrow = w.optJSONObject("tomorrow");
        JSONArray names = w.optJSONArray("names");
        if (names == null) return;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
        boolean stale = !today.equals(w.optString("date"));   // the day changed since the Mac last fetched
        if (stale && tomorrow != null) times = tomorrow;
        String now = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date());
        String next = null;
        for (int i = 0; i < names.length(); i++) if (times.optString(names.optString(i)).compareTo(now) > 0) { next = names.optString(i); break; }
        JSONObject nextDay = times;
        boolean afterIsha = next == null;
        if (afterIsha) { next = names.optString(0); if (tomorrow != null) nextDay = tomorrow; }
        String at = nextDay.optString(next);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        TextView nm = text(40, 500, Color.WHITE, 0, false);
        nm.setText(next);
        row.addView(nm);
        TextView tm = text(40, 300, white(.9), 0, false);
        tm.setText("  " + at);
        row.addView(tm);
        TextView cd = text(22, 400, white(.6), 0, false);
        try {
            String[] hm = at.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
            c.set(Calendar.SECOND, 0);
            if (afterIsha) c.add(Calendar.DAY_OF_YEAR, 1);
            cd.setText("   " + until(c.getTimeInMillis()));
        } catch (Exception ignored) { }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.bottomMargin = 6;
        row.addView(cd, cl);
        card.addView(row, below(10));
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length(); i++) {
            String n = names.optString(i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(0, 6, 0, 8);
            boolean lit = n.equals(next);
            if (lit) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(14); g.setColor(white(.16)); col.setBackground(g); }
            TextView a = text(17, 600, lit ? Color.WHITE : white(.5), .08f, true);
            a.setText(n);
            a.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(a);
            TextView b = text(23, lit ? 500 : 400, lit ? Color.WHITE : white(.8), 0, false);
            b.setText(times.optString(n));
            b.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(b);
            strip.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(strip, below(10));
    }

    /** News: the top story's picture beside the headlines. */
    void fillHeadlines(LinearLayout card, JSONObject w) {
        JSONArray items = w.optJSONArray("items");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String image = w.optString("image");
        if (!image.isEmpty()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(white(.08));
            round(iv, 12);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(176, 120);
            il.rightMargin = 18; il.topMargin = 4;
            row.addView(iv, il);
            loadImage(image, iv, 240);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Math.min(3, items.length()); i++) {
            JSONObject it = items.optJSONObject(i);
            TextView t = text(i == 0 ? 25 : 22, i == 0 ? 500 : 400, i == 0 ? Color.WHITE : white(.75), 0, false);
            if (i == 0) { t.setSingleLine(false); t.setMaxLines(2); }
            t.setText(it == null ? "" : it.optString("title"));
            col.addView(t, below(i == 0 ? 0 : 6));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row, below(12));
    }

    // ---------- quick actions and row items: steps ----------
    void runAddon(String addonId, String name) {
        final boolean[] done = {false};
        ui.postDelayed(() -> { if (!done[0]) toast(name + "…"); }, 1200);   // a VPN switch takes a while
        io.execute(() -> {
            try {
                JSONObject r = new JSONObject(post("/api/addons/" + addonId + "/run", "{}", 90_000));
                done[0] = true;
                if (!r.optBoolean("ok")) { ui.post(() -> toast(name + ": " + r.optString("error", "did not work"))); return; }
                ui.post(this::refreshRegion);
                runSteps(r.optJSONArray("steps"));
            } catch (Exception e) {
                done[0] = true;
                ui.post(() -> toast("Cannot reach the Mac, so " + name + " did not run"));
            }
        });
    }

    /** One step or a list of them: {"vpn"}, {"open", "uri"}, {"screen"}, {"url"}, {"toast"}. */
    void runSteps(Object action) {
        JSONArray steps = action instanceof JSONArray ? (JSONArray) action : new JSONArray();
        if (action instanceof JSONObject) steps.put(action);
        final JSONArray all = steps;
        new Thread(() -> {
            for (int i = 0; i < all.length(); i++) {
                JSONObject st = all.optJSONObject(i);
                if (st == null) continue;
                if (st.has("vpn")) {
                    String v = st.optString("vpn");
                    ui.post(() -> toast("Switching the VPN to " + regionName(v) + "…"));
                    try { post("/api/region", new JSONObject().put("region", v).toString(), 90_000); } catch (Exception ignored) { }
                    ui.post(this::refreshRegion);
                }
                if (st.has("open")) { String pkg = st.optString("open"), uri = st.optString("uri"); ui.post(() -> openPackage(pkg, uri)); }
                if (st.has("url")) { String u = st.optString("url"); ui.post(() -> openPackage(null, u)); }
                if (st.has("screen")) { String id = st.optString("screen"); ui.post(() -> openScreen(id)); }
                if (st.has("toast")) { String m = st.optString("toast"); ui.post(() -> toast(m)); }
            }
        }).start();
    }

    void openPackage(String pkg, String uri) {
        if (uri != null && !uri.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            if (pkg != null && !pkg.isEmpty()) view.setPackage(pkg);
            if (startSafely(view)) return;
        }
        if (pkg == null || pkg.isEmpty()) { toast("Nothing can open that here"); return; }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null || !startSafely(i)) toast("That app isn't installed");
    }

    // ---------- pictures from the internet (rows, slideshows, lists), shrunk to what's shown ----------
    final ExecutorService imgPool = Executors.newFixedThreadPool(4);
    final android.util.LruCache<String, Bitmap> imgCache = new android.util.LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    void loadImage(String url, ImageView iv, int width) {
        if (url == null || url.isEmpty()) return;
        final String full = url.startsWith("/") ? BASE + url : url;
        final String key = full + "@" + width;
        iv.setTag(key);
        Bitmap hit = imgCache.get(key);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        imgPool.execute(() -> {
            try {
                byte[] b = full.startsWith(BASE) ? fetch(full, null, 30_000) : download(full);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (bm == null) return;
                imgCache.put(key, bm);
                ui.post(() -> { if (key.equals(iv.getTag())) { iv.setImageBitmap(bm); iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(250).start(); } });
            } catch (Exception ignored) { }
        });
    }

    static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) HomeLauncher");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    // ---------- full-screen add-ons: a slideshow or a list ----------
    FrameLayout screenRoot, slideA, slideB;
    ImageView slideImgA, slideImgB;
    TextView screenTitle, slideCaption, slideCount, screenEmpty;
    View topShade;
    LinearLayout listBox;
    JSONObject screenPage;
    String screenKind = "";
    int screenIndex = 0;
    boolean slideFront = true;
    final Runnable slideNext = () -> { screenIndex++; showSlide(); };

    void buildScreen() {
        screenRoot = new FrameLayout(this);
        screenRoot.setBackgroundColor(Color.BLACK);
        screenRoot.setClipChildren(true);
        slideImgA = new ImageView(this);
        slideImgB = new ImageView(this);
        for (ImageView v : new ImageView[]{slideImgA, slideImgB}) {
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);   // the whole photo, tall ones too
            v.setAlpha(0f);
            screenRoot.addView(v, new FrameLayout.LayoutParams(W, H));
        }
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        screenRoot.addView(listBox, at(108, 190, W - 216, 40_000));   // tall: it slides up under the title as you go down
        topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF000000, 0xEE000000, 0x00000000}));
        screenRoot.addView(topShade, new FrameLayout.LayoutParams(W, 200));
        screenTitle = text(44, 400, Color.WHITE, -.01f, false);
        screenRoot.addView(screenTitle, at(108, 70, W - 216, ViewGroup.LayoutParams.WRAP_CONTENT));
        slideCaption = text(28, 400, white(.9), 0, false);
        shadow(slideCaption, true);
        screenRoot.addView(slideCaption, at(108, H - 100, W - 500, ViewGroup.LayoutParams.WRAP_CONTENT));
        slideCount = text(24, 400, white(.6), 0, false);
        shadow(slideCount, true);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        cl.rightMargin = 108; cl.bottomMargin = 70;
        screenRoot.addView(slideCount, cl);
        screenEmpty = text(30, 400, white(.7), 0, false);
        screenEmpty.setSingleLine(false);
        screenEmpty.setGravity(Gravity.CENTER);
        screenRoot.addView(screenEmpty, new FrameLayout.LayoutParams(W - 400, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        screenRoot.setVisibility(View.GONE);
        stage.addView(screenRoot, new FrameLayout.LayoutParams(W, H));
    }

    void openScreen(String addonId) {
        toast("Opening…");
        io.execute(() -> {
            try {
                JSONObject page = new JSONObject(get("/api/addons/" + addonId + "/screen", 30_000));
                ui.post(() -> showScreen(page));
            } catch (Exception e) {
                ui.post(() -> toast("Cannot reach the Mac, so that page can't open"));
            }
        });
    }

    void showScreen(JSONObject page) {
        ui.removeCallbacks(hideToast);
        toast.setAlpha(0f);
        screenPage = page;
        screenKind = page.optString("kind", "list");
        screenIndex = 0;
        area = "screen";
        screenTitle.setText(page.optString("title"));
        JSONArray items = page.optJSONArray("items");
        boolean empty = items == null || items.length() == 0;
        screenEmpty.setText(empty ? (page.optString("error").isEmpty() ? page.optString("empty", "Nothing to show yet.") : "Problem: " + page.optString("error")) : "");
        screenEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        boolean slides = screenKind.equals("slideshow");
        listBox.setVisibility(slides ? View.GONE : View.VISIBLE);
        slideCaption.setVisibility(slides ? View.VISIBLE : View.GONE);
        slideCount.setVisibility(slides ? View.VISIBLE : View.GONE);
        slideImgA.setAlpha(0f); slideImgB.setAlpha(0f);
        screenTitle.setVisibility(slides ? View.GONE : View.VISIBLE);
        topShade.setVisibility(slides ? View.GONE : View.VISIBLE);
        screenRoot.setAlpha(0f);
        screenRoot.setVisibility(View.VISIBLE);
        screenRoot.animate().alpha(1f).setDuration(250).start();
        if (empty) return;
        if (slides) showSlide();
        else buildList(items);
    }

    void showSlide() {
        ui.removeCallbacks(slideNext);
        JSONArray items = screenPage.optJSONArray("items");
        if (items == null || items.length() == 0) return;
        int n = items.length();
        screenIndex = ((screenIndex % n) + n) % n;
        JSONObject it = items.optJSONObject(screenIndex);
        ImageView in = slideFront ? slideImgB : slideImgA, out = slideFront ? slideImgA : slideImgB;
        slideFront = !slideFront;
        in.setImageDrawable(null);
        in.setAlpha(0f);
        loadImage(it.optString("image"), in, W);
        in.animate().alpha(1f).setDuration(900).start();
        out.animate().alpha(0f).setDuration(900).start();
        slideCaption.setText(it.optString("caption"));
        slideCount.setText((screenIndex + 1) + " / " + n);
        if (resumed) ui.postDelayed(slideNext, Math.max(3, screenPage.optInt("seconds", 8)) * 1000L);
    }

    void buildList(JSONArray items) {
        listBox.removeAllViews();
        listBox.setTranslationY(0);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(18, 14, 24, 14);
            String image = it.optString("image");
            if (!image.isEmpty()) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFF1C1C1E);
                round(iv, 14);
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(224, 126);
                il.rightMargin = 28;
                row.addView(iv, il);
                loadImage(image, iv, 224);
            }
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(30, 500, Color.WHITE, 0, false);
            t.setText(it.optString("title"));
            col.addView(t);
            String sub = it.optString("subtitle");
            if (!sub.isEmpty()) {
                TextView st = text(24, 400, white(.6), 0, false);
                st.setSingleLine(false);
                st.setMaxLines(2);
                st.setText(sub);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sl.topMargin = 6;
                col.addView(st, sl);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160);
            rl.bottomMargin = 12;
            listBox.addView(row, rl);
        }
        focusListRow();
    }

    void focusListRow() {
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View r = listBox.getChildAt(i);
            if (i == screenIndex) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(22); g.setColor(white(.12)); g.setStroke(3, white(.7)); r.setBackground(g); }
            else r.setBackground(null);
        }
        float y = -Math.max(0, screenIndex - 1) * 172f;
        listBox.animate().translationY(y).setDuration(250).setInterpolator(decel).start();
    }

    void screenArrow(int dx, int dy) {
        JSONArray items = screenPage == null ? null : screenPage.optJSONArray("items");
        if (items == null || items.length() == 0) return;
        if (screenKind.equals("slideshow")) {
            if (dx != 0) { screenIndex += dx; showSlide(); }
        } else if (dy != 0) {
            screenIndex = Math.max(0, Math.min(items.length() - 1, screenIndex + dy));
            focusListRow();
        }
    }

    void screenOk() {
        if (screenKind.equals("slideshow")) { screenIndex++; showSlide(); return; }
        JSONArray items = screenPage == null ? null : screenPage.optJSONArray("items");
        JSONObject it = items == null ? null : items.optJSONObject(screenIndex);
        if (it != null && it.has("action")) runSteps(it.opt("action"));
    }

    void closeScreen() {
        ui.removeCallbacks(slideNext);
        screenRoot.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            screenRoot.setVisibility(View.GONE);
            slideImgA.setImageDrawable(null);
            slideImgB.setImageDrawable(null);
            listBox.removeAllViews();
        }).start();
        area = index < dockSize ? "dock" : "grid";
        render();
    }

    // ---------- Admin: the admin panel's address as a QR code, for a phone camera ----------
    void buildAdminBox() {
        adminBox = new LinearLayout(this);
        adminBox.setOrientation(LinearLayout.VERTICAL);
        adminBox.setGravity(Gravity.CENTER_HORIZONTAL);
        adminBox.setPadding(56, 48, 56, 48);
        GradientDrawable bg = vertical(new int[]{0xFF2A2A2E, 0xFF161618}, 40);
        bg.setStroke(2, white(.2));
        adminBox.setBackground(bg);
        TextView t = text(26, 500, white(.55), .14f, true);
        t.setText("Admin panel");
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        adminBox.addView(t);
        adminQr = new ImageView(this);
        round(adminQr, 20);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(440, 440);
        ql.setMargins(0, 30, 0, 26);
        adminBox.addView(adminQr, ql);
        adminUrl = text(34, 500, Color.WHITE, 0, false);
        adminUrl.setGravity(Gravity.CENTER_HORIZONTAL);
        adminBox.addView(adminUrl);
        TextView hint = text(26, 400, white(.55), 0, false);
        hint.setText("Scan with your phone's camera, or type the address. Back closes this.");
        hint.setSingleLine(false);
        hint.setMaxLines(2);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = 14;
        adminBox.addView(hint, hl);
        adminBox.setVisibility(View.GONE);
        stage.addView(adminBox, new FrameLayout.LayoutParams(820, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    void openAdmin() {
        io.execute(() -> {
            try {
                JSONObject a = new JSONObject(get("/api/admin-link", 8000));
                JSONArray rows = a.getJSONArray("qr");
                int n = rows.length(), q = 4, full = n + 2 * q;
                Bitmap small = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
                small.eraseColor(Color.WHITE);
                for (int y = 0; y < n; y++) {
                    String row = rows.getString(y);
                    for (int x = 0; x < n; x++) if (row.charAt(x) == '1') small.setPixel(x + q, y + q, Color.BLACK);
                }
                int cell = Math.max(1, 440 / full);
                Bitmap big = Bitmap.createScaledBitmap(small, cell * full, cell * full, false);   // sharp squares
                small.recycle();
                String url = a.getString("url").replaceFirst("^https?://", "");
                ui.post(() -> {
                    adminQr.setImageBitmap(big);
                    adminUrl.setText(url);
                    area = "admin";
                    scrim.setVisibility(View.VISIBLE);
                    scrim.animate().alpha(1f).setDuration(200).start();
                    adminBox.setVisibility(View.VISIBLE);
                    adminBox.setAlpha(0f);
                    adminBox.animate().alpha(1f).setDuration(200).start();
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> toast("Cannot reach the Mac, so the admin panel can't be shown"));
            }
        });
    }

    void closeAdmin() {
        adminBox.setVisibility(View.GONE);
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        area = "pills";
        render();
    }

    // =====================================================================================================
    // Edit Home, with the remote: choose a block, move it, size it, hide it; put widgets in it; Back saves.
    // The same layout as the admin panel's and the TV's: a save here shows there too.
    // =====================================================================================================
    FrameLayout editLayer;
    LinearLayout editBar;
    TextView editHint, editAdd;
    JSONObject editLay, editSpec, editSavedLayout;
    JSONArray editWidgets = new JSONArray();
    String editSel = "", editLast = "";
    boolean editMoving = false, editChanged = false;
    int editFromX, editFromY;
    final Set<String> editOn = new java.util.HashSet<>(), editOff = new java.util.HashSet<>();

    void buildEdit() {
        editLayer = new FrameLayout(this);
        editLayer.setVisibility(View.GONE);
        stage.addView(editLayer, at(0, 0, W, 700));
        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setPadding(36, 18, 22, 18);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xEB141416); g.setCornerRadius(48); g.setStroke(2, white(.18));
        editBar.setBackground(g);
        editHint = text(26, 400, Color.WHITE, 0, false);
        editBar.addView(editHint);
        editAdd = text(26, 500, Color.WHITE, 0, false);
        editAdd.setText("+ Add to Home");
        editAdd.setPadding(28, 14, 28, 14);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = 28;
        editBar.addView(editAdd, al);
        editBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        bl.topMargin = 760;
        stage.addView(editBar, bl);
    }

    void openEdit() {
        io.execute(() -> {
            try {
                JSONObject r = new JSONObject(get("/api/home-edit", 8000));
                ui.post(() -> {
                    try {
                        editLay = new JSONObject(r.getJSONObject("layout").toString());
                        editSpec = r.getJSONObject("spec");
                        editWidgets = r.optJSONArray("widgets") == null ? new JSONArray() : r.optJSONArray("widgets");
                    } catch (Exception e) { toast("Cannot read the layout"); return; }
                    editSavedLayout = cfg.optJSONObject("layout");
                    editMoving = false; editChanged = false; editOn.clear(); editOff.clear();
                    List<String> shown = editShown();
                    editSel = shown.isEmpty() ? "add" : shown.get(0);
                    area = "edit";
                    editLayer.setVisibility(View.VISIBLE);
                    editBar.setVisibility(View.VISIBLE);
                    dimForEdit(true);
                    editPreview();
                    render();
                    drawEdit();
                });
            } catch (Exception e) { ui.post(() -> toast("Cannot reach the Mac")); }
        });
    }

    void dimForEdit(boolean on) {
        for (View t : tiles) t.setAlpha(on ? .2f : 1f);
        band.setAlpha(on ? .2f : 1f);
        pills.setAlpha(on ? .35f : 1f);
        for (View v : sectionViews) v.setAlpha(on ? .2f : 1f);
    }

    /** The layout being edited, drawn by the real blocks. */
    void editPreview() {
        try { cfg.put("layout", editLay); } catch (Exception ignored) { }
        applyLayout();
    }

    void closeEdit(boolean save) {
        editLayer.removeAllViews();
        editLayer.setVisibility(View.GONE);
        editBar.setVisibility(View.GONE);
        dimForEdit(false);
        area = "pills";
        render();
        if (!save || !editChanged) { restoreLayout(); return; }
        final JSONObject body = new JSONObject();
        try {
            body.put("layout", editLay);
            body.put("on", new JSONArray(editOn));
            body.put("off", new JSONArray(editOff));
        } catch (Exception ignored) { }
        final boolean added = !editOn.isEmpty();
        io.execute(() -> {
            try {
                JSONObject r = new JSONObject(post("/api/home-edit", body.toString(), 10_000));
                ui.post(() -> {
                    if (!r.optBoolean("ok")) { toast("Not saved: " + r.optString("error", "no answer")); restoreLayout(); return; }
                    toast("Saved. The TV shows it too." + (added ? " New widgets appear in a few seconds." : ""));
                    ui.postDelayed(() -> io.execute(this::refreshAddons), 4000);
                });
            } catch (Exception e) { ui.post(() -> { toast("Cannot reach the Mac: not saved"); restoreLayout(); }); }
        });
    }

    void restoreLayout() {
        try { if (editSavedLayout != null) cfg.put("layout", editSavedLayout); else cfg.remove("layout"); } catch (Exception ignored) { }
        applyLayout();
    }

    JSONObject eb(String id) { JSONObject b = editLay.optJSONObject("blocks"); return b == null ? null : b.optJSONObject(id); }
    List<String> editShown() {
        List<String> out = new ArrayList<>();
        JSONObject blocks = editLay.optJSONObject("blocks");
        if (blocks == null) return out;
        java.util.Iterator<String> it = blocks.keys();
        while (it.hasNext()) { String k = it.next(); if (blocks.optJSONObject(k).optBoolean("on", true)) out.add(k); }
        return out;
    }
    boolean editIsWidgets(String id) {
        JSONArray w = editSpec.optJSONArray("widget_blocks");
        for (int i = 0; w != null && i < w.length(); i++) if (w.optString(i).equals(id)) return true;
        return false;
    }
    static boolean overlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) { return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah; }
    boolean editFits(String id, int x, int y, int w, int h) {
        int cols = editSpec.optInt("cols", 12), rows = editSpec.optInt("rows", 6);
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return false;
        JSONObject r = editSpec.optJSONObject("reserved");
        if (r != null && overlap(x, y, w, h, r.optInt("x"), r.optInt("y"), r.optInt("w"), r.optInt("h"))) return false;
        for (String k : editShown()) {
            if (k.equals(id)) continue;
            JSONObject o = eb(k);
            if (overlap(x, y, w, h, o.optInt("x"), o.optInt("y"), o.optInt("w"), o.optInt("h"))) return false;
        }
        return true;
    }
    JSONArray editSizes(String id) { JSONObject b = editSpec.optJSONObject("blocks"); return b == null ? new JSONArray() : b.optJSONObject(id).optJSONArray("sizes"); }
    String editName(String id) { JSONObject b = editSpec.optJSONObject("blocks"); return b == null ? id : b.optJSONObject(id).optString("name", id); }
    String editSizeName(String id) {
        JSONObject b = eb(id); JSONArray s = editSizes(id);
        for (int i = 0; i < s.length(); i++) { JSONArray z = s.optJSONArray(i); if (z.optInt(0) == b.optInt("w") && z.optInt(1) == b.optInt("h")) return z.optString(2); }
        return "";
    }
    /** What a widget block shows: the ones picked, or every widget on Home that no other block picked. */
    List<JSONObject> editWidgetsIn(String id) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray picks = eb(id).optJSONArray("addons");
        java.util.Set<String> others = new java.util.HashSet<>();
        JSONArray wb = editSpec.optJSONArray("widget_blocks");
        for (int i = 0; wb != null && i < wb.length(); i++) {
            String k = wb.optString(i); JSONObject o = eb(k);
            if (k.equals(id) || o == null || !o.optBoolean("on", true)) continue;
            JSONArray p = o.optJSONArray("addons");
            for (int j = 0; p != null && j < p.length(); j++) others.add(p.optString(j));
        }
        for (int i = 0; i < editWidgets.length(); i++) {
            JSONObject w = editWidgets.optJSONObject(i);
            boolean in = picks != null && picks.length() > 0 ? contains(picks, w.optString("id")) : w.optBoolean("on") && !others.contains(w.optString("id"));
            if (in) out.add(w);
        }
        return out;
    }
    static boolean contains(JSONArray a, String s) { for (int i = 0; a != null && i < a.length(); i++) if (a.optString(i).equals(s)) return true; return false; }

    void drawEdit() {
        editLayer.removeAllViews();
        int cols = editSpec.optInt("cols", 12), rows = editSpec.optInt("rows", 6);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            View cell = new View(this);
            GradientDrawable g = new GradientDrawable(); g.setStroke(1, white(.14), 6, 6);
            cell.setBackground(g);
            editLayer.addView(cell, at(GX + c * CW, GY + r * RH, CW, RH));
        }
        for (String id : editShown()) {
            JSONObject b = eb(id);
            boolean sel = id.equals(editSel), moving = sel && editMoving;
            boolean bad = moving && !editFits(id, b.optInt("x"), b.optInt("y"), b.optInt("w"), b.optInt("h"));
            View o = new View(this);
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(18);
            if (bad) g.setStroke(5, 0xFFFF6B6B); else if (moving) g.setStroke(5, 0xFF4ADE80); else if (sel) g.setStroke(5, Color.WHITE);
            else g.setStroke(3, white(.55), 14, 10);
            o.setBackground(g);
            editLayer.addView(o, at(GX + b.optInt("x") * CW, GY + b.optInt("y") * RH, b.optInt("w") * CW, b.optInt("h") * RH));
            if (sel) {
                StringBuilder label = new StringBuilder(editName(id)).append(" · ").append(editSizeName(id));
                if (editIsWidgets(id)) {
                    List<JSONObject> ws = editWidgetsIn(id);
                    label.append(" · ");
                    if (ws.isEmpty()) label.append("empty");
                    for (int i = 0; i < ws.size(); i++) label.append(i > 0 ? ", " : "").append(ws.get(i).optString("name"));
                }
                TextView t = text(22, 500, Color.WHITE, 0, false);
                t.setText(label.toString());
                t.setPadding(14, 6, 14, 6);
                GradientDrawable lb = new GradientDrawable(); lb.setColor(black(.75)); lb.setCornerRadius(12);
                t.setBackground(lb);
                // Above the block (its content may run past its bottom edge); below it for a block in the top row.
                int ty = b.optInt("y") > 0 ? GY + b.optInt("y") * RH - 46 : GY + b.optInt("h") * RH + 8;
                editLayer.addView(t, at(GX + b.optInt("x") * CW + 10, ty, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        boolean addOn = editSel.equals("add");
        GradientDrawable ag = new GradientDrawable(); ag.setCornerRadius(32);
        if (addOn) ag.setColor(white(.96)); else ag.setStroke(2, white(.3));
        editAdd.setBackground(ag);
        editAdd.setTextColor(addOn ? 0xFF111111 : Color.WHITE);
        editHint.setText(editMoving ? "Arrows move it  ·  OK places it  ·  Back puts it back" : "Arrows choose  ·  OK changes it  ·  Back saves and finishes");
    }

    void editArrow(int dx, int dy) {
        if (editMoving) {
            JSONObject b = eb(editSel);
            int cols = editSpec.optInt("cols", 12), rows = editSpec.optInt("rows", 6);
            try {
                b.put("x", Math.max(0, Math.min(cols - b.optInt("w"), b.optInt("x") + dx)));
                b.put("y", Math.max(0, Math.min(rows - b.optInt("h"), b.optInt("y") + dy)));
            } catch (Exception ignored) { }
            editPreview(); drawEdit();
            return;
        }
        if (editSel.equals("add")) {
            if (dy < 0 && !editShown().isEmpty()) editSel = editShown().contains(editLast) ? editLast : editShown().get(0);
            drawEdit();
            return;
        }
        JSONObject cur = eb(editSel);
        float cx = cur.optInt("x") + cur.optInt("w") / 2f, cy = cur.optInt("y") + cur.optInt("h") / 2f;
        String best = null; float score = 1e9f;
        for (String id : editShown()) {
            if (id.equals(editSel)) continue;
            JSONObject b = eb(id);
            float vx = b.optInt("x") + b.optInt("w") / 2f - cx, vy = b.optInt("y") + b.optInt("h") / 2f - cy;
            if ((dx != 0 && vx * dx <= 0) || (dy != 0 && vy * dy <= 0)) continue;
            float sc = dx != 0 ? Math.abs(vx) + 2 * Math.abs(vy) : Math.abs(vy) + 2 * Math.abs(vx);
            if (sc < score) { score = sc; best = id; }
        }
        if (best != null) editSel = best;
        else if (dy > 0) { editLast = editSel; editSel = "add"; }
        drawEdit();
    }

    void editOk() {
        if (editMoving) {
            JSONObject b = eb(editSel);
            if (!editFits(editSel, b.optInt("x"), b.optInt("y"), b.optInt("w"), b.optInt("h"))) { toast("Can't go there: it covers another block or the buttons."); return; }
            editMoving = false; editChange();
            return;
        }
        if (editSel.equals("add")) { editAddMenu(); return; }
        editMenu(0);
    }

    void editBack() {
        if (editMoving) {
            JSONObject b = eb(editSel);
            try { b.put("x", editFromX); b.put("y", editFromY); } catch (Exception ignored) { }
            editMoving = false; editPreview(); drawEdit();
            return;
        }
        closeEdit(true);
    }

    void editChange() {
        editChanged = true;
        try { editLay.put("preset", ""); } catch (Exception ignored) { }
        editPreview(); drawEdit();
    }

    void editMenu(int keepIndex) {
        String id = editSel; JSONObject b = eb(id);
        List<Item> items = new ArrayList<>();
        items.add(new Item("Move", "e-move", null));
        JSONArray sizes = editSizes(id);
        for (int i = 0; i < sizes.length(); i++) {
            JSONArray z = sizes.optJSONArray(i);
            boolean cur = z.optInt(0) == b.optInt("w") && z.optInt(1) == b.optInt("h");
            items.add(new Item("Size: " + z.optString(2) + (cur ? "  ✓" : ""), "e-size", z.optInt(0) + "," + z.optInt(1)));
        }
        if (editIsWidgets(id)) {
            List<JSONObject> in = editWidgetsIn(id);
            for (int i = 0; i < editWidgets.length(); i++) {
                JSONObject w = editWidgets.optJSONObject(i);
                boolean on = false;
                for (JSONObject x : in) if (x.optString("id").equals(w.optString("id"))) on = true;
                items.add(new Item((on ? "✓  " : "+  ") + w.optString("emoji") + "  " + w.optString("name"), "e-widget", w.optString("id")));
            }
        }
        items.add(new Item("Hide from Home", "e-hide", null));
        items.add(new Item("Done", "cancel", null));
        menuKind = "edit";
        showMenu(editName(id), items);
        menuIndex = Math.min(keepIndex, items.size() - 1);
        renderMenu();
    }

    void editAddMenu() {
        List<Item> items = new ArrayList<>();
        JSONObject blocks = editLay.optJSONObject("blocks");
        java.util.Iterator<String> it = blocks.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!blocks.optJSONObject(k).optBoolean("on", true) && !editIsWidgets(k)) items.add(new Item("Show the " + editName(k).toLowerCase(Locale.ROOT), "e-show", k));
        }
        JSONArray wb = editSpec.optJSONArray("widget_blocks");
        for (int i = 0; i < editWidgets.length(); i++) {
            JSONObject w = editWidgets.optJSONObject(i);
            boolean placed = false;
            for (int j = 0; wb != null && j < wb.length(); j++) {
                JSONObject o = eb(wb.optString(j));
                if (o == null || !o.optBoolean("on", true)) continue;
                for (JSONObject x : editWidgetsIn(wb.optString(j))) if (x.optString("id").equals(w.optString("id"))) placed = true;
            }
            if (!placed) items.add(new Item(w.optString("emoji") + "  " + w.optString("name"), "e-addwidget", w.optString("id")));
        }
        if (items.isEmpty()) { toast("Everything is on Home already."); return; }
        items.add(new Item("Cancel", "cancel", null));
        menuKind = "edit";
        showMenu("Add to Home", items);
    }

    boolean editShow(String id) {
        JSONObject b = eb(id);
        try {
            if (!editFits(id, b.optInt("x"), b.optInt("y"), b.optInt("w"), b.optInt("h"))) {
                JSONArray sizes = editSizes(id);
                int cols = editSpec.optInt("cols", 12), rows = editSpec.optInt("rows", 6);
                boolean found = false;
                for (int s = 0; s < sizes.length() && !found; s++) {
                    int w = sizes.optJSONArray(s).optInt(0), h = sizes.optJSONArray(s).optInt(1);
                    for (int y = 0; y + h <= rows && !found; y++)
                        for (int x = 0; x + w <= cols && !found; x++)
                            if (editFits(id, x, y, w, h)) { b.put("x", x); b.put("y", y); b.put("w", w); b.put("h", h); found = true; }
                }
                if (!found) { toast("No room for the " + editName(id).toLowerCase(Locale.ROOT) + ": hide or shrink something first."); return false; }
            }
            b.put("on", true);
        } catch (Exception ignored) { }
        editSel = id;
        return true;
    }

    void editSetWidget(String blockId, String wid, boolean on) {
        try {
            JSONObject b = eb(blockId);
            JSONArray picks = b.optJSONArray("addons");
            if (picks == null || picks.length() == 0) {   // keep what it showed
                picks = new JSONArray();
                for (JSONObject w : editWidgetsIn(blockId)) picks.put(w.optString("id"));
            }
            JSONArray wb = editSpec.optJSONArray("widget_blocks");
            for (int j = 0; wb != null && j < wb.length(); j++) {   // one block per widget
                JSONObject o = eb(wb.optString(j)); if (o == null) continue;
                JSONArray p = wb.optString(j).equals(blockId) ? picks : o.optJSONArray("addons");
                JSONArray keep = new JSONArray();
                for (int i = 0; p != null && i < p.length(); i++) if (!p.optString(i).equals(wid)) keep.put(p.optString(i));
                if (wb.optString(j).equals(blockId)) picks = keep; else o.put("addons", keep);
            }
            if (on) picks.put(wid);
            b.put("addons", picks);
            for (int i = 0; i < editWidgets.length(); i++) if (editWidgets.optJSONObject(i).optString("id").equals(wid)) editWidgets.optJSONObject(i).put("on", on);
            if (on) { editOn.add(wid); editOff.remove(wid); } else { editOff.add(wid); editOn.remove(wid); }
        } catch (Exception ignored) { }
    }

    void editChoose(Item it, int index) {
        String id = editSel; JSONObject b = eb(id);
        try {
            switch (it.act) {
                case "e-move": editMoving = true; editFromX = b.optInt("x"); editFromY = b.optInt("y"); drawEdit(); break;
                case "e-size": {
                    String[] wh = it.arg.split(",");
                    int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
                    if (!editFits(id, b.optInt("x"), b.optInt("y"), w, h)) { toast("No room for that size here: move it first."); break; }
                    b.put("w", w); b.put("h", h); editChange();
                    break;
                }
                case "e-hide": {
                    b.put("on", false);
                    List<String> shown = editShown();
                    editSel = shown.isEmpty() ? "add" : shown.get(0);
                    editChange();
                    break;
                }
                case "e-widget": {
                    boolean in = false;
                    for (JSONObject x : editWidgetsIn(id)) if (x.optString("id").equals(it.arg)) in = true;
                    editSetWidget(id, it.arg, !in); editChange(); editMenu(index);   // stay in the menu to pick more
                    break;
                }
                case "e-show": if (editShow(it.arg)) editChange(); break;
                case "e-addwidget": {
                    JSONArray wb = editSpec.optJSONArray("widget_blocks");
                    String target = null;
                    for (int j = 0; wb != null && j < wb.length() && target == null; j++) { JSONObject o = eb(wb.optString(j)); if (o != null && o.optBoolean("on", true)) target = wb.optString(j); }
                    if (target == null) { target = wb.optString(0); if (!editShow(target)) break; }
                    editSetWidget(target, it.arg, true); editSel = target; editChange();
                    break;
                }
                default: break;
            }
        } catch (Exception ignored) { }
    }

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
        area = menuKind.equals("edit") ? "edit" : !menuKind.equals("tile") ? "pills" : index < dockSize ? "dock" : "grid";
        menuKind = "tile";
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        menuBox.setVisibility(View.GONE);
        render();
    }

    void openTileMenu() {
        App app = focused();
        if (app == null || "item".equals(app.kind)) return;   // a row's picture: nothing to arrange
        menuApp = app; menuKind = "tile";
        List<Item> items = new ArrayList<>();
        if (index < dockSize) items.add(new Item("Move", "move", null));
        items.add(new Item("Remove from Home", "remove", null));
        if (prefs.getString("dock", null) != null) items.add(new Item("Reset the order", "reset-order", null));
        int n = removed().size();
        if (n > 0) items.add(new Item("Restore removed apps (" + n + ")", "restore", null));
        if ("app".equals(app.kind)) items.add(new Item("App info", "info", null));
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
        boolean wasEdit = menuKind.equals("edit");
        int at = menuIndex;
        closeMenu();
        if (wasEdit) { if (!it.act.equals("cancel")) editChoose(it, at); return; }
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
                miniClock.setText(clock.getText());
                ampm.setVisibility(cfg.optBoolean("clock_24h") ? View.GONE : View.VISIBLE);
                String day = DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], mon = MONTHS[c.get(Calendar.MONTH)];
                if (clockRows == 1) { day = day.substring(0, 3); mon = mon.substring(0, Math.min(3, mon.length())); }   // the one-row clock: "Tue 17 Nov"
                date.setText(day + "  " + c.get(Calendar.DAY_OF_MONTH) + " " + mon);
                String g = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
                String name = cfg.optString("greeting_name", "");
                greeting.setText(name.isEmpty() ? g : g + ", " + name);
                showQuote();   // a new one at midnight
                refreshWidgetTimes();   // countdowns
                long now = System.currentTimeMillis();
                if (now - lastWeather > 15 * 60_000) refreshWeather();
                if (now - lastWallpaper > 60 * 60_000) refreshWallpaper();
                if (now - lastAddons > 5 * 60_000) io.execute(MainActivity.this::refreshAddons);
            }
            // "Update right away" in the admin panel; off: changes show when Home opens again.
            if (cfg.optBoolean("live_refresh", true) && System.currentTimeMillis() - lastRev > 3_000) checkRev();
            if (resumed) ui.postDelayed(this, 1000);
        }
    };

    // Changes from the admin panel, checked every 3 s while Home is on screen: a save changes "saved" (or
    // restarts the helper: "started"): fetch the settings again; a new photo changes "wall".
    long lastRev = 0, revStarted = 0;
    double revSaved = -1;
    volatile boolean revBusy = false;
    String revWall = null;

    void checkRev() {
        lastRev = System.currentTimeMillis();
        if (!helperUp() || revBusy) return;
        revBusy = true;
        io.execute(() -> {
            try {
                JSONObject r = new JSONObject(get("/api/rev", 2500));
                long started = r.optLong("started");
                double saved = r.optDouble("saved", 0);
                String wall = r.optString("wall", "");
                ui.post(() -> {
                    boolean first = revStarted == 0;
                    if (!first && (started != revStarted || saved != revSaved)) refreshAll(false);
                    else if (!first && !wall.equals(revWall)) refreshWallpaper();
                    revStarted = started;
                    revSaved = saved;
                    revWall = wall;
                });
            } catch (Exception ignored) {
            } finally {
                revBusy = false;
            }
        });
    }

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
                // Saved in the admin panel since we last looked: the panel's dock and hidden apps win over
                // what was moved or removed here with the remote.
                long rev = c.optLong("rev", 0);
                SharedPreferences.Editor ed = prefs.edit().putString("cfg", c.toString());
                if (rev != prefs.getLong("rev", 0)) ed.remove("dock").remove("removed").putLong("rev", rev);
                ed.apply();
                reportApps();
                refreshAddons();
                ui.post(() -> {
                    boolean changed = !c.toString().equals(cfg.toString());
                    cfg = c;
                    if (changed || first) { lastMinute = -1; applyLayout(); applyWeatherVisibility(); loadApps(); build(); render(); tick.run(); }
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

    // Tells the helper which apps are installed here (names and small icons), for the admin panel.
    // Runs on the io thread; sent again only when the list changes.
    void reportApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            StringBuilder key = new StringBuilder();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                long v = 0;
                try { v = pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (Exception ignored) { }
                key.append(pkg).append(v).append(';');
            }
            String sig = Integer.toHexString(key.toString().hashCode());
            if (sig.equals(prefs.getString("reported", ""))) return;
            JSONArray list = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                boolean banner = ri.activityInfo.banner != 0 || ri.activityInfo.applicationInfo.banner != 0;
                JSONObject o = new JSONObject().put("pkg", pkg).put("name", String.valueOf(ri.loadLabel(pm))).put("banner", banner);
                Drawable d = null;
                ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                if (banner) { d = pm.getActivityBanner(cn); if (d == null) d = pm.getApplicationBanner(pkg); }
                if (d == null) d = pm.getActivityIcon(cn);
                int w = banner ? 240 : 128, h = banner ? 135 : 128;
                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, w, h);
                d.draw(new Canvas(bm));
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 100, png);
                bm.recycle();
                o.put("icon", android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP));
                list.put(o);
            }
            post("/api/cc-apps", new JSONObject().put("apps", list).toString(), 30_000);
            prefs.edit().putString("reported", sig).apply();
        } catch (Exception ignored) {
        }
    }

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
                if (!same || pillViews.size() != (fn != null ? 3 : 2)) { rebuildPills(); render(); }
            });
        });
    }

    // ---------- weather (Open-Meteo, through the helper) ----------
    static final int[][] WX_CODES = {{0}, {1, 2}, {3}, {45, 48}, {51, 53, 55, 56, 57}, {61, 63, 65, 66, 67, 80, 81, 82}, {71, 73, 75, 77, 85, 86}, {95, 96, 99}};
    static final String[] WX_LABEL = {"Clear", "Partly cloudy", "Cloudy", "Fog", "Drizzle", "Rain", "Snow", "Thunderstorm"};
    static final int[] WX_ICON = {R.drawable.wx_sun, R.drawable.wx_part, R.drawable.wx_cloud, R.drawable.wx_fog, R.drawable.wx_rain, R.drawable.wx_rain, R.drawable.wx_snow, R.drawable.wx_storm};

    void applyWeatherVisibility() {
        JSONObject lay = cfg.optJSONObject("layout"), blocks = lay == null ? null : lay.optJSONObject("blocks");
        boolean on = blocks == null || block(blocks, "weather").optBoolean("on", true);
        weatherBox.setVisibility(on && weatherBox.getTag() != null ? View.VISIBLE : View.GONE);
    }

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
                weatherBox.setTag("ok");
                applyWeatherVisibility();
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
        quoteText.getLayoutParams().width = pic != null ? quoteW - 150 : quoteW;
        quoteText.requestLayout();
        JSONObject lay = cfg.optJSONObject("layout"), blocks = lay == null ? null : lay.optJSONObject("blocks");
        quoteBox.setVisibility(blocks == null || block(blocks, "quote").optBoolean("on", true) ? View.VISIBLE : View.GONE);
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
                    for (TextView t : new TextView[]{greeting, clock, ampm, date, quoteText, quoteBy, quoteSrc, wxTemp, wxInfo, credit, heroName, miniClock}) shadow(t, true);
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
