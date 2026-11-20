// The admin panel's service worker (only on its https address): shows the helper's notifications, even with the
// panel closed, and runs their buttons ("Keep watching"). Nothing is cached: the panel always comes from the helper.
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });

self.addEventListener('push', function (e) {
  var d = {};
  try { d = e.data ? e.data.json() : {}; } catch (x) { d = { title: 'Home', body: e.data ? e.data.text() : '' }; }
  var actions = d.actions || [];
  e.waitUntil(self.registration.showNotification(d.title || 'Home', {
    body: d.body || '',
    tag: d.tag || undefined,
    renotify: !!d.tag,
    icon: '/api/admin/app/icon-192.png',
    badge: '/api/admin/app/icon-192.png',
    data: { url: d.url || '/admin', actions: actions },
    actions: actions.slice(0, 2).map(function (a) { return { action: a.id, title: a.title }; })
  }));
});

self.addEventListener('notificationclick', function (e) {
  e.notification.close();
  var data = e.notification.data || {}, a = (data.actions || []).filter(function (x) { return x.id === e.action; })[0];
  if (a && a.post) {   // a button: do it without opening the panel
    e.waitUntil(fetch(a.post, { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-CYT': '1' }, body: JSON.stringify(a.body || {}) }));
    return;
  }
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (ws) {
    for (var i = 0; i < ws.length; i++) if (ws[i].url.indexOf('/admin') >= 0 && 'focus' in ws[i]) return ws[i].focus();
    return self.clients.openWindow(data.url || '/admin');
  }));
});
