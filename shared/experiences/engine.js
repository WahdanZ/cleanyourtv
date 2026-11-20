/* Clean Your TV: the engine every Home layout runs on (the layouts themselves are in layouts.js).
 *
 * One system, many presentations: a layout only arranges things and maps the remote's six keys. Everything it
 * shows or does comes from here, the same on the TV and the Chromecast:
 *   - the data, normalised from either back end by the page that hosts us (the "host", see HOST below)
 *   - items: one shape for an app, an input, Live TV, a scene, the player, today's photo ("source actions")
 *   - act(): opening one, with honest feedback, and remembering it for Resume
 *   - the 1920 x 1080 stage scaled to the screen, the top bar, the key hints, toasts
 *   - overlays every layout shares: All apps, Sources, the menu (Hold OK / Menu), today's photo
 *   - focus kept per layout, so it comes back where it was (after an app, a reload, a layout switch)
 *   - loading, empty and offline states, and reduced motion
 *
 * HOST (the page that mounts us) gives:
 *   host.platform           'vidaa' | 'cc' | 'demo'
 *   host.open(item)         -> Promise<{ok, error}>   an app, an input ('source'), Live TV
 *   host.scene(id)          -> Promise<{ok, said, error}>   only scenes allowed on Home
 *   host.media(key)         'play' | 'next' | 'prev'
 *   host.admin(), host.classic(), host.settings()   optional (missing: not offered)
 * and calls ctl.update(data) whenever its data changes, ctl.key(name) for each remote key.
 * Plain ES5 throughout: VIDAA's browser is old.
 */
(function (W) {
  'use strict';
  var C = W.CYTX_CATALOG;
  var E = { layouts: {}, catalog: C, version: 1 };
  W.CYTX = E;

  E.register = function (def) { E.layouts[def.id] = def; };

  // ---------------------------------------------------------------- small helpers
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function add(parent, child) { parent.appendChild(child); return child; }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  E.el = el; E.add = add; E.clamp = clamp;

  E.store = {
    get: function (k, def) { try { var v = W.localStorage.getItem(k); return v == null ? def : JSON.parse(v); } catch (e) { return def; } },
    set: function (k, v) { try { W.localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* private mode, full, blocked: fine */ } }
  };

  var DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  E.clock = function (h24, d) {
    d = d || new Date();
    var h = d.getHours(), m = d.getMinutes();
    return { time: h24 ? pad2(h) + ':' + pad2(m) : ((h % 12) || 12) + ':' + pad2(m), ampm: h24 ? '' : (h < 12 ? 'AM' : 'PM'),
             date: DAYS[d.getDay()] + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()], day: DAYS[d.getDay()], hhmm: pad2(h) + ':' + pad2(m) };
  };
  var WX = { 0: 'Clear', 1: 'Mostly clear', 2: 'Partly cloudy', 3: 'Cloudy', 45: 'Fog', 48: 'Fog', 51: 'Drizzle', 53: 'Drizzle', 55: 'Drizzle',
             56: 'Freezing drizzle', 57: 'Freezing drizzle', 61: 'Rain', 63: 'Rain', 65: 'Heavy rain', 66: 'Freezing rain', 67: 'Freezing rain',
             71: 'Snow', 73: 'Snow', 75: 'Heavy snow', 77: 'Snow', 80: 'Showers', 81: 'Showers', 82: 'Heavy showers', 85: 'Snow showers',
             86: 'Snow showers', 95: 'Thunderstorm', 96: 'Thunderstorm', 99: 'Thunderstorm' };
  E.weather = function (w) {
    if (!w || w.temp == null) return '';
    return Math.round(w.temp) + '°' + (WX[w.code] ? ' · ' + WX[w.code] : '');
  };
  E.until = function (ms) {
    var min = Math.round((ms - Date.now()) / 60000);
    if (min <= 0) return 'now';
    if (min < 60) return 'in ' + min + ' min';
    var h = Math.floor(min / 60);
    return h < 24 ? 'in ' + h + ' h' + (min % 60 ? ' ' + (min % 60) + ' min' : '') : 'in ' + Math.round(h / 24) + ' days';
  };
  E.hhmm = function (ms) { var d = new Date(ms); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };

  // ---------------------------------------------------------------- items: one shape for every destination
  // { type: 'app' | 'source' | 'scene' | 'np' | 'photo' | 'all' | 'sources' | 'none', id, name, sub, icon, color,
  //   badge ('APP' | 'HDMI' | 'LIVE' | 'INPUT' | 'SCENE' | 'PLAYING'), verb (what OK does, said on screen), why (for 'none') }
  var PALETTE = ['#2a6f97', '#7a3b69', '#2f7d6d', '#8a5a2b', '#3f4f9a', '#7d2f3b', '#466b2f', '#5b4a8a'];
  function colorFor(s) { var h = 0; s = String(s || ''); for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return PALETTE[Math.abs(h) % PALETTE.length]; }
  var LISTEN = /spotify|music|deezer|tidal|soundcloud|anghami|tunein|radio|podcast|audible|pandora|karaoke|yokee|shazam/i;
  var PLAY = /game|geforce|steam ?link|xbox|playstation|ps remote|stadia|luna|boosteroid|arcade|chess|retro|emulator/i;

  function appItem(a) {
    return { type: 'app', id: a.id, name: a.name, icon: a.icon || '', color: a.color || colorFor(a.id), badge: a.kind === 'web' ? 'WEB' : 'APP',
             sub: a.sub || (a.kind === 'web' ? 'Website' : a.kind === 'page' ? 'Built-in screen' : a.kind === 'addon' ? 'Add-on' : 'App'),
             verb: 'Open', kind: a.kind || 'app' };
  }
  function sourceItem(s) {
    var live = s.kind === 'live';
    return { type: 'source', id: s.id, name: s.name, icon: '', color: live ? '#1f6f8b' : '#3a4f73', badge: live ? 'LIVE' : (/hdmi/i.test(s.id) ? 'HDMI' : 'INPUT'),
             sub: s.label || (live ? 'Broadcast' : /hdmi/i.test(s.id) ? 'HDMI input' : 'Input'), verb: 'Switch', kind: s.kind };
  }
  E.colorFor = colorFor;
  E.items = {
    app: function (d, id) {
      for (var i = 0; i < d.apps.length; i++) if (d.apps[i].id === id) return appItem(d.apps[i]);
      return null;
    },
    all: function (d) { return d.apps.map(appItem); },
    favorites: function (d) {
      var out = [];
      d.favorites.forEach(function (id) { var it = E.items.app(d, id); if (it) out.push(it); });
      if (!out.length) out = d.apps.slice(0, 6).map(appItem);   // nothing chosen yet: the first apps, not an empty Home
      return out;
    },
    // Inputs the back end reported (never invented): HDMI, DisplayPort, AV… Live TV is separate (live()).
    sources: function (d) { return (d.sources || []).filter(function (s) { return s.kind !== 'live' && s.kind !== 'other'; }).map(sourceItem); },
    live: function (d) {
      var s = (d.sources || []).filter(function (x) { return x.kind === 'live'; })[0];
      if (s) return sourceItem(s);
      var a = d.apps.filter(function (x) { return x.live; })[0];   // a Live TV app (the free channels page, a TV app)
      if (a) { var it = appItem(a); it.badge = 'LIVE'; it.sub = 'Live TV'; return it; }
      return null;
    },
    scenes: function (d) {
      return (d.scenes || []).map(function (s) {
        return { type: 'scene', id: s.id, name: s.name, icon: '', emoji: s.icon || '✦', color: colorFor('scene' + s.id), badge: 'SCENE',
                 sub: s.summary || 'Scene', verb: 'Start' };
      });
    },
    np: function (d) {
      var p = d.np;
      if (!p || !p.title) return null;
      return { type: 'np', id: 'np', name: p.title, sub: (p.subtitle ? p.subtitle + ' · ' : '') + (p.name || 'Playing'), icon: p.art || p.icon || '',
               color: '#2f4858', badge: p.state === 'playing' ? 'PLAYING' : 'PAUSED', verb: p.state === 'playing' ? 'Pause' : 'Play', app: p.app, np: p };
    },
    photo: function (d) {
      if (!d.wallpaper) return null;
      return { type: 'photo', id: 'photo', name: 'Today\'s photo', sub: d.wallpaperTitle || 'Picture of the day', icon: d.wallpaper, color: '#4a3b2a', badge: 'PHOTO', verb: 'Look at' };
    },
    allApps: function () { return { type: 'all', id: 'all', name: 'All apps', sub: 'Everything installed', color: '#26394d', badge: '', verb: 'Show', glyph: '⋯' }; },
    sourcesItem: function () { return { type: 'sources', id: 'sources', name: 'Sources', sub: 'Inputs and Live TV', color: '#26394d', badge: '', verb: 'Show', glyph: '⇄' }; },
    none: function (name, why) { return { type: 'none', id: 'none-' + name, name: name, sub: why, why: why, color: '#22313f', badge: '', verb: '' }; },
    // The apps opened from this Home, newest first: real and local, never a guess at an app's own history.
    recent: function (d) {
      var out = [], seen = {};
      (E.store.get('cytx-recent', []) || []).forEach(function (r) {
        if (seen[r.type + r.id]) return;
        var it = r.type === 'app' ? E.items.app(d, r.id) : r.type === 'source' ? E.items.sourceById(d, r.id) : null;
        if (it) { it.at = r.at; seen[r.type + r.id] = 1; out.push(it); }
      });
      return out;
    },
    sourceById: function (d, id) {
      var s = (d.sources || []).filter(function (x) { return x.id === id; })[0];
      return s ? sourceItem(s) : null;
    },
    // Watch / Play / Listen / Sources. The panel can set each list (opts.groups); otherwise a fixed list of well-known
    // names decides Play and Listen, and the rest is Watch. Nothing is read from the device's behaviour.
    groups: function (d, opts) {
      var g = (opts && opts.groups) || {}, all = d.apps, pick = function (ids) {
        var out = []; (ids || []).forEach(function (id) { var it = E.items.app(d, id); if (it) out.push(it); }); return out;
      };
      var listen = g.listen && g.listen.length ? pick(g.listen) : all.filter(function (a) { return LISTEN.test(a.name); }).map(appItem);
      var play = g.play && g.play.length ? pick(g.play) : all.filter(function (a) { return PLAY.test(a.name); }).map(appItem);
      var taken = {}; listen.concat(play).forEach(function (i) { taken[i.id] = 1; });
      var watch = g.watch && g.watch.length ? pick(g.watch)
        : E.items.favorites(d).concat(all.map(appItem)).filter(function (i) { if (taken[i.id]) return false; taken[i.id] = 1; return true; });
      var live = E.items.live(d), sources = (live ? [live] : []).concat(E.items.sources(d));
      return { watch: watch, play: play, listen: listen, sources: sources };
    },
    // Upcoming moments from the widgets that carry a time (football kick-offs, the next prayer); nothing else.
    upcoming: function (d) {
      var out = [], now = Date.now();
      (d.widgets || []).forEach(function (w) {
        (w.matches || []).forEach(function (m) {
          var at = m.when || m.at || m.kickoff;
          if (typeof at === 'number' && at > now - 2 * 3600e3 && at < now + 36 * 3600e3)
            out.push({ at: at, name: (m.home && m.away) ? (m.home.name || m.home) + ' – ' + (m.away.name || m.away) : (m.title || w.name || 'Match'),
                       live: !!m.live, source: w.name || 'Football', kind: 'match' });
        });
        if (w.next && w.when) out.push({ at: w.when, name: w.next + (w.at ? ' · ' + w.at : ''), source: w.name || 'Prayer times', kind: 'prayer' });
      });
      return out.sort(function (a, b) { return a.at - b.at; });
    }
  };

  E.remember = function (item) {
    if (item.type !== 'app' && item.type !== 'source') return;
    var list = (E.store.get('cytx-recent', []) || []).filter(function (r) { return !(r.type === item.type && r.id === item.id); });
    list.unshift({ type: item.type, id: item.id, at: Date.now() });
    E.store.set('cytx-recent', list.slice(0, 12));
  };

  // Icons adapt to the tile: a wide tile may show an app's banner edge to edge; a round, square or tall one shows
  // its icon whole, fitted, on its colour (a banner there is fitted too, never cropped to a fragment).
  // An icon theme (an "icons" add-on, addons/README.md) restyles every app's icon: its shape, the background, grey,
  // a tint, and its own pictures for some apps. E.theme is the active one ({style, icons}), or null.
  E.theme = null;
  function themeSrc(item) {
    var t = E.theme;
    if (!t || !t.icons || item.type !== 'app') return '';
    return t.icons[String(item.id).toLowerCase()] || t.icons[String(item.name).toLowerCase()] || '';
  }
  // ---------- icons that fit their shape ----------
  // An app's artwork is redrawn for the tile it's in (a "designed" icon, made from the app's own picture):
  //   1. the picture's own background colour is read from its edges (Netflix: black, YouTube: white…);
  //   2. the logo is cut out of it (whatever differs from that colour);
  //   3. the whole shape (a circle, a frame, a square) is filled with that colour, and the logo set in it as large
  //      as fits inside the shape (in a circle: its corners within the circle).
  // So a banner never shows as a rectangle pasted on a colour. An icon theme adds its plate on top: the theme's
  // background around, the app's colour in the theme's shape, the logo inside. A wide tile with no theme keeps the
  // banner edge to edge. Done once per picture and shape (cached); without canvas access, the picture is fitted.
  var FIT = {};
  var CANVAS = { round: [320, 320], square: [320, 320], tall: [280, 400], wide: [384, 216] };
  function shapePath(x, kind, l, t, w, h) {
    x.beginPath();
    if (kind === 'round') { x.ellipse(l + w / 2, t + h / 2, w / 2, h / 2, 0, 0, Math.PI * 2); return; }
    var r = kind === 'squircle' ? Math.min(w, h) * 0.24 : kind === 'keep' ? Math.min(w, h) * 0.14 : 0;
    x.moveTo(l + r, t); x.arcTo(l + w, t, l + w, t + h, r); x.arcTo(l + w, t + h, l, t + h, r); x.arcTo(l, t + h, l, t, r); x.arcTo(l, t, l + w, t, r);
    x.closePath();
  }
  function fitIcon(img, shape, th, fallback) {
    var key = img.src + '|' + shape + '|' + (th ? JSON.stringify(th) : '');
    if (FIT[key] !== undefined) return FIT[key];
    var out = null;
    try {
      var sw = img.naturalWidth, sh = img.naturalHeight;
      if (!sw || !sh) return null;
      var k = Math.min(1, 256 / Math.max(sw, sh)), w = Math.max(1, Math.round(sw * k)), h = Math.max(1, Math.round(sh * k));
      var c = document.createElement('canvas'); c.width = w; c.height = h;
      var x = c.getContext('2d'); x.drawImage(img, 0, 0, w, h);
      var px = x.getImageData(0, 0, w, h).data;
      var rs = [], gs = [], bs = [], clear = 0, n = 0, i, j, o;
      var edge = function (a, b) { o = (b * w + a) * 4; n++; if (px[o + 3] < 40) { clear++; return; } rs.push(px[o]); gs.push(px[o + 1]); bs.push(px[o + 2]); };
      for (i = 0; i < w; i++) { edge(i, 0); edge(i, h - 1); }
      for (j = 0; j < h; j++) { edge(0, j); edge(w - 1, j); }
      var med = function (a) { a.sort(function (p, q) { return p - q; }); return a[a.length >> 1]; };
      var bg = clear < n / 2 && rs.length ? [med(rs), med(gs), med(bs)] : null;
      var x0 = w, y0 = h, x1 = -1, y1 = -1;
      for (j = 0; j < h; j++) for (i = 0; i < w; i++) {
        o = (j * w + i) * 4;
        if (px[o + 3] < 40) continue;
        if (bg && Math.abs(px[o] - bg[0]) + Math.abs(px[o + 1] - bg[1]) + Math.abs(px[o + 2] - bg[2]) < 90) continue;
        if (i < x0) x0 = i; if (i > x1) x1 = i; if (j < y0) y0 = j; if (j > y1) y1 = j;
      }
      if (x1 < 0) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1; }
      var lw = x1 - x0 + 1, lh = y1 - y0 + 1, brand = bg ? 'rgb(' + bg.join(',') + ')' : fallback;
      var W = CANVAS[shape][0], H = CANVAS[shape][1];
      var oc = document.createElement('canvas'); oc.width = W; oc.height = H;
      var y = oc.getContext('2d');
      y.fillStyle = th ? (th.background === 'brand' ? brand : th.background === 'none' ? 'rgba(0,0,0,0)' : th.background) : brand;
      y.fillRect(0, 0, W, H);
      var plate = shape === 'round' ? 'round' : 'rect', L = 0, T = 0, PW = W, PH = H, pad = shape === 'round' ? 0.18 : 0.14;
      if (th) {   // the theme's plate: its shape, the app's colour
        var side = Math.min(W, H) * (shape === 'round' ? 0.78 : 0.82);
        L = (W - side) / 2; T = (H - side) / 2; PW = PH = side; plate = th.shape === 'round' ? 'round' : th.shape; pad = th.padding;
        shapePath(y, plate, L, T, PW, PH); y.fillStyle = brand; y.fill();
      }
      y.save(); shapePath(y, plate, L, T, PW, PH); y.clip();
      var s = plate === 'round' ? (Math.min(PW, PH) / 2 * (1 - pad)) / Math.sqrt(lw * lw / 4 + lh * lh / 4)
                                : Math.min(PW * (1 - 2 * pad) / lw, PH * (1 - 2 * pad) / lh);
      y.drawImage(c, x0, y0, lw, lh, L + (PW - lw * s) / 2, T + (PH - lh * s) / 2, lw * s, lh * s);
      y.restore();
      if (th && th.monochrome) {
        var d = y.getImageData(0, 0, W, H), q = d.data;
        for (i = 0; i < q.length; i += 4) { var g = Math.round(q[i] * 0.3 + q[i + 1] * 0.59 + q[i + 2] * 0.11); q[i] = q[i + 1] = q[i + 2] = g; }
        y.putImageData(d, 0, 0);
      }
      out = oc.toDataURL('image/png');
    } catch (e) { out = null; }   // a picture from elsewhere (no canvas access): fitted as it is
    FIT[key] = out;
    return out;
  }
  E.fitIcon = fitIcon;

  // A tile: the icon if it loads, else the initial on its colour. The name is always written under it.
  // shape: 'wide' (default), 'round', 'square' or 'tall'.
  E.tile = function (item, cls, shape) {
    shape = shape || 'wide';
    var t = el('div', 'x-tile ' + (cls || ''));
    var art = add(t, el('div', 'x-art'));
    art.style.background = item.color || '#22313f';
    var th = E.theme && item.type === 'app' ? E.theme.style : null, own = themeSrc(item), src = own || item.icon;
    if (src && item.type !== 'photo') {
      var designed = !!th || shape !== 'wide';
      var img = add(art, el('img', designed ? 'x-fill x-wait' : 'x-fill'));
      img.alt = '';
      if (th && th.tint && own) {   // the theme's own picture as a tinted silhouette on its plate
        var sil = add(art, el('div', 'x-ic x-sil'));
        sil.style.width = sil.style.height = Math.round(100 - th.padding * 200) + '%'; sil.style.background = th.tint;
        sil.style.webkitMaskImage = sil.style.maskImage = 'url("' + own + '")';
        art.style.background = th.background === 'brand' ? item.color : th.background;
        img.style.display = 'none';
      } else if (designed) {
        img.onload = function () {
          var url = fitIcon(img, shape === 'wide' ? 'wide' : shape, th, item.color || '#22313f');
          img.onload = null;
          if (url) img.src = url;
          else { img.className = 'x-ic x-ic-fit'; img.style.width = '80%'; img.style.height = '70%'; if (th && th.monochrome) img.style.filter = 'grayscale(1)'; }
          img.classList.remove('x-wait');
        };
      }
      img.onerror = function () { art.innerHTML = ''; add(art, el('span', 'x-mono', mono(item))); };
      img.src = src;
    } else if (item.type === 'photo' && item.icon) {
      art.style.backgroundImage = 'url("' + item.icon + '")';
      art.style.backgroundSize = 'cover';
      art.style.backgroundPosition = 'center';
    } else add(art, el('span', 'x-mono', mono(item)));
    add(t, el('div', 'x-name', item.name));
    return t;
  };
  function mono(item) {
    if (item.glyph) return item.glyph;
    if (item.emoji) return item.emoji;
    if (item.type === 'source') return item.badge === 'LIVE' ? 'TV' : (String(item.id).match(/\d+/) || [item.name.charAt(0)])[0];
    return String(item.name || '?').replace(/[^A-Za-z0-9À-ɏ؀-ۿ]/g, '').charAt(0).toUpperCase() || '•';
  }
  E.mono = mono;

  // Marks the one focused element (the focus ring) in a list.
  E.mark = function (list, i, on) {
    for (var k = 0; k < list.length; k++) list[k].classList.toggle('x-f', on !== false && k === i);
  };

  // ---------------------------------------------------------------- the controller: one mounted layout
  E.mount = function (root, host, data) {
    var ctl = { root: root, host: host, data: null, layout: null, id: '', opts: {}, overlay: null, timers: [] };
    root.className = 'cytx';
    root.innerHTML = '';
    var stage = add(root, el('div', 'x-stage'));
    var bg = add(stage, el('div', 'x-bg'));
    var content = add(stage, el('div', 'x-content'));
    var top = add(stage, el('div', 'x-top'));
    var brand = add(top, el('div', 'x-brand'));
    brand.innerHTML = '<b>CLEAN</b> <span>YOUR TV</span><small></small>';
    var right = add(top, el('div', 'x-topr'));
    var status = add(right, el('div', 'x-status'));
    var clockEl = add(right, el('div', 'x-clock'));
    var hints = add(stage, el('div', 'x-hints'));
    var layer = add(stage, el('div', 'x-layer'));
    var toastEl = add(stage, el('div', 'x-toast'));
    ctl.stage = stage; ctl.bg = bg; ctl.content = content; ctl.hintsEl = hints; ctl.top = top;

    function fit() {
      var w = W.innerWidth || 1920, h = W.innerHeight || 1080, k = Math.min(w / 1920, h / 1080);
      stage.style.transform = 'scale(' + k + ')';
      stage.style.left = Math.round((w - 1920 * k) / 2) + 'px';
      stage.style.top = Math.round((h - 1080 * k) / 2) + 'px';
    }
    fit();
    W.addEventListener('resize', fit);

    var toastTimer = 0;
    ctl.toast = function (msg) {
      toastEl.textContent = msg;
      toastEl.classList.add('show');
      clearTimeout(toastTimer);
      toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 3500);
    };

    function tick() {
      if (!ctl.data) return;
      var c = E.clock(ctl.data.clock24);
      clockEl.innerHTML = '';
      add(clockEl, el('b', '', c.time));
      if (c.ampm) add(clockEl, el('small', '', c.ampm));
      if (ctl.layout && ctl.layout.tick) ctl.layout.tick(ctx());
    }
    ctl.timers.push(setInterval(tick, 15000));

    // The context a layout gets: where to draw, the data, its options and its kept state.
    function ctx() {
      return { E: E, ctl: ctl, data: ctl.data, opts: ctl.opts, state: ctl.state, content: content, stage: stage, bg: bg,
               save: save, act: act, toast: ctl.toast, still: root.classList.contains('x-still') };
    }
    function save() { if (ctl.id) E.store.set('cytx-state-' + ctl.id + '-' + host.platform, ctl.state); }

    // Opening something: feedback on screen, the honest result, and Resume remembers it.
    function act(item, el) {
      if (!item) return;
      if (el) { el.classList.add('x-press'); setTimeout(function () { el.classList.remove('x-press'); }, 160); }
      save();
      if (item.type === 'none') { ctl.toast(item.why || 'Not available here'); return; }
      if (item.type === 'all') { openGrid('All apps', E.items.all(ctl.data)); return; }
      if (item.type === 'sources') { openSources(); return; }
      if (item.type === 'photo') { openPhoto(); return; }
      if (item.type === 'np') { if (host.media) host.media('play'); return; }
      if (item.type === 'scene') {
        if (!host.scene) { ctl.toast('Scenes need the phone panel here'); return; }
        ctl.toast(item.name + '…');
        host.scene(item.id).then(function (r) { ctl.toast(r && r.ok ? (r.said || item.name + ': on its way') : 'Did not start: ' + ((r && r.error) || 'no answer')); },
                                 function () { ctl.toast('Could not reach Home\'s server: the scene did not start'); });
        return;
      }
      if (item.type === 'source' && !(ctl.data.caps && ctl.data.caps.sources)) { ctl.toast('Switching inputs needs the helper, which isn\'t reachable'); return; }
      E.remember(item);
      ctl.toast((item.type === 'source' ? 'Switching to ' : 'Opening ') + item.name + '…');
      var p = host.open(item);
      if (p && p.then) p.then(function (r) { if (r && r.ok === false) ctl.toast('Could not open ' + item.name + (r.error ? ': ' + r.error : '')); },
                              function () { ctl.toast('Could not reach Home\'s server, so ' + item.name + ' did not open'); });
    }
    ctl.act = act;

    // ---------------- overlays: one at a time, on top; Back closes and the layout's focus is where it was
    // While an overlay is up the layout under it is hidden: one focus on screen, never two.
    function closeOverlay() { if (ctl.overlay) { layer.innerHTML = ''; layer.className = 'x-layer'; ctl.overlay = null; content.style.visibility = ''; } }
    function cover() { content.style.visibility = 'hidden'; }
    function openGrid(title, items, opts) {
      closeOverlay();
      opts = opts || {};
      var cols = opts.cols || 6, i = 0;
      layer.className = 'x-layer on x-grid-layer';
      cover();
      var box = add(layer, el('div', 'x-panel'));
      add(box, el('div', 'x-ptitle', title));
      if (!items.length) { add(box, el('div', 'x-empty', opts.empty || 'Nothing here yet.')); }
      var grid = add(box, el('div', 'x-pgrid'));
      var cells = items.map(function (it) { return add(grid, E.tile(it, 'x-small')); });
      function paint() {
        E.mark(cells, i);
        if (cells[i]) { var r = Math.floor(i / cols); grid.style.transform = 'translateY(' + (-Math.max(0, r - 2) * 236) + 'px)'; }
      }
      paint();
      ctl.overlay = { key: function (k) {
        if (k === 'back') { closeOverlay(); return true; }
        if (!items.length) return true;
        if (k === 'left') i = Math.max(0, i - 1);
        else if (k === 'right') i = Math.min(items.length - 1, i + 1);
        else if (k === 'up') { if (i - cols >= 0) i -= cols; else if (opts.upClose) { closeOverlay(); return true; } }
        else if (k === 'down') i = Math.min(items.length - 1, i + cols);
        else if (k === 'ok') { var it = items[i]; closeOverlay(); act(it); return true; }
        paint();
        return true;
      } };
    }
    function openSources() {
      var d = ctl.data, live = E.items.live(d), list = (live ? [live] : []).concat(E.items.sources(d));
      openGrid('Sources', list, { cols: 4, empty: d.caps && d.caps.sources ? 'The TV reported no inputs.' : 'Inputs need the helper (a VIDAA TV), which isn\'t reachable from here.' });
    }
    function openPhoto() {
      closeOverlay();
      var d = ctl.data;
      layer.className = 'x-layer on x-photo-layer';
      cover();
      var ph = add(layer, el('div', 'x-photo'));
      if (d.wallpaper) ph.style.backgroundImage = 'url("' + d.wallpaper + '")';
      add(ph, el('div', 'x-photo-cap', d.wallpaperTitle || 'Today\'s photo'));
      ctl.overlay = { key: function (k) { if (k === 'back' || k === 'ok') closeOverlay(); return true; } };
    }
    function openMenu() {
      closeOverlay();
      var rows = [{ label: 'All apps', run: function () { openGrid('All apps', E.items.all(ctl.data)); } }];
      rows.push({ label: 'Sources', run: openSources });
      if (host.admin) rows.push({ label: 'Phone panel (change the layout there)', run: host.admin });
      if (host.classic) rows.push({ label: 'Classic Home, until Home restarts', run: host.classic });
      if (host.settings) rows.push({ label: 'Settings', run: host.settings });
      var i = 0;
      layer.className = 'x-layer on x-menu-layer';
      cover();
      var box = add(layer, el('div', 'x-menu'));
      add(box, el('div', 'x-ptitle', (C.get(ctl.id) || {}).name || 'Home'));
      var els = rows.map(function (r) { return add(box, el('div', 'x-mrow', r.label)); });
      E.mark(els, 0);
      ctl.overlay = { key: function (k) {
        if (k === 'back' || k === 'menu') { closeOverlay(); return true; }
        if (k === 'up') i = Math.max(0, i - 1);
        else if (k === 'down') i = Math.min(rows.length - 1, i + 1);
        else if (k === 'ok') { var r = rows[i]; closeOverlay(); r.run(); return true; }
        E.mark(els, i);
        return true;
      } };
    }
    ctl.openGrid = openGrid; ctl.openSources = openSources; ctl.openMenu = openMenu; ctl.closeOverlay = closeOverlay;

    // ---------------- keys: overlay first, then the menu and media keys, then the layout's own map
    ctl.key = function (k) {
      if (!ctl.data) return k !== 'back';
      if (ctl.overlay) return ctl.overlay.key(k);
      if (k === 'menu') { openMenu(); return true; }
      if (k === 'play' || k === 'next' || k === 'prev') { if (host.media) host.media(k); return true; }
      if (/^[0-9]$/.test(k) && ctl.layout.digit) return ctl.layout.digit(ctx(), +k);
      if (E.catalog.keys.indexOf(k) < 0) return false;
      var used = ctl.layout.key(k, ctx());
      save();
      return used !== false;
    };

    function hintText(id) {
      var lay = E.layouts[id] || {}, h = lay.hints || [];
      return h.concat(['Hold OK · Menu']).join('   •   ');
    }

    // ---------------- (re)drawing
    function draw() {
      var d = ctl.data, exp = (d.experience && d.experience.id) || 'theater';
      if (!E.layouts[exp]) exp = 'theater';
      var lay = E.layouts[exp];
      if (ctl.id !== exp) {
        if (ctl.layout && ctl.layout.leave) ctl.layout.leave(ctx());
        ctl.id = exp;
        ctl.layout = lay;
        var kept = E.store.get('cytx-state-' + exp + '-' + host.platform, null);
        ctl.state = kept && typeof kept === 'object' ? kept : {};
        closeOverlay();
      }
      var info = C.get(exp) || {}, opts = {}, given = (d.experience.opts || {})[exp] || {};
      (info.options || []).concat(C.common).forEach(function (o) { opts[o.key] = given[o.key] != null ? given[o.key] : o.def; });
      if (exp === 'scenes' || info.variantOf) opts.variant = exp;
      ctl.opts = opts;
      brand.lastChild.textContent = info.name || '';
      hints.textContent = hintText(exp);
      frame();
      content.innerHTML = '';
      lay.render(ctx());
      tick();
    }

    // What changes with the data around the layout: motion, offline, today's photo behind it.
    function frame() {
      var d = ctl.data, lay = ctl.layout, still = ctl.opts.motion === 'reduced' || (W.matchMedia && W.matchMedia('(prefers-reduced-motion: reduce)').matches);
      var cls = 'cytx x-l-' + (lay.css || ctl.id) + ' x-v-' + ctl.id + (still ? ' x-still' : '') + (d.online === false ? ' x-offline' : '');
      if (root.classList.contains('x-revealed')) cls += ' x-revealed';
      root.className = cls;
      status.textContent = d.online === false ? 'Server offline: apps may not open' : '';
      status.className = 'x-status' + (d.online === false ? ' on' : '');
      bg.style.backgroundImage = lay.photo && d.wallpaper ? 'url("' + d.wallpaper + '")' : '';
    }

    ctl.update = function (d) {
      if (!d) return;
      d.apps = d.apps || []; d.favorites = d.favorites || []; d.sources = d.sources || []; d.caps = d.caps || {};
      d.experience = d.experience || { id: 'theater', opts: {} };
      E.theme = d.iconTheme && d.iconTheme.style ? d.iconTheme : null;
      var first = !ctl.data;
      ctl.data = d;
      if (first || !ctl.layout || ctl.id !== d.experience.id || !ctl.layout.refresh) draw();
      else { frame(); content.innerHTML = ''; ctl.layout.refresh(ctx()); tick(); }   // new data, same layout: focus stays exactly where it is
    };
    ctl.redraw = function () { if (ctl.data) draw(); };
    ctl.destroy = function () { ctl.timers.forEach(clearInterval); W.removeEventListener('resize', fit); root.innerHTML = ''; };

    if (data) ctl.update(data);
    else { add(content, el('div', 'x-loading', 'Loading your Home…')); }
    return ctl;
  };

  // Maps a key event to one of our names (TV remotes send odd codes). Hosts with their own mapping skip this.
  E.keyName = function (e) {
    var k = e.keyCode;
    if (k === 37) return 'left'; if (k === 39) return 'right'; if (k === 38) return 'up'; if (k === 40) return 'down';
    if (k === 13) return 'ok';
    if (k === 8 || k === 461 || k === 27 || k === 10009) return 'back';
    if (k === 93 || k === 18 || k === 457) return 'menu';
    if (k === 415 || k === 19 || k === 179 || k === 10252) return 'play';
    if (k === 425 || k === 176) return 'next';
    if (k === 424 || k === 177) return 'prev';
    if (k >= 48 && k <= 57) return String(k - 48);
    return '';
  };
})(window);
