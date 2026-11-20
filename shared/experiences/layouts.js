/* Clean Your TV: the Home layouts. Each one arranges the same items (engine.js) differently and maps the remote's
 * keys its own way; the key maps follow the "D-pad & remote focus map" of each concept in the research atlas
 * (CleanYourTV_10_TV_UI_Layout_Concepts.pdf, pages 6-24), noted per layout below.
 *
 * A layout is { id, hints, photo, render(c), key(k, c), refresh(c), tick(c), digit(c, n) }:
 *   render draws into c.content; key gets 'up' 'down' 'left' 'right' 'ok' 'back' and returns false when unused;
 *   refresh redraws for new data without moving focus; c.state is kept (per layout, per device) for focus restoring.
 * Focus is restored by item id, so a new app in the list doesn't move it to something else.
 * Plain ES5: VIDAA's browser is old.
 */
(function (W) {
  'use strict';
  var E = W.CYTX, el = E.el, add = E.add, clamp = E.clamp;

  function indexOfId(list, id, def) {
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return i;
    return def || 0;
  }
  function header(c, kicker, title, sub) {
    var h = add(c.content, el('div', 'x-head'));
    if (kicker) add(h, el('div', 'x-kicker', kicker));
    add(h, el('div', 'x-title', title));
    if (sub) add(h, el('div', 'x-sub', sub));
    return h;
  }
  function verbLine(item) {
    if (!item) return '';
    return 'OK  ' + (item.type === 'none' ? '' : item.verb ? item.verb.toUpperCase() + ' ' : '') + (item.type === 'none' ? item.why : item.name);
  }
  // A strip of tiles; returns the tile elements.
  function strip(parent, items, cls) {
    var row = add(parent, el('div', 'x-strip ' + (cls || '')));
    return items.map(function (it) { return add(row, E.tile(it, 'x-small')); });
  }
  function nowCard(parent, np, cls) {
    var card = add(parent, el('div', 'x-np ' + (cls || '')));
    if (!np) return card;
    var art = add(card, el('div', 'x-np-art'));
    if (np.icon) art.style.backgroundImage = 'url("' + np.icon + '")';
    var t = add(card, el('div', 'x-np-text'));
    add(t, el('div', 'x-np-k', (np.np.state === 'playing' ? 'Now playing' : 'Paused') + (np.np.name ? ' · ' + np.np.name : '')));
    add(t, el('div', 'x-np-t', np.name));
    if (np.np.subtitle) add(t, el('div', 'x-np-s', np.np.subtitle));
    add(card, el('div', 'x-np-b', np.np.state === 'playing' ? '❚❚' : '▶'));
    return card;
  }

  // =============================================================================================== 01 Liquid OS
  // PDF p.6: LEFT/RIGHT previous/next favourite, UP sources, DOWN all apps, OK launch. Fixed order; the focused bubble
  // grows and its neighbours yield, by transforms only (no physics, no blur).
  E.register({
    id: 'liquid', hints: ['◀ ▶ Flow between favourites', 'OK Open', '▲ Sources', '▼ All apps'],
    items: function (d) {
      var live = E.items.live(d), favs = E.items.favorites(d), src = E.items.sources(d)[0], out = favs.slice();
      if (live) out.unshift(live);
      if (src) out.splice(Math.min(2, out.length), 0, src);
      return out;
    },
    render: function (c) {
      var v = c.ctl.v = {};
      header(c, '', 'Everything flows', 'The one in focus grows. The rest make room.');
      v.items = this.items(c.data);
      v.row = add(c.content, el('div', 'x-bubbles'));
      v.els = v.items.map(function (it) {
        var b = add(v.row, el('div', 'x-bubble'));
        var t = E.tile(it, 'x-round', 'round');
        add(b, t);
        return b;
      });
      c.state.i = clamp(indexOfId(v.items, c.state.id, c.state.i || 0), 0, v.items.length - 1);
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    paint: function (c) {
      var v = c.ctl.v, n = v.items.length, count = +c.opts.count || 5, i = c.state.i;
      var start = clamp(i - Math.floor(count / 2), 0, Math.max(0, n - count)), shown = Math.min(count, n);
      var BIG = 400, small = Math.min(250, (1640 - BIG) / Math.max(1, shown - 1) - 24), x = 0, widths = [];
      for (var k = 0; k < shown; k++) widths.push(start + k === i ? BIG : small);
      var total = widths.reduce(function (a, b) { return a + b + 24; }, -24), left = (1920 - total) / 2;
      v.els.forEach(function (b, k) {
        var vis = k >= start && k < start + shown;
        b.style.opacity = vis ? 1 : 0;
        b.style.visibility = vis ? 'visible' : 'hidden';
        if (!vis) return;
        var w = widths[k - start];
        b.style.transform = 'translate(' + (left + x) + 'px,' + ((BIG - w) / 2) + 'px) scale(' + (w / BIG) + ')';
        x += w + 24;
        b.classList.toggle('x-f', k === i);
        b.classList.toggle('x-near', Math.abs(k - i) === 1);
      });
      c.state.id = v.items[i] && v.items[i].id;
    },
    key: function (k, c) {
      var v = c.ctl.v, n = v.items.length;
      if (k === 'left' || k === 'right') { if (!n) return true; c.state.i = clamp(c.state.i + (k === 'left' ? -1 : 1), 0, n - 1); this.paint(c); return true; }
      if (k === 'up') { c.ctl.openSources(); return true; }
      if (k === 'down') { c.ctl.openGrid('All apps', E.items.all(c.data)); return true; }
      if (k === 'ok') { c.act(v.items[c.state.i], v.els[c.state.i]); return true; }
      if (k === 'back') { c.state.i = 0; this.paint(c); return true; }
      return false;
    }
  });

  // ======================================================================== 02 Channel Zero (and S, Signal)
  // PDF p.8: UP/DOWN previous/next source, RIGHT details, LEFT favourites, OK open/switch, BACK back. Numbers jump.
  // Signal (p.25) is the same dial, grouped under headings with today's photo on the stage, and no numbers.
  E.register({
    id: 'channel', css: 'channel',
    hints: ['▲ ▼ Change source', 'OK Open / switch', '▶ Details', '◀ Favourites', '0-9 Channel number'],
    list: function (c) {
      var d = c.data, live = E.items.live(d), src = E.items.sources(d), apps = c.opts.apps === 'all' ? E.items.all(d) : E.items.favorites(d);
      var signal = c.opts.variant === 'signal', groups = [];
      if (signal || c.opts.order !== 'apps') {
        if (live) groups.push({ title: 'Live TV', items: [live] });
        if (src.length) groups.push({ title: 'Inputs', items: src });
        groups.push({ title: 'Apps', items: apps });
      } else {
        groups.push({ title: 'Apps', items: apps });
        if (live) groups.push({ title: 'Live TV', items: [live] });
        if (src.length) groups.push({ title: 'Inputs', items: src });
      }
      var flat = [];
      groups.forEach(function (g, gi) { g.items.forEach(function (it) { flat.push({ item: it, group: gi }); }); });
      return { groups: groups, flat: flat };
    },
    render: function (c) {
      var v = c.ctl.v = {}, signal = c.opts.variant === 'signal';
      var L = this.list(c);
      v.flat = L.flat; v.groups = L.groups; v.zone = 'list';
      header(c, signal ? 'The TV dial · one place for every source' : 'The channel dial', '', '');
      v.rail = add(c.content, el('div', 'x-rail'));
      v.inner = add(v.rail, el('div', 'x-rail-in'));
      v.rows = [];
      var lastGroup = -1;
      v.flat.forEach(function (f, n) {
        if (signal && f.group !== lastGroup) { add(v.inner, el('div', 'x-rail-h', v.groups[f.group].title)); lastGroup = f.group; }
        var r = add(v.inner, el('div', 'x-ch'));
        if (!signal) add(r, el('span', 'x-ch-no', (n + 1 < 10 ? '0' : '') + (n + 1)));
        var t = add(r, el('span', 'x-ch-t'));
        add(t, el('b', '', f.item.name));
        add(t, el('small', '', f.item.sub));
        add(r, el('span', 'x-badge', f.item.badge));
        v.rows.push(r);
      });
      v.stage = add(c.content, el('div', 'x-chstage'));
      if (signal && c.data.wallpaper) v.stage.style.backgroundImage = 'linear-gradient(90deg, rgba(11,21,33,.92), rgba(11,21,33,.35)), url("' + c.data.wallpaper + '")';
      v.big = add(v.stage, el('div', 'x-ch-big'));
      v.type = add(v.stage, el('div', 'x-ch-type'));
      v.btns = add(v.stage, el('div', 'x-ch-btns'));
      v.go = add(v.btns, el('div', 'x-btn'));
      v.info = add(v.btns, el('div', 'x-btn x-ghost', 'Back to the dial'));
      v.jump = add(c.content, el('div', 'x-jump'));
      if (!v.flat.length) add(v.inner, el('div', 'x-empty', 'No apps or inputs yet.'));
      c.state.i = clamp(indexOfId(v.flat.map(function (f) { return f.item; }), c.state.id, c.state.i || 0), 0, Math.max(0, v.flat.length - 1));
      this.paint(c);
    },
    refresh: function (c) { var z = c.ctl.v && c.ctl.v.zone; this.render(c); c.ctl.v.zone = z || 'list'; this.paint(c); },
    paint: function (c) {
      var v = c.ctl.v, i = c.state.i, f = v.flat[i], it = f && f.item;
      E.mark(v.rows, i, v.zone === 'list');
      v.rows.forEach(function (r, k) { r.classList.toggle('x-dim', v.zone === 'stage' && k !== i); r.classList.toggle('x-sel', v.zone === 'stage' && k === i); });
      var row = v.rows[i];
      if (row) v.inner.style.transform = 'translateY(' + (-Math.max(0, row.offsetTop - 240)) + 'px)';
      v.big.innerHTML = '';
      if (it) {
        if (it.type === 'source') {
          var m = String(it.id).match(/^([A-Za-z]+)(\d+)$/);
          add(v.big, el('div', 'x-ch-w', m ? m[1].toUpperCase() : it.name));
          if (m) add(v.big, el('div', 'x-ch-n', (+m[2] < 10 ? '0' : '') + m[2]));
        } else {
          var t = add(v.big, E.tile(it, 'x-hero'));
          t.classList.add('x-noname');
          add(v.big, el('div', 'x-ch-w', it.name));
        }
        v.type.textContent = (it.sub || '') + '  /  ' + (it.type === 'source' ? 'OK switches the TV to it' : 'OK opens it');
        v.go.textContent = 'OK  ' + (it.type === 'source' ? 'SWITCH' : 'OPEN');
      }
      v.go.classList.toggle('x-f', v.zone === 'stage' && (c.state.btn || 0) === 0);
      v.info.classList.toggle('x-f', v.zone === 'stage' && c.state.btn === 1);
      v.stage.classList.toggle('x-active', v.zone === 'stage');
      c.state.id = it && it.id;
    },
    digit: function (c, n) {
      if (c.opts.variant === 'signal') return false;
      var v = c.ctl.v;
      v.typed = ((v.typed || '') + n).slice(-2);
      clearTimeout(v.typedTimer);
      var want = +v.typed;
      v.jump.textContent = 'Channel ' + v.typed;
      v.jump.classList.add('show');
      v.typedTimer = setTimeout(function () { v.typed = ''; v.jump.classList.remove('show'); }, 1400);
      if (want >= 1 && want <= v.flat.length) { c.state.i = want - 1; v.zone = 'list'; this.paint(c); c.save(); }
      return true;
    },
    key: function (k, c) {
      var v = c.ctl.v, n = v.flat.length;
      if (!n) return k !== 'back';
      if (v.zone === 'stage') {
        if (k === 'left' || k === 'back') { v.zone = 'list'; this.paint(c); return true; }
        if (k === 'up' || k === 'down') { c.state.btn = k === 'down' ? 1 : 0; this.paint(c); return true; }
        if (k === 'ok') { if (c.state.btn === 1) { v.zone = 'list'; c.state.btn = 0; this.paint(c); } else c.act(v.flat[c.state.i].item, v.go); return true; }
        return true;
      }
      if (k === 'up' || k === 'down') { c.state.i = (c.state.i + (k === 'down' ? 1 : -1) + n) % n; this.paint(c); return true; }   // a dial wraps
      if (k === 'right') { v.zone = 'stage'; c.state.btn = 0; this.paint(c); return true; }
      if (k === 'left') {   // Channel Zero: to the first app (the favourites); Signal: to the next group
        var g = v.flat[c.state.i].group, j;
        if (c.opts.variant === 'signal') { for (j = 1; j <= n; j++) if (v.flat[(c.state.i + j) % n].group !== g) { c.state.i = (c.state.i + j) % n; break; } }
        else for (j = 0; j < n; j++) if (v.flat[j].item.type === 'app') { c.state.i = j; break; }
        this.paint(c);
        return true;
      }
      if (k === 'ok') { c.act(v.flat[c.state.i].item, v.rows[c.state.i]); return true; }
      if (k === 'back') { c.state.i = 0; this.paint(c); return true; }
      return false;
    }
  });
  E.register({ id: 'signal', css: 'channel', hints: ['▲ ▼ Change source', 'OK Open / switch', '▶ Details', '◀ Next group'],
    render: function (c) { E.layouts.channel.render(c); }, refresh: function (c) { E.layouts.channel.refresh(c); },
    key: function (k, c) { return E.layouts.channel.key(k, c); } });

  // ===================================================================================== 03 Typography First
  // PDF p.10: UP/DOWN previous/next mode, RIGHT enter, LEFT back to verbs, OK choose, BACK back.
  var VERBS = [['watch', 'Watch', 'Watching'], ['play', 'Play', 'Playing'], ['listen', 'Listen', 'Listening'], ['sources', 'Sources', 'Switching']];
  E.register({
    id: 'typography', hints: ['▲ ▼ Mode', '▶ / OK Enter', '◀ Words', 'OK Open'],
    render: function (c) {
      var v = c.ctl.v = {}, g = E.items.groups(c.data, c.opts);
      v.modes = VERBS.filter(function (m) { return m[0] === 'watch' || g[m[0]].length; }).map(function (m) { return { id: m[0], verb: m[1], ing: m[2], items: g[m[0]] }; });
      var sc = E.items.scenes(c.data);
      if (sc.length) v.modes.push({ id: 'scenes', verb: 'Scenes', ing: 'Setting', items: sc });
      c.ctl.root.classList.toggle('x-cool', c.opts.accent === 'cool');
      var q = String(c.opts.question || 'Watching tonight?').trim(), rest = q.indexOf(' ') > 0 ? q.slice(q.indexOf(' ') + 1) : q;
      v.left = add(c.content, el('div', 'x-ty-left'));
      add(v.left, el('div', 'x-kicker', 'What are we'));
      v.word = add(v.left, el('div', 'x-ty-word'));
      v.rest = add(v.left, el('div', 'x-ty-rest', rest));
      v.row = add(v.left, el('div', 'x-ty-row'));
      v.menu = add(c.content, el('div', 'x-ty-menu'));
      v.mels = v.modes.map(function (m, n) {
        var r = add(v.menu, el('div', 'x-ty-m'));
        add(r, el('span', 'x-ty-no', '0' + (n + 1)));
        add(r, el('span', '', m.verb.toUpperCase()));
        return r;
      });
      c.state.m = clamp(indexOfId(v.modes, c.state.mid, c.state.m || 0), 0, v.modes.length - 1);
      v.zone = c.state.zone === 'items' ? 'items' : 'menu';
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    paint: function (c) {
      var v = c.ctl.v, m = v.modes[c.state.m];
      v.word.textContent = m.ing.toUpperCase();
      E.mark(v.mels, c.state.m, v.zone === 'menu');
      v.mels.forEach(function (r, k) { r.classList.toggle('x-sel', v.zone !== 'menu' && k === c.state.m); });
      v.menu.classList.toggle('x-away', v.zone === 'items');
      v.row.innerHTML = '';
      v.iels = m.items.slice(0, 7).map(function (it) { return add(v.row, el('div', 'x-ty-i', it.name)); });
      if (!m.items.length) add(v.row, el('div', 'x-ty-empty', 'Nothing here yet: choose apps for ' + m.verb + ' in the phone panel.'));
      c.state.j = clamp(c.state.j || 0, 0, Math.max(0, v.iels.length - 1));
      E.mark(v.iels, c.state.j, v.zone === 'items');
      v.row.classList.toggle('x-open', v.zone === 'items');
      c.state.mid = m.id; c.state.zone = v.zone;
    },
    key: function (k, c) {
      var v = c.ctl.v, m = v.modes[c.state.m];
      if (v.zone === 'menu') {
        if (k === 'up' || k === 'down') { c.state.m = clamp(c.state.m + (k === 'down' ? 1 : -1), 0, v.modes.length - 1); c.state.j = 0; this.paint(c); return true; }
        if (k === 'right' || k === 'ok') { if (m.items.length) { v.zone = 'items'; c.state.j = 0; } this.paint(c); return true; }
        if (k === 'back') { c.state.m = 0; this.paint(c); return true; }
        return true;
      }
      if (k === 'left' && c.state.j === 0 || k === 'back') { v.zone = 'menu'; this.paint(c); return true; }
      if (k === 'left' || k === 'right') { c.state.j = clamp(c.state.j + (k === 'right' ? 1 : -1), 0, v.iels.length - 1); this.paint(c); return true; }
      if (k === 'up' || k === 'down') { v.zone = 'menu'; c.state.m = clamp(c.state.m + (k === 'down' ? 1 : -1), 0, v.modes.length - 1); c.state.j = 0; this.paint(c); return true; }
      if (k === 'ok') { c.act(m.items.slice(0, 7)[c.state.j], v.iels[c.state.j]); return true; }
      return false;
    }
  });

  // ===================================================================================== 04 Floating Worlds
  // PDF p.12: LEFT/RIGHT world, UP global TV controls, DOWN quick favourites, OK enter world, BACK return to world.
  var WORLDS = [['watch', 'Watch', '▶'], ['play', 'Play', '◆'], ['listen', 'Listen', '♪'], ['sources', 'Sources', '⇄']];
  E.register({
    id: 'worlds', hints: ['◀ ▶ Between worlds', 'OK Enter', '▼ Favourites', '▲ TV controls'],
    render: function (c) {
      var v = c.ctl.v = {}, g = E.items.groups(c.data, c.opts), np = E.items.np(c.data);
      v.worlds = WORLDS.map(function (w) { return { id: w[0], name: w[1], glyph: w[2], items: g[w[0]] }; });
      v.controls = [];
      if (np) v.controls.push({ label: (np.np.state === 'playing' ? '❚❚  ' : '▶  ') + np.name, run: function () { c.act(np); } });
      v.controls.push({ label: 'Sources', run: function () { c.ctl.openSources(); } });
      v.controls.push({ label: 'All apps', run: function () { c.ctl.openGrid('All apps', E.items.all(c.data)); } });
      v.controls.push({ label: 'Menu', run: function () { c.ctl.openMenu(); } });
      v.bar = add(c.content, el('div', 'x-wo-bar'));
      v.cels = v.controls.map(function (x) { return add(v.bar, el('div', 'x-chip', x.label)); });
      header(c, '', 'Choose your world', '');
      v.isl = add(c.content, el('div', 'x-islands'));
      v.wels = v.worlds.map(function (w) {
        var b = add(v.isl, el('div', 'x-island x-w-' + w.id));
        var box = add(b, el('div', 'x-island-art'));
        add(box, el('span', '', w.glyph));
        add(b, el('div', 'x-island-stand'));
        add(b, el('div', 'x-island-name', w.name.toUpperCase()));
        add(b, el('div', 'x-island-count', w.items.length ? w.items.length + (w.items.length === 1 ? ' place' : ' places') : 'empty'));
        return b;
      });
      add(c.content, el('div', 'x-wo-note', 'Explore a world with OK. Your favourites are always one key away.'));
      v.favs = E.items.favorites(c.data).slice(0, 8);
      v.fels = strip(c.content, v.favs, 'x-wo-favs');
      v.panel = add(c.content, el('div', 'x-wo-panel'));
      v.zone = c.state.zone || 'worlds';
      if (v.zone === 'inside') v.zone = 'worlds';
      c.state.w = clamp(c.state.w || 0, 0, 3);
      this.paint(c);
    },
    refresh: function (c) { var inside = c.ctl.v.zone === 'inside'; this.render(c); if (inside) this.enter(c); },
    enter: function (c) {
      var v = c.ctl.v, w = v.worlds[c.state.w];
      v.zone = 'inside';
      v.panel.innerHTML = '';
      add(v.panel, el('div', 'x-ptitle', w.name));
      if (!w.items.length) add(v.panel, el('div', 'x-empty', 'Nothing in ' + w.name + ' yet. Choose its apps in the phone panel (Layout, Floating Worlds).'));
      var grid = add(v.panel, el('div', 'x-pgrid'));
      v.pitems = w.items;
      v.pels = w.items.map(function (it) { return add(grid, E.tile(it, 'x-small')); });
      c.state.p = clamp(c.state.p || 0, 0, Math.max(0, v.pels.length - 1));
      this.paint(c);
    },
    paint: function (c) {
      var v = c.ctl.v;
      E.mark(v.wels, c.state.w, v.zone === 'worlds');
      v.wels.forEach(function (r, k) { r.classList.toggle('x-sel', v.zone !== 'worlds' && k === c.state.w); });
      E.mark(v.cels, c.state.c || 0, v.zone === 'controls');
      E.mark(v.fels, c.state.f || 0, v.zone === 'favs');
      v.panel.classList.toggle('on', v.zone === 'inside');
      if (v.zone === 'inside') E.mark(v.pels, c.state.p, true);
      c.state.zone = v.zone;
    },
    key: function (k, c) {
      var v = c.ctl.v, z = v.zone;
      if (z === 'inside') {
        var n = v.pels.length, cols = 6;
        if (k === 'back') { v.zone = 'worlds'; this.paint(c); return true; }
        if (!n) return true;
        if (k === 'left') c.state.p = Math.max(0, c.state.p - 1);
        else if (k === 'right') c.state.p = Math.min(n - 1, c.state.p + 1);
        else if (k === 'up') { if (c.state.p >= cols) c.state.p -= cols; else { v.zone = 'worlds'; } }
        else if (k === 'down') c.state.p = Math.min(n - 1, c.state.p + cols);
        else if (k === 'ok') { c.act(v.pitems[c.state.p], v.pels[c.state.p]); return true; }
        this.paint(c);
        return true;
      }
      if (z === 'controls') {
        if (k === 'left' || k === 'right') c.state.c = clamp((c.state.c || 0) + (k === 'right' ? 1 : -1), 0, v.controls.length - 1);
        else if (k === 'down' || k === 'back') v.zone = 'worlds';
        else if (k === 'ok') { v.controls[c.state.c || 0].run(); return true; }
        this.paint(c);
        return true;
      }
      if (z === 'favs') {
        if (k === 'left' || k === 'right') c.state.f = clamp((c.state.f || 0) + (k === 'right' ? 1 : -1), 0, v.favs.length - 1);
        else if (k === 'up' || k === 'back') v.zone = 'worlds';
        else if (k === 'ok') { c.act(v.favs[c.state.f || 0], v.fels[c.state.f || 0]); return true; }
        this.paint(c);
        return true;
      }
      if (k === 'left' || k === 'right') c.state.w = clamp(c.state.w + (k === 'right' ? 1 : -1), 0, 3);
      else if (k === 'up') v.zone = 'controls';
      else if (k === 'down') { if (v.favs.length) v.zone = 'favs'; }
      else if (k === 'ok') { c.state.p = 0; this.enter(c); return true; }
      else if (k === 'back') c.state.w = 0;
      this.paint(c);
      return true;
    }
  });

  // =========================================================================================== 05 The Fold
  // PDF p.14: LEFT/RIGHT segment, UP panel shortcuts, DOWN all apps, OK enter/open, BACK collapse one level.
  // 2D skew only; target positions stay put (the focused panel widens, the others narrow in place).
  E.register({
    id: 'fold', hints: ['◀ ▶ Fold panels', 'OK Open the list', '▲ Shortcuts', '▼ All apps', 'BACK Fold'],
    render: function (c) {
      var v = c.ctl.v = {}, d = c.data, g = E.items.groups(d, c.opts), live = E.items.live(d);
      v.panels = [
        { name: 'Live TV', items: live ? [live] : [], empty: 'No Live TV source here.' },
        { name: 'Watch', items: g.watch.slice(0, 5), empty: 'No apps yet.' },
        { name: 'Play', items: g.play.slice(0, 5), empty: 'Choose apps for Play in the phone panel.' },
        { name: 'Inputs', items: E.items.sources(d).slice(0, 5), empty: d.caps.sources ? 'The TV reported no inputs.' : 'Inputs need the helper.' }
      ];
      v.short = [{ label: 'Sources', run: function () { c.ctl.openSources(); } }, { label: 'All apps', run: function () { c.ctl.openGrid('All apps', E.items.all(d)); } },
                 { label: 'Menu', run: function () { c.ctl.openMenu(); } }];
      v.sbar = add(c.content, el('div', 'x-fo-short'));
      v.sels = v.short.map(function (s) { return add(v.sbar, el('div', 'x-chip', s.label)); });
      header(c, '', 'The Fold', 'An interface that unfolds around focus.');
      v.row = add(c.content, el('div', 'x-folds'));
      v.pels = v.panels.map(function (p, n) {
        var f = add(v.row, el('div', 'x-fold x-fold-' + n));
        var inner = add(f, el('div', 'x-fold-in'));
        add(inner, el('div', 'x-fold-name', p.name.toUpperCase()));
        var list = add(inner, el('div', 'x-fold-list'));
        p.els = p.items.map(function (it) { return add(list, el('div', 'x-fold-i', it.name)); });
        if (!p.items.length) add(list, el('div', 'x-fold-e', p.empty));
        return f;
      });
      add(c.content, el('div', 'x-fo-note', 'Focus reveals options without shifting other targets.'));
      v.zone = c.state.zone === 'list' ? 'list' : 'panels';
      c.state.p = clamp(c.state.p || 1, 0, 3);
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    paint: function (c) {
      var v = c.ctl.v;
      v.pels.forEach(function (f, n) { f.classList.toggle('x-open', n === c.state.p); });
      E.mark(v.pels, c.state.p, v.zone === 'panels');
      E.mark(v.sels, c.state.s || 0, v.zone === 'short');
      v.panels.forEach(function (p, n) { E.mark(p.els, c.state.j || 0, v.zone === 'list' && n === c.state.p); });
      c.state.zone = v.zone;
    },
    key: function (k, c) {
      var v = c.ctl.v, p = v.panels[c.state.p];
      if (v.zone === 'short') {
        if (k === 'left' || k === 'right') c.state.s = clamp((c.state.s || 0) + (k === 'right' ? 1 : -1), 0, v.short.length - 1);
        else if (k === 'down' || k === 'back') v.zone = 'panels';
        else if (k === 'ok') { v.short[c.state.s || 0].run(); return true; }
        this.paint(c); return true;
      }
      if (v.zone === 'list') {
        if (k === 'up') { if ((c.state.j || 0) > 0) c.state.j--; else v.zone = 'panels'; }
        else if (k === 'down') c.state.j = Math.min(p.items.length - 1, (c.state.j || 0) + 1);
        else if (k === 'back') v.zone = 'panels';
        else if (k === 'left' || k === 'right') { v.zone = 'panels'; c.state.p = clamp(c.state.p + (k === 'right' ? 1 : -1), 0, 3); c.state.j = 0; }
        else if (k === 'ok') { c.act(p.items[c.state.j || 0], p.els[c.state.j || 0]); return true; }
        this.paint(c); return true;
      }
      if (k === 'left' || k === 'right') { c.state.p = clamp(c.state.p + (k === 'right' ? 1 : -1), 0, 3); c.state.j = 0; }
      else if (k === 'ok') { if (p.items.length) { v.zone = 'list'; c.state.j = 0; } else c.toast(p.empty); }
      else if (k === 'up') v.zone = 'short';
      else if (k === 'down') { c.ctl.openGrid('All apps', E.items.all(c.data)); return true; }
      else if (k === 'back') c.state.p = 1;
      this.paint(c); return true;
    }
  });

  // =================================================================================== 06 Four Directions
  // PDF p.16: UP discover, DOWN all apps, LEFT resume, RIGHT sources, OK last-used, BACK return to the hub.
  // The arrows act only at the hub; inside a destination they move normally. Number keys 1-4: the favourites below.
  var DIRS = { up: ['discover', 'Discover', 'Up arrow'], right: ['sources', 'Sources', 'Right arrow'], down: ['apps', 'All apps', 'Down arrow'], left: ['resume', 'Resume', 'Left arrow'] };
  E.register({
    id: 'four', hints: ['▲ Discover', '▼ All apps', '◀ Resume', '▶ Sources', 'OK Last used', '1-4 Favourites'],
    center: function (c) {
      var r = E.items.recent(c.data)[0];
      return (c.opts.center !== 'first' && r) || E.items.favorites(c.data)[0] || null;
    },
    render: function (c) {
      var v = c.ctl.v = {};
      header(c, '', 'Four directions', 'One key. One destination.');
      v.hub = add(c.content, el('div', 'x-hub'));
      v.boxes = {};
      Object.keys(DIRS).forEach(function (k) {
        var b = add(v.hub, el('div', 'x-dir x-dir-' + k));
        add(b, el('b', '', DIRS[k][1].toUpperCase()));
        add(b, el('small', '', DIRS[k][2].toUpperCase()));
        v.boxes[k] = b;
      });
      v.ok = add(v.hub, el('div', 'x-hub-ok'));
      add(v.ok, el('b', '', 'OK'));
      v.okSub = add(v.ok, el('small', ''));
      v.dock = add(c.content, el('div', 'x-fd-dock'));
      v.favs = E.items.favorites(c.data).slice(0, 4);
      v.favs.forEach(function (it, n) { var t = add(v.dock, E.tile(it, 'x-small')); add(t, el('span', 'x-key', String(n + 1))); });
      v.sub = add(c.content, el('div', 'x-fd-sub'));
      v.zone = 'hub';
      this.paint(c);
    },
    refresh: function (c) { var z = c.ctl.v.zone, s = c.ctl.v.dest; this.render(c); if (z !== 'hub' && s) this.open(c, s); },
    paint: function (c) {
      var v = c.ctl.v, cen = this.center(c);
      v.okSub.textContent = cen ? cen.name : 'Nothing yet';
      v.ok.classList.toggle('x-f', v.zone === 'hub');
      Object.keys(v.boxes).forEach(function (k) { v.boxes[k].classList.toggle('x-on', v.zone !== 'hub' && v.dest === DIRS[k][0]); });
      v.sub.classList.toggle('on', v.zone !== 'hub');
      if (v.zone !== 'hub') E.mark(v.sels, c.state.s || 0, true);
    },
    open: function (c, dest) {
      var v = c.ctl.v, d = c.data, items, title, empty;
      if (dest === 'apps') { c.ctl.openGrid('All apps', E.items.all(d), { upClose: true }); return; }
      if (dest === 'sources') { var live = E.items.live(d); items = (live ? [live] : []).concat(E.items.sources(d)); title = 'Sources'; empty = d.caps.sources ? 'The TV reported no inputs.' : 'Inputs need the helper (a VIDAA TV).'; }
      else if (dest === 'resume') { var np = E.items.np(d); items = (np ? [np] : []).concat(E.items.recent(d)); title = 'Resume'; empty = 'Nothing opened from this Home yet. Apps you open appear here.'; }
      else {
        title = 'Discover'; items = [];
        (d.rows || []).forEach(function (r) { (r.ids || []).forEach(function (id) { var it = E.items.app(d, id); if (it) items.push(it); }); });
        if (!items.length) items = E.items.all(d);
        empty = 'Nothing to discover yet.';
      }
      v.zone = 'dest'; v.dest = dest; v.items = items;
      v.sub.innerHTML = '';
      add(v.sub, el('div', 'x-ptitle', title));
      if (!items.length) add(v.sub, el('div', 'x-empty', empty));
      var grid = add(v.sub, el('div', 'x-pgrid'));
      v.sels = items.map(function (it) { return add(grid, E.tile(it, 'x-small')); });
      c.state.s = 0;
      this.paint(c);
    },
    digit: function (c, n) {
      var v = c.ctl.v, it = v.favs[n - 1];
      if (v.zone === 'hub' && it) { c.act(it); return true; }
      return false;
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (v.zone === 'hub') {
        if (DIRS[k]) { this.open(c, DIRS[k][0]); return true; }
        if (k === 'ok') { var it = this.center(c); if (it) c.act(it, v.ok); else c.toast('Open something first: it comes back here.'); return true; }
        return k === 'back';
      }
      var n = v.items.length, cols = 6;
      if (k === 'back') { v.zone = 'hub'; v.dest = ''; this.paint(c); return true; }
      if (!n) return true;
      if (k === 'left') c.state.s = Math.max(0, c.state.s - 1);
      else if (k === 'right') c.state.s = Math.min(n - 1, c.state.s + 1);
      else if (k === 'up') { if (c.state.s >= cols) c.state.s -= cols; else { v.zone = 'hub'; v.dest = ''; } }
      else if (k === 'down') c.state.s = Math.min(n - 1, c.state.s + cols);
      else if (k === 'ok') { c.act(v.items[c.state.s], v.sels[c.state.s]); return true; }
      this.paint(c);
      return true;
    }
  });

  // =================================================================================== 07 The Time River
  // PDF p.18: LEFT/RIGHT earlier/later moment, UP sources, DOWN all apps, OK do it, BACK previous view.
  // Only real moments: what is playing, what you opened here, timed widgets, your scenes; else favourites.
  E.register({
    id: 'river', hints: ['◀ ▶ Moments', 'OK Do it', '▲ Sources', '▼ Apps'],
    moments: function (c) {
      var d = c.data, out = [], recent = E.items.recent(d)[0], np = E.items.np(d), live = E.items.live(d);
      if (recent) out.push({ when: E.hhmm(recent.at), what: 'Resume', note: 'Last opened here', item: recent });
      if (np) out.push({ when: 'Now', what: np.name, note: np.np.name ? 'On ' + np.np.name : 'Playing', item: np, now: true });
      else if (live) out.push({ when: 'Now', what: 'Live TV', note: 'Quick access', item: live, now: true });
      var sports = c.opts.sports ? E.items.app(d, c.opts.sports) : null;
      E.items.upcoming(d).slice(0, 3).forEach(function (u) {
        var it = u.kind === 'match' ? (sports || E.items.none(u.name, 'Choose the app a match opens in the phone panel (Layout, Time River)'))
                                    : E.items.none(u.name, u.source + ' · ' + E.until(u.at));
        out.push({ when: u.live ? 'Live' : E.hhmm(u.at), what: u.name, note: u.source + (u.live ? '' : ' · ' + E.until(u.at)), item: it });
      });
      E.items.scenes(d).slice(0, 2).forEach(function (s) { out.push({ when: 'Later', what: s.name, note: 'Your scene', item: s }); });
      if (out.length < 3) E.items.favorites(d).slice(0, 4 - out.length).forEach(function (f) { out.push({ when: 'Any time', what: f.name, note: f.sub, item: f }); });
      return out;
    },
    render: function (c) {
      var v = c.ctl.v = {};
      header(c, '', 'Your evening', 'A meaningful sequence, not an endless feed.');
      v.m = this.moments(c);
      v.track = add(c.content, el('div', 'x-river'));
      v.line = add(v.track, el('div', 'x-river-line'));
      v.els = v.m.map(function (m) {
        var card = add(v.track, el('div', 'x-moment' + (m.now ? ' x-now' : '')));
        add(card, el('div', 'x-m-when', m.when.toUpperCase()));
        add(card, el('div', 'x-m-what', m.what));
        add(card, el('div', 'x-m-note', m.note));
        add(card, el('div', 'x-m-dot'));
        return card;
      });
      v.btn = add(c.content, el('div', 'x-btn x-river-btn'));
      var nowAt = 0;
      v.m.forEach(function (m, n) { if (m.now) nowAt = n; });
      c.state.i = clamp(c.state.i == null ? nowAt : c.state.i, 0, v.m.length - 1);
      v.nowAt = nowAt;
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    paint: function (c) {
      var v = c.ctl.v, i = c.state.i, first = clamp(i - 1, 0, Math.max(0, v.m.length - 4));
      v.els.forEach(function (e, n) { e.style.transform = 'translateX(' + ((n - first) * 400) + 'px)'; e.style.opacity = n < first || n >= first + 4 ? 0 : 1; });
      E.mark(v.els, i);
      var m = v.m[i];
      v.btn.textContent = m ? verbLine(m.item) : 'Nothing here yet';
      v.btn.classList.toggle('x-disabled', !m || m.item.type === 'none');
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (k === 'left' || k === 'right') { c.state.i = clamp(c.state.i + (k === 'right' ? 1 : -1), 0, v.m.length - 1); this.paint(c); return true; }
      if (k === 'ok') { var m = v.m[c.state.i]; if (m) c.act(m.item, v.btn); return true; }
      if (k === 'up') { c.ctl.openSources(); return true; }
      if (k === 'down') { c.ctl.openGrid('All apps', E.items.all(c.data)); return true; }
      if (k === 'back') { c.state.i = v.nowAt; this.paint(c); return true; }
      return false;
    }
  });

  // ======================================================================================= 08 The Gallery
  // PDF p.20: LEFT/RIGHT frame, UP frame details, DOWN full collection, OK open, BACK gallery/exit.
  E.register({
    id: 'gallery', hints: ['◀ ▶ Frames', 'OK Open', '▲ About this frame', '▼ The full collection'],
    frames: function (c) {
      var d = c.data, n = +c.opts.frames || 4, live = E.items.live(d), src = E.items.sources(d)[0], photo = c.opts.photo !== 'off' ? E.items.photo(d) : null;
      var out = [];
      if (live) out.push(live);
      E.items.favorites(d).forEach(function (f) { out.push(f); });
      if (src) out.splice(Math.min(2, out.length), 0, src);
      out = out.slice(0, photo ? n - 1 : n);
      if (photo) out.push(photo);
      return out;
    },
    render: function (c) {
      var v = c.ctl.v = {};
      header(c, '', 'The collection', 'Curated by you, not an algorithm.');
      v.items = this.frames(c);
      v.wall = add(c.content, el('div', 'x-wall'));
      v.els = v.items.map(function (it) {
        var f = add(v.wall, el('div', 'x-frame'));
        var mat = add(f, el('div', 'x-mat'));
        var t = add(mat, E.tile(it, 'x-framed', 'tall'));
        t.classList.add('x-noname');
        add(f, el('div', 'x-plate', it.name.toUpperCase()));
        return f;
      });
      v.info = add(c.content, el('div', 'x-ga-info'));
      v.zone = 'frames';
      c.state.i = clamp(indexOfId(v.items, c.state.id, c.state.i || 0), 0, Math.max(0, v.items.length - 1));
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    paint: function (c) {
      var v = c.ctl.v, it = v.items[c.state.i];
      E.mark(v.els, c.state.i);
      v.info.classList.toggle('on', v.zone === 'info');
      v.info.innerHTML = '';
      if (it) {
        add(v.info, el('b', '', it.name));
        add(v.info, el('span', '', it.sub + '  ·  OK ' + (it.verb || 'Open').toLowerCase()));
      }
      c.state.id = it && it.id;
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (k === 'left' || k === 'right') { c.state.i = clamp(c.state.i + (k === 'right' ? 1 : -1), 0, v.items.length - 1); this.paint(c); return true; }
      if (k === 'up') { v.zone = v.zone === 'info' ? 'frames' : 'info'; this.paint(c); return true; }
      if (k === 'down') {
        var d = c.data, live = E.items.live(d);
        c.ctl.openGrid('The full collection', (live ? [live] : []).concat(E.items.sources(d), E.items.all(d))); return true;
      }
      if (k === 'ok') { c.act(v.items[c.state.i], v.els[c.state.i]); return true; }
      if (k === 'back') { if (v.zone === 'info') v.zone = 'frames'; else c.state.i = 0; this.paint(c); return true; }
      return false;
    }
  });

  // ============================================================================= 09 Aurora (and A, Ambient)
  // PDF p.22: any arrow reveals (the first press never opens anything), OK opens the focused place, BACK hides.
  // Static light (no constant animation, no shimmer); Ambient is the same over today's photo, the clock centred.
  function auroraSvg() {
    var paths = '', k;
    for (k = 0; k < 9; k++) {
      var y = 560 + k * 18, a = 120 + k * 9;
      paths += '<path d="M0 ' + (y + 60) + ' C 420 ' + (y + a) + ', 820 ' + (y - a) + ', 1240 ' + (y - 40) + ' S 1760 ' + (y - a / 2) + ', 1920 ' + (y - 90) + '" stroke="' +
               (k % 3 === 0 ? '#3fb6a8' : k % 3 === 1 ? '#2f7fa8' : '#57c7b0') + '" stroke-opacity="' + (0.25 + (k % 4) * 0.12) + '" stroke-width="' + (2 + (k % 3)) + '" fill="none"/>';
    }
    return '<svg viewBox="0 0 1920 1080" preserveAspectRatio="none"><defs><linearGradient id="xg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0b1521"/><stop offset="1" stop-color="#0c2a34"/></linearGradient></defs>' +
           '<rect width="1920" height="1080" fill="url(#xg)"/>' + paths + '<path d="M0 1080 L380 840 L700 1000 L1080 800 L1500 1010 L1920 860 L1920 1080 Z" fill="#081018" fill-opacity=".8"/></svg>';
  }
  E.register({
    id: 'aurora', photo: false, hints: ['Any arrow  Show places', 'OK Open', 'BACK Hide'],
    places: function (c) {
      var d = c.data, n = +c.opts.strip || 5, live = E.items.live(d), src = E.items.sources(d)[0], out = [];
      if (live) out.push(live);
      if (src) out.push(src);
      E.items.favorites(d).forEach(function (f) { out.push(f); });
      out = out.slice(0, n - 1);
      out.push(E.items.allApps());
      return out;
    },
    render: function (c) {
      var v = c.ctl.v = {}, ambient = c.opts.variant === 'ambient';
      if (!ambient) c.content.innerHTML = '<div class="x-aurora">' + auroraSvg() + '</div>';
      else if (c.data.wallpaper) c.bg.style.backgroundImage = 'url("' + c.data.wallpaper + '")';
      v.clock = add(c.content, el('div', 'x-au-clock' + (ambient ? ' x-centre' : '')));
      v.time = add(v.clock, el('div', 'x-au-time'));
      v.date = add(v.clock, el('div', 'x-au-date'));
      v.np = add(v.clock, el('div', 'x-au-np'));
      v.items = this.places(c);
      v.strip = add(c.content, el('div', 'x-au-strip'));
      v.els = v.items.map(function (it) {
        var b = add(v.strip, el('div', 'x-au-i'));
        add(b, el('b', '', it.badge === 'LIVE' ? 'TV' : E.mono(it)));
        add(b, el('small', '', it.name));
        return b;
      });
      v.ctrls = add(c.content, el('div', 'x-au-ctrl'));
      var np = E.items.np(c.data);
      v.ctrl = [];
      if (np) v.ctrl.push({ label: (np.np.state === 'playing' ? '❚❚ ' : '▶ ') + np.name, run: function () { c.act(np); } });
      v.ctrl.push({ label: 'Sources', run: function () { c.ctl.openSources(); } }, { label: 'Menu', run: function () { c.ctl.openMenu(); } });
      v.cels = v.ctrl.map(function (x) { return add(v.ctrls, el('div', 'x-chip', x.label)); });
      v.shown = false; v.zone = 'strip';
      c.state.i = clamp(c.state.i || 0, 0, v.items.length - 1);
      this.tick(c);
      this.paint(c);
    },
    refresh: function (c) { var s = c.ctl.v.shown, z = c.ctl.v.zone; this.render(c); c.ctl.v.shown = s; c.ctl.v.zone = z; this.paint(c); },
    tick: function (c) {
      var v = c.ctl.v, k = E.clock(c.data.clock24), np = E.items.np(c.data);
      v.time.textContent = k.time;
      v.date.textContent = k.date + (c.data.weather ? '  ·  ' + E.weather(c.data.weather) : '');
      v.np.textContent = np ? '♪  ' + np.name + (np.np.subtitle ? ' — ' + np.np.subtitle : '') : '';
    },
    paint: function (c) {
      var v = c.ctl.v, self = this;
      c.ctl.root.classList.toggle('x-revealed', v.shown);
      E.mark(v.els, c.state.i, v.shown && v.zone === 'strip');
      E.mark(v.cels, c.state.c || 0, v.shown && v.zone === 'ctrl');
      clearTimeout(v.hide);
      if (v.shown) v.hide = setTimeout(function () { v.shown = false; self.paint(c); }, 20000);   // back to calm by itself
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (!v.shown) {
        if (k === 'back') return true;
        v.shown = true; v.zone = 'strip'; this.paint(c); return true;   // reveal only: nothing opens on the first press
      }
      if (k === 'back') { v.shown = false; this.paint(c); return true; }
      if (v.zone === 'ctrl') {
        if (k === 'left' || k === 'right') c.state.c = clamp((c.state.c || 0) + (k === 'right' ? 1 : -1), 0, v.ctrl.length - 1);
        else if (k === 'down') v.zone = 'strip';
        else if (k === 'ok') { v.ctrl[c.state.c || 0].run(); return true; }
        this.paint(c); return true;
      }
      if (k === 'left' || k === 'right') c.state.i = clamp(c.state.i + (k === 'right' ? 1 : -1), 0, v.items.length - 1);
      else if (k === 'up') v.zone = 'ctrl';
      else if (k === 'down') { c.ctl.openGrid('All apps', E.items.all(c.data)); return true; }
      else if (k === 'ok') { c.act(v.items[c.state.i], v.els[c.state.i]); return true; }
      this.paint(c); return true;
    }
  });
  E.register({ id: 'ambient', css: 'aurora', photo: false, hints: ['Any arrow  Show places', 'OK Open', 'BACK Hide'],
    render: function (c) { E.layouts.aurora.render(c); }, refresh: function (c) { E.layouts.aurora.refresh(c); },
    tick: function (c) { E.layouts.aurora.tick(c); }, key: function (k, c) { return E.layouts.aurora.key(k, c); } });

  // ================================================================================== 10 The Command Deck
  // PDF p.24: LEFT/RIGHT favourites, UP system controls, DOWN all apps, OK activate, BACK return to the dock.
  // Only controls with a verified API: input (helper), playback (media session), VPN region. No volume or
  // brightness: nothing here can read or set them truthfully.
  E.register({
    id: 'deck', hints: ['◀ ▶ Favourites', '▲ TV controls', 'OK Do it', '▼ All apps'],
    render: function (c) {
      var v = c.ctl.v = {}, d = c.data, live = E.items.live(d);
      header(c, '', 'Your TV, under control', '');
      v.stage = add(c.content, el('div', 'x-dk-stage'));
      v.sk = add(v.stage, el('div', 'x-kicker', 'Selected'));
      v.sname = add(v.stage, el('div', 'x-dk-name'));
      v.ssub = add(v.stage, el('div', 'x-dk-sub'));
      v.sbtn = add(v.stage, el('div', 'x-btn'));
      v.side = add(c.content, el('div', 'x-dk-side'));
      v.ctrl = [];
      var last = E.items.recent(d).filter(function (r) { return r.type === 'source'; })[0];
      if (d.caps.sources) v.ctrl.push({ k: 'Input', val: last ? last.name + ' (chosen here)' : 'Choose…', run: function () { c.ctl.openSources(); } });
      var np = E.items.np(d);
      if (np) v.ctrl.push({ k: 'Playback', val: (np.np.state === 'playing' ? 'Playing · ' : 'Paused · ') + np.name, run: function () { c.act(np); } });
      if (d.region && d.region.list && d.region.list.length && c.ctl.host.region) {
        var cur = d.region.list.filter(function (r) { return r.id === d.region.now; })[0];
        v.ctrl.push({ k: 'VPN', val: cur ? cur.name : 'Local', run: function () { regionMenu(c); } });
      }
      v.ctrl.push({ k: 'Weather', val: E.weather(d.weather) || 'Not set', run: function () { c.toast('Set the weather place in the phone panel'); } });
      v.cels = v.ctrl.map(function (x) { var b = add(v.side, el('div', 'x-dk-ctl')); add(b, el('small', '', x.k.toUpperCase())); add(b, el('b', '', x.val)); return b; });
      v.dock = (live ? [live] : []).concat(E.items.favorites(d).slice(0, 5), [E.items.allApps()]);
      v.dels = strip(c.content, v.dock, 'x-dk-dock');
      v.zone = c.state.zone === 'ctrl' ? 'ctrl' : 'dock';
      c.state.i = clamp(indexOfId(v.dock, c.state.id, c.state.i || 0), 0, v.dock.length - 1);
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    paint: function (c) {
      var v = c.ctl.v, it = v.zone === 'dock' ? v.dock[c.state.i] : null, ct = v.zone === 'ctrl' ? v.ctrl[c.state.c || 0] : null;
      E.mark(v.dels, c.state.i, v.zone === 'dock');
      E.mark(v.cels, c.state.c || 0, v.zone === 'ctrl');
      v.sk.textContent = it ? (it.type === 'source' ? 'Input' : it.type === 'all' ? 'Everything' : 'App') : 'TV control';
      v.sname.textContent = it ? it.name : ct ? ct.k : '';
      v.ssub.textContent = it ? it.sub : ct ? ct.val : '';
      v.sbtn.textContent = it ? 'OK  ' + (it.verb || 'Open').toUpperCase() : 'OK  CHANGE';
      c.state.id = it ? it.id : c.state.id; c.state.zone = v.zone;
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (v.zone === 'ctrl') {
        if (k === 'up' || k === 'down') { var j = (c.state.c || 0) + (k === 'down' ? 1 : -1); if (j >= v.ctrl.length) v.zone = 'dock'; else c.state.c = Math.max(0, j); }
        else if (k === 'left' || k === 'back') v.zone = 'dock';
        else if (k === 'ok') { v.ctrl[c.state.c || 0].run(); return true; }
        this.paint(c); return true;
      }
      if (k === 'left' || k === 'right') c.state.i = clamp(c.state.i + (k === 'right' ? 1 : -1), 0, v.dock.length - 1);
      else if (k === 'up') { v.zone = 'ctrl'; c.state.c = v.ctrl.length - 1; }
      else if (k === 'down') { c.ctl.openGrid('All apps', E.items.all(c.data)); return true; }
      else if (k === 'ok') { c.act(v.dock[c.state.i], v.dels[c.state.i]); return true; }
      else if (k === 'back') c.state.i = 0;
      this.paint(c); return true;
    }
  });
  function regionMenu(c) {
    var d = c.data, list = [{ id: 'off', name: 'Local (no VPN)' }].concat(d.region.list.filter(function (r) { return r.id !== 'off'; }));
    c.ctl.openGrid('VPN country', list.map(function (r) {
      return { type: 'none', id: 'vpn-' + r.id, name: r.name, sub: r.id === d.region.now ? 'Now' : 'VPN', color: '#24435a', glyph: r.id === 'off' ? '⌂' : r.id.toUpperCase().slice(0, 2),
               why: '', run: r.id };
    }), { cols: 5 });
    var ov = c.ctl.overlay, inner = ov.key;
    ov.key = function (k) {   // OK on a country: switch it, here instead of the grid's own "open"
      if (k !== 'ok') return inner(k);
      var cells = c.ctl.stage.querySelectorAll('.x-layer .x-tile'), i = 0;
      for (var n = 0; n < cells.length; n++) if (cells[n].classList.contains('x-f')) i = n;
      var r = list[i];
      c.ctl.closeOverlay();
      c.toast('VPN: ' + r.name + '…');
      c.ctl.host.region(r.id).then(function (x) { c.toast(x && x.ok !== false ? 'VPN: ' + r.name : 'VPN did not switch' + (x && x.error ? ': ' + x.error : '')); },
                                   function () { c.toast('Could not reach Home\'s server'); });
      return true;
    };
  }

  // ======================================================================================= T Theater
  // The recommended default (PDF p.25, p.32): photo, headline, verified Now playing, one row of places.
  E.register({
    id: 'theater', photo: true, hints: ['◀ ▶ Choose', 'OK Open', '▲ Now playing', '▼ All apps'],
    render: function (c) {
      var v = c.ctl.v = {}, d = c.data, live = E.items.live(d), src = E.items.sources(d)[0], k = E.clock(d.clock24);
      v.head = add(c.content, el('div', 'x-th-head'));
      add(v.head, el('div', 'x-kicker', 'Your screen. Your rules.'));
      add(v.head, el('div', 'x-th-title', c.opts.headline || 'Make tonight your own.'));
      v.meta = add(v.head, el('div', 'x-th-meta'));
      v.np = E.items.np(d);
      v.npel = nowCard(c.content, v.np, 'x-th-np');
      if (!v.np) v.npel.style.display = 'none';
      v.row = E.items.favorites(d).slice(0, 5);
      if (live) v.row.splice(Math.min(2, v.row.length), 0, live);
      if (src) v.row.splice(Math.min(3, v.row.length), 0, src);
      v.row.push(E.items.allApps());
      add(c.content, el('div', 'x-th-label', 'Your favourites'));
      v.els = strip(c.content, v.row, 'x-th-row');
      v.zone = 'row';
      c.state.i = clamp(indexOfId(v.row, c.state.id, c.state.i || 0), 0, v.row.length - 1);
      this.tick(c);
      this.paint(c);
    },
    refresh: function (c) { this.render(c); },
    tick: function (c) {
      var d = c.data, k = E.clock(d.clock24);
      c.ctl.v.meta.textContent = k.date + (d.weather ? '  ·  ' + E.weather(d.weather) : '') + (d.wallpaperTitle ? '  ·  ' + d.wallpaperTitle : '');
    },
    paint: function (c) {
      var v = c.ctl.v;
      E.mark(v.els, c.state.i, v.zone === 'row');
      v.npel.classList.toggle('x-f', v.zone === 'np');
      c.state.id = v.row[c.state.i] && v.row[c.state.i].id;
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (v.zone === 'np') {
        if (k === 'ok') { c.act(v.np); return true; }
        if (k === 'down' || k === 'back') { v.zone = 'row'; this.paint(c); }
        return true;
      }
      if (k === 'left' || k === 'right') c.state.i = clamp(c.state.i + (k === 'right' ? 1 : -1), 0, v.row.length - 1);
      else if (k === 'up') { if (v.np) v.zone = 'np'; }
      else if (k === 'down') { c.ctl.openGrid('All apps', E.items.all(c.data)); return true; }
      else if (k === 'ok') { c.act(v.row[c.state.i], v.els[c.state.i]); return true; }
      else if (k === 'back') c.state.i = 0;
      this.paint(c); return true;
    }
  });

  // =================================================================================== C Scene Stage
  // Activity cards from the scenes the household allowed on Home (PDF p.25; p.26: "secure helper action",
  // "new scoped endpoint"). OK starts one; the result is said on screen.
  E.register({
    id: 'scenes', photo: false, hints: ['◀ ▶ Scenes', 'OK Start the scene', '▼ Favourites', '▲ Sources'],
    render: function (c) {
      var v = c.ctl.v = {}, d = c.data;
      header(c, 'One press. A whole evening.', 'Set the mood.', 'Your scenes, made in the phone panel.');
      v.sc = E.items.scenes(d);
      v.cards = add(c.content, el('div', 'x-sc-cards'));
      v.els = v.sc.map(function (s) {
        var card = add(v.cards, el('div', 'x-scene'));
        card.style.background = 'radial-gradient(circle at 50% 35%, ' + s.color + ', #101b28 70%)';
        add(card, el('div', 'x-scene-e', s.emoji));
        add(card, el('div', 'x-scene-n', s.name));
        add(card, el('div', 'x-scene-s', s.sub));
        return card;
      });
      if (!v.sc.length) {
        var e = add(v.cards, el('div', 'x-empty x-sc-empty'));
        e.innerHTML = '<b>No scenes on Home yet.</b><br>In the phone panel: Layout, Scene Stage, then choose which scenes may start from the TV.';
      }
      v.btn = add(c.content, el('div', 'x-btn x-sc-btn'));
      v.favs = E.items.favorites(d).slice(0, 7);
      v.fels = strip(c.content, v.favs, 'x-sc-favs');
      v.zone = v.sc.length ? 'cards' : 'favs';
      c.state.i = clamp(indexOfId(v.sc, c.state.id, c.state.i || 0), 0, Math.max(0, v.sc.length - 1));
      this.paint(c);
    },
    refresh: function (c) { var z = c.ctl.v.zone; this.render(c); if (c.ctl.v.sc.length) c.ctl.v.zone = z; this.paint(c); },
    paint: function (c) {
      var v = c.ctl.v, s = v.sc[c.state.i];
      E.mark(v.els, c.state.i, v.zone === 'cards');
      E.mark(v.fels, c.state.f || 0, v.zone === 'favs');
      v.btn.textContent = v.zone === 'cards' && s ? 'OK  START  ' + s.name.toUpperCase() : v.zone === 'favs' && v.favs[c.state.f || 0] ? 'OK  OPEN  ' + v.favs[c.state.f || 0].name.toUpperCase() : '';
      c.state.id = s && s.id;
    },
    key: function (k, c) {
      var v = c.ctl.v;
      if (v.zone === 'favs') {
        if (k === 'left' || k === 'right') c.state.f = clamp((c.state.f || 0) + (k === 'right' ? 1 : -1), 0, v.favs.length - 1);
        else if ((k === 'up' || k === 'back') && v.sc.length) v.zone = 'cards';
        else if (k === 'up') { c.ctl.openSources(); return true; }
        else if (k === 'ok') { c.act(v.favs[c.state.f || 0], v.fels[c.state.f || 0]); return true; }
        this.paint(c); return true;
      }
      if (k === 'left' || k === 'right') c.state.i = clamp(c.state.i + (k === 'right' ? 1 : -1), 0, v.sc.length - 1);
      else if (k === 'down') v.zone = 'favs';
      else if (k === 'up') { c.ctl.openSources(); return true; }
      else if (k === 'ok') { c.act(v.sc[c.state.i], v.els[c.state.i]); return true; }
      else if (k === 'back') c.state.i = 0;
      this.paint(c); return true;
    }
  });
})(window);
