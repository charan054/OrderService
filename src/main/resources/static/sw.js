// Keeps the shell installable and shows offline.html when a page can't be reached. Only the offline page is cached;
// API calls and prices are never stored, so nothing stale or private can be served from here.
const CACHE = "charan-mart-offline-v2";
self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.add("/offline.html")).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  if (e.request.mode !== "navigate") return;
  e.respondWith(fetch(e.request).catch(() => caches.match("/offline.html")));
});

// Browser notifications (Web Push): shown even when the shop is not open; tapping one opens (or focuses) the shop.
self.addEventListener("push", (e) => {
  let data = {};
  try { data = e.data ? e.data.json() : {}; } catch (err) { data = { title: "Charan Mart", body: e.data ? e.data.text() : "" }; }
  e.waitUntil(self.registration.showNotification(data.title || "Charan Mart", {
    body: data.body || "",
    icon: "/icon.svg",
    data: { url: data.url || "/shop.html" }
  }));
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const target = (e.notification.data && e.notification.data.url) || "/shop.html";
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
    for (const w of windows) {
      if (w.url.includes("/shop.html") && "focus" in w) return w.focus();
    }
    return self.clients.openWindow(target);
  }));
});
