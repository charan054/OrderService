// Section finder for the long admin page: a sticky search box that jumps to any card, per-card collapse (remembered),
// and Collapse all / Expand all. Built from the cards that are already on the page, so a new card shows up by itself.
(function () {
  var cards = Array.prototype.slice.call(document.querySelectorAll(".card")).filter(function (c) { return c.querySelector(".card-header h2"); });
  if (!cards.length) return;
  var STORE = "adminCollapsedCards";
  function titleOf(c) { return c.querySelector(".card-header h2").textContent.trim(); }
  function load() { try { return JSON.parse(localStorage.getItem(STORE) || "[]"); } catch (e) { return []; } }
  function save(list) { try { localStorage.setItem(STORE, JSON.stringify(list)); } catch (e) {} }
  // First visit (nothing remembered yet): start with only what every admin needs open - sign-in, the key and the to-do list -
  // and the other cards folded away; the finder above opens any of them. After that the choices made here are remembered.
  var firstVisit = false;
  try { firstVisit = localStorage.getItem(STORE) === null; } catch (e) {}
  var collapsed = firstVisit
    ? cards.map(titleOf).filter(function (t) { return ["Admin Sign-in", "Service Credentials", "Needs Attention"].indexOf(t) < 0; })
    : load();
  cards.forEach(function (c, i) {
    if (!c.id) c.id = "card-auto-" + i;
    if (collapsed.indexOf(titleOf(c)) >= 0) c.classList.add("collapsed");
    c.querySelector(".card-header h2").addEventListener("click", function () {
      c.classList.toggle("collapsed");
      save(cards.filter(function (x) { return x.classList.contains("collapsed"); }).map(titleOf));
    });
  });

  var bar = document.createElement("div");
  bar.id = "adminNav";
  bar.innerHTML = '<span class="nav-title">' + cards.length + ' sections</span>' +
    '<div class="nav-search"><input id="navSearch" type="search" placeholder="Find a section... (press /)" autocomplete="off" aria-label="Find a section">' +
    '<div id="navResults" role="listbox"></div></div>' +
    '<button type="button" id="navCollapse">Collapse all</button><button type="button" id="navExpand">Expand all</button>' +
    '<button type="button" id="navTop" aria-label="Back to top">&#8593; Top</button>';
  var main = document.querySelector("main.container");
  main.parentNode.insertBefore(bar, main);

  var input = document.getElementById("navSearch");
  var results = document.getElementById("navResults");
  var active = -1;
  function matches(q) {
    q = q.trim().toLowerCase();
    if (!q) return [];
    return cards.filter(function (c) {
      return titleOf(c).toLowerCase().indexOf(q) >= 0 || (c.querySelector("p") ? c.querySelector("p").textContent.toLowerCase().indexOf(q) >= 0 : false);
    }).slice(0, 12);
  }
  function render() {
    var list = matches(input.value);
    active = list.length ? 0 : -1;
    if (!input.value.trim()) { results.classList.remove("open"); return; }
    results.innerHTML = list.length ? "" : '<div class="none">No section matches.</div>';
    list.forEach(function (c, i) {
      var b = document.createElement("button");
      b.type = "button";
      b.textContent = titleOf(c);
      if (i === 0) b.className = "active";
      b.addEventListener("click", function () { go(c); });
      results.appendChild(b);
    });
    results.classList.add("open");
  }
  function go(c) {
    c.classList.remove("collapsed");
    save(cards.filter(function (x) { return x.classList.contains("collapsed"); }).map(titleOf));
    results.classList.remove("open");
    input.value = "";
    c.scrollIntoView({ behavior: "smooth", block: "start" });
    c.classList.remove("flash");
    void c.offsetWidth;
    c.classList.add("flash");
  }
  input.addEventListener("input", render);
  input.addEventListener("keydown", function (e) {
    var buttons = results.querySelectorAll("button");
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!buttons.length) return;
      active = (active + (e.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
      buttons.forEach(function (b, i) { b.className = i === active ? "active" : ""; });
    } else if (e.key === "Enter" && buttons[active]) {
      e.preventDefault();
      buttons[active].click();
    } else if (e.key === "Escape") {
      input.value = "";
      results.classList.remove("open");
      input.blur();
    }
  });
  document.addEventListener("click", function (e) { if (!bar.contains(e.target)) results.classList.remove("open"); });
  document.addEventListener("keydown", function (e) {
    var tag = (document.activeElement && document.activeElement.tagName) || "";
    if (e.key === "/" && !/INPUT|TEXTAREA|SELECT/.test(tag)) { e.preventDefault(); input.focus(); }
  });
  function setAll(on) {
    cards.forEach(function (c) { c.classList.toggle("collapsed", on); });
    save(on ? cards.map(titleOf) : []);
  }
  document.getElementById("navCollapse").addEventListener("click", function () { setAll(true); });
  document.getElementById("navExpand").addEventListener("click", function () { setAll(false); });
  document.getElementById("navTop").addEventListener("click", function () { window.scrollTo({ top: 0, behavior: "smooth" }); });
})();
