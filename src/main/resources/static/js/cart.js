    const BASE_URL = "/cart";
    let products = [];
    let selectedItems = [];

    const serviceKeyInput = document.getElementById("serviceKey");
    const buyerAuthorizationInput = document.getElementById("buyerAuthorization");
    // The shared Service Key and the buyer token are secrets: keep them for this tab only (sessionStorage), never in
    // localStorage where they would outlive the session and be readable by anything else that runs on this origin.
    function readSecret(name) {
      try {
        localStorage.removeItem(name); // older versions stored it here
        return sessionStorage.getItem(name) || "";
      } catch (e) { return ""; }
    }
    function saveSecret(name, value) {
      try { sessionStorage.setItem(name, value); } catch (e) { /* works for this page view only */ }
    }
    serviceKeyInput.value = readSecret("cartServiceKey");
    buyerAuthorizationInput.value = readSecret("cartBuyerAuthorization");
    serviceKeyInput.addEventListener("input", () => saveSecret("cartServiceKey", serviceKeyInput.value));
    buyerAuthorizationInput.addEventListener("input", () => saveSecret("cartBuyerAuthorization", buyerAuthorizationInput.value));

    // ---------- admin sign-in (named accounts) ----------
    const ADMIN_ORIGIN = "";
    const adminUsernameInput = document.getElementById("adminUsername");
    const adminPasswordInput = document.getElementById("adminPassword");
    const adminCodeInput = document.getElementById("adminCode");
    let adminToken = null;
    try { adminToken = sessionStorage.getItem("cartAdminToken"); } catch (e) { /* storage blocked: sign in each visit */ }

    // While signed in as a named admin, every request to this service carries that session instead of the shared
    // Service Key, so the audit log can name the person. Done once here so no call site needs to know.
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (input, init = {}) => {
      const url = typeof input === "string" ? input : input.url;
      const sameOrigin = url.startsWith("/") && !url.startsWith("//") || url.startsWith(window.location.origin + "/");
      if (!adminToken || !sameOrigin) return nativeFetch(input, init);
      const headers = new Headers(init.headers || {});
      headers.delete("X-Service-Key");
      headers.set("X-Admin-Token", adminToken);
      return nativeFetch(input, { ...init, headers });
    };

    function renderAdminIdentity(identity) {
      document.getElementById("adminIdentity").textContent = identity
        ? `Signed in as ${identity.username} (${identity.role}).`
        : "Not signed in. Sign in with your own admin account (what you can do depends on your role), or use the shared Service Key below.";
    }

    function forgetAdminSession() {
      adminToken = null;
      try { sessionStorage.removeItem("cartAdminToken"); } catch (e) { /* nothing stored */ }
      renderAdminIdentity(null);
    }

    async function adminSignIn() {
      const username = adminUsernameInput.value.trim();
      const password = adminPasswordInput.value;
      if (!username || !password) {
        showStatus("Enter your admin username and password.", "error");
        return;
      }
      try {
        const response = await nativeFetch(`${ADMIN_ORIGIN}/admin/login`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username, password, code: adminCodeInput.value.trim() || undefined })
        });
        if (!response.ok) {
          const problem = (await response.text()) || "Sign-in failed.";
          // The password was right but this account has two-step on: ask for the code from the authenticator app.
          if (response.status === 401 && /authenticator/i.test(problem)) {
            adminCodeInput.classList.remove("hidden");
            adminCodeInput.focus();
          }
          showStatus(problem, "error");
          return;
        }
        const login = await response.json();
        adminToken = login.token;
        try { sessionStorage.setItem("cartAdminToken", adminToken); } catch (e) { /* works for this page view only */ }
        adminPasswordInput.value = "";
        adminCodeInput.value = "";
        adminCodeInput.classList.add("hidden");
        renderAdminIdentity(login);
        showStatus(`Signed in as ${login.username} (${login.role}).`, "success");
      } catch (error) {
        showStatus("Sign-in failed: " + error.message, "error");
      }
    }

    // ---------- two-step sign-in (authenticator app) ----------
    async function twoStepSetup() {
      if (!adminToken) return showStatus("Sign in with your own admin account first.", "error");
      const start = await window.fetch(`${ADMIN_ORIGIN}/admin/me/2fa/setup`, { method: "POST" });
      if (!start.ok) return showStatus((await start.text()) || "Could not start the setup.", "error");
      const setup = await start.json();
      const code = prompt(`Add this key to your authenticator app (Google Authenticator, Authy, ...):\n\n${setup.secret}\n\nThen type the 6-digit code it shows:`);
      if (!code) return showStatus("Two-step setup not finished - nothing changed.", "");
      const enable = await window.fetch(`${ADMIN_ORIGIN}/admin/me/2fa/enable`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: code.trim() })
      });
      if (!enable.ok) return showStatus((await enable.text()) || "That code was not right.", "error");
      const done = await enable.json();
      alert("Two-step sign-in is ON.\n\nSave these recovery codes somewhere safe. Each works once if you lose your phone, and they are not shown again:\n\n"
        + done.recoveryCodes.join("\n"));
      showStatus("Two-step sign-in is on for your account.", "success");
    }

    async function twoStepDisable() {
      if (!adminToken) return showStatus("Sign in with your own admin account first.", "error");
      const password = prompt("To turn two-step off, type your password:");
      if (!password) return;
      const code = prompt("And a current authenticator code (or a recovery code):");
      if (!code) return;
      const r = await window.fetch(`${ADMIN_ORIGIN}/admin/me/2fa/disable`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password, code: code.trim() })
      });
      if (!r.ok) return showStatus((await r.text()) || "Could not turn it off.", "error");
      forgetAdminSession();
      showStatus("Two-step sign-in is off. Please sign in again.", "success");
    }

    async function adminSignOut() {
      if (adminToken) {
        try { await nativeFetch(`${ADMIN_ORIGIN}/admin/logout`, { method: "POST", headers: { "X-Admin-Token": adminToken } }); } catch (e) { /* the token expires on its own */ }
      }
      forgetAdminSession();
      showStatus("Signed out.", "success");
    }

    // A saved session may have expired (or the account been disabled) since last visit.
    async function restoreAdminSession() {
      if (!adminToken) return;
      try {
        const response = await window.fetch(`${ADMIN_ORIGIN}/admin/me`);
        if (response.ok) renderAdminIdentity(await response.json());
        else forgetAdminSession();
      } catch (e) {
        renderAdminIdentity(null);
      }
    }
    restoreAdminSession();

    // When the server has switched the shared key off for browsers (ADMIN_REQUIRE_NAMED_LOGIN), stop offering it: the
    // saved copy is wiped from this browser and the field is disabled, so everyone signs in as themselves.
    let namedLoginRequired = false;
    async function loadAdminConfig() {
      try {
        const response = await nativeFetch(`${ADMIN_ORIGIN}/admin/config`);
        if (!response.ok) return;
        namedLoginRequired = (await response.json()).namedLoginRequired === true;
      } catch (e) {
        return;
      }
      if (namedLoginRequired) {
        try { sessionStorage.removeItem("cartServiceKey"); } catch (e) { /* nothing stored */ }
        serviceKeyInput.value = "";
        serviceKeyInput.disabled = true;
        serviceKeyInput.placeholder = "Not accepted in the browser - sign in with your admin account above";
      }
    }
    loadAdminConfig();

    function requireServiceKey() {
      // Signed in as a named admin: the fetch wrapper above swaps this placeholder for the X-Admin-Token session.
      if (adminToken) return "admin-session";
      if (namedLoginRequired) {
        showStatus("Sign in with your admin account above first.", "error");
        return null;
      }
      const key = serviceKeyInput.value;
      if (!key) {
        showStatus("Please enter the Service Key above first.", "error");
        return null;
      }
      return key;
    }

    function requireBuyerAuthorization() {
      const authorization = buyerAuthorizationInput.value;
      if (!authorization) {
        showStatus("Please enter the buyer's Authorization header value above first.", "error");
        return null;
      }
      return authorization;
    }

    async function apiCall(url, options = {}) {
      try {
        showStatus("Processing request...", "");
        // Customer lookups (orders/wishlist/addresses/loyalty/profile by phone) are no longer public now that the
        // storefront has verified sign-in - this dashboard reads them with the service key, sent on every call.
        const headers = new Headers(options.headers || {});
        if (serviceKeyInput.value && !headers.has("X-Service-Key")) headers.set("X-Service-Key", serviceKeyInput.value);
        const response = await fetch(url, { ...options, headers });

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(errorText || `HTTP Error: ${response.status}`);
        }

        const text = await response.text();
        if (!text) return null;
        return JSON.parse(text);
      } catch (error) {
        showStatus("Request failed: " + error.message, "error");
        openResultTab("Request Failed", { error: error.message });
        throw error;
      }
    }

    function showStatus(message, className) {
      const status = document.getElementById("status");
      status.className = "status-panel " + (className || "");
      status.textContent = message;
    }

    function escapeHtml(value) {
      return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
    }

    function formatLabel(key) {
      return key
        .replace(/([A-Z])/g, " $1")
        .replace(/_/g, " ")
        .replace(/^./, ch => ch.toUpperCase());
    }

    function valueHtml(value) {
      if (value === null || value === undefined || value === "") return "-";
      if (Array.isArray(value)) return `${value.length} item(s)`;
      if (typeof value === "object") return escapeHtml(JSON.stringify(value));
      return escapeHtml(value);
    }

    function getProductId(product) {
      return product.productId ?? product.id ?? product.product_id;
    }

    function getProductName(product) {
      return product.productName ?? product.name ?? product.product_name ?? "-";
    }

    function getProductPrice(product) {
      return product.productPrice ?? product.price ?? product.product_price ?? 0;
    }

    function getProductStock(product) {
      return product.productStock ?? product.stock ?? product.product_stock ?? 0;
    }

    function getProductCategory(product) {
      return product.productCategory ?? product.category ?? product.product_category ?? "-";
    }

    function renderProductsTable() {
      const tbody = document.getElementById("productTableBody");

      if (!products || products.length === 0) {
        tbody.innerHTML = `<tr><td colspan="8" style="text-align:center; color:#64748b; padding:26px;">No products found.</td></tr>`;
        return;
      }

      tbody.innerHTML = products.map((product, index) => {
        const productId = getProductId(product);
        return `
          <tr>
            <td>${index + 1}</td>
            <td>${escapeHtml(productId)}</td>
            <td>${escapeHtml(getProductName(product))}</td>
            <td>${escapeHtml(getProductCategory(product))}</td>
            <td>${escapeHtml(getProductPrice(product))}</td>
            <td>${escapeHtml(getProductStock(product))}</td>
            <td><input class="qty-input" type="number" id="qty_${productId}" min="1" placeholder="Qty" /></td>
            <td><button class="small-btn green" onclick="addItemToSelection('${productId}')">Add</button></td>
          </tr>
        `;
      }).join("");
    }

    function addItemToSelection(productId) {
      const phno = document.getElementById("customerPhno").value;
      const qty = document.getElementById(`qty_${productId}`).value;

      if (!phno) return showStatus("Please enter customer phone number first.", "error");
      if (!qty || Number(qty) <= 0) return showStatus("Please enter valid quantity.", "error");

      const product = products.find(p => String(getProductId(p)) === String(productId));
      if (!product) return showStatus("Product not found in loaded products.", "error");

      const existing = selectedItems.find(item => String(item.productId) === String(productId));

      if (existing) {
        existing.productQuantity = Number(existing.productQuantity) + Number(qty);
      } else {
        selectedItems.push({
          productId: Number(productId),
          productQuantity: Number(qty)
        });
      }

      updateCartSummary();
      showStatus(`${getProductName(product)} added to cart selection.`, "success");
    }

    function updateCartSummary() {
      document.getElementById("cartSummary").textContent = `Selected items: ${selectedItems.length}`;
    }

    function clearSelection() {
      selectedItems = [];
      updateCartSummary();
      showStatus("Selected cart items cleared.", "success");
    }

    async function loadProducts() {
      const data = await apiCall(`${BASE_URL}/display`);
      products = Array.isArray(data) ? data : [data];
      renderProductsTable();
      showStatus("Products loaded successfully. Now select quantity and add items to cart.", "success");
    }

    async function placeOrder() {
      const phno = document.getElementById("customerPhno").value;

      if (!phno) return showStatus("Please enter customer phone number.", "error");
      if (selectedItems.length === 0) return showStatus("Please add at least one product to cart.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const authorization = requireBuyerAuthorization();
      if (!authorization) return;

      const couponCode = document.getElementById("couponCode").value.trim();
      const shippingAddressId = document.getElementById("orderShippingAddressId").value;
      const pointsRedeemed = document.getElementById("orderPointsRedeemed").value;

      const cart = {
        customerPhno: Number(phno),
        orderItems: selectedItems,
        couponCode: couponCode || null,
        shippingAddressId: shippingAddressId ? Number(shippingAddressId) : null,
        pointsRedeemed: pointsRedeemed ? Number(pointsRedeemed) : null
      };

      const data = await apiCall(`${BASE_URL}/add`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Service-Key": serviceKey,
          "Authorization": authorization
        },
        body: JSON.stringify(cart)
      });

      showStatus("Cart order placed successfully. Result opened in new tab.", "success");
      openResultTab("Cart Order Created", data);
      selectedItems = [];
      updateCartSummary();
      loadProducts();
    }

    async function getAllOrders() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`${BASE_URL}/all`, {
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus("All cart orders loaded successfully. Result opened in new tab.", "success");
      openResultTab("All Cart Orders", data);
    }

    async function getOrdersByPhno() {
      const phno = document.getElementById("searchPhno").value;
      if (!phno) return showStatus("Please enter customer phone number.", "error");

      const data = await apiCall(`${BASE_URL}/byphno?phno=${encodeURIComponent(phno)}`);
      showStatus("Orders by phone number loaded successfully. Result opened in new tab.", "success");
      openResultTab("Orders By Phone Number", data);
    }

    // ---- Revenue over time ----
    let revenueSeries = null;

    function localIsoDate(d) {
      const m = String(d.getMonth() + 1).padStart(2, "0");
      const day = String(d.getDate()).padStart(2, "0");
      return `${d.getFullYear()}-${m}-${day}`;
    }

    (function initRevenueDates() {
      const to = new Date();
      const from = new Date();
      from.setDate(from.getDate() - 29);
      document.getElementById("revTo").value = localIsoDate(to);
      document.getElementById("revFrom").value = localIsoDate(from);
    })();

    const rupees = n => "₹" + Number(n).toLocaleString("en-IN", { minimumFractionDigits: 0, maximumFractionDigits: 2 });

    // Rounds a maximum up to a "nice" axis top (1, 2, 2.5, 5 x 10^n) and returns ticks from 0 to it.
    function niceTicks(max, count = 4) {
      if (max <= 0) return { top: 1, ticks: [0, 1] };
      const rough = max / count;
      const pow = Math.pow(10, Math.floor(Math.log10(rough)));
      const step = [1, 2, 2.5, 5, 10].map(m => m * pow).find(v => v >= rough);
      const top = Math.ceil(max / step) * step;
      const ticks = [];
      for (let v = 0; v <= top + step / 1000; v += step) ticks.push(Math.round(v * 100) / 100);
      return { top, ticks };
    }

    function periodLabel(point, bucket, short) {
      const d = new Date(point.periodStart + "T00:00:00");
      const text = d.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
      return bucket === "week" && !short ? "Week of " + text : text;
    }

    function revenueQuery() {
      const params = new URLSearchParams({
        bucket: document.getElementById("revBucket").value,
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"
      });
      const from = document.getElementById("revFrom").value;
      const to = document.getElementById("revTo").value;
      if (from) params.set("from", from);
      if (to) params.set("to", to);
      return params.toString();
    }

    async function loadRevenueChart() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      revenueSeries = await apiCall(`${BASE_URL}/analytics/timeseries?${revenueQuery()}`, {
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus("Revenue chart loaded.", "success");
      renderRevenueChart();
    }

    function renderRevenueChart() {
      const data = revenueSeries;
      const summary = document.getElementById("revSummary");
      const chart = document.getElementById("revChart");
      const table = document.getElementById("revTable");
      if (!data) return;

      summary.innerHTML = `
        <div><b>${rupees(data.totalRevenue)}</b><span>Revenue, ${data.from} to ${data.to}</span></div>
        <div><b>${data.totalOrders}</b><span>Orders placed</span></div>
        <div><b>${data.totalOrders ? rupees(data.totalRevenue / data.totalOrders) : "-"}</b><span>Average per order</span></div>
        ${data.undatedOrders ? `<div><b>${data.undatedOrders}</b><span>Older orders with no date (not charted)</span></div>` : ""}`;

      table.innerHTML = `<table class="rev-table"><thead><tr><th>${data.bucket === "week" ? "Week starting" : "Day"}</th><th>Orders</th><th>Revenue</th></tr></thead><tbody>${
        data.points.map(pt => `<tr><td>${escapeHtml(pt.periodStart)}</td><td>${pt.orders}</td><td>${rupees(pt.revenue)}</td></tr>`).join("")
      }</tbody></table>`;

      if (!data.points.some(pt => pt.revenue > 0 || pt.orders > 0)) {
        chart.innerHTML = `<div class="rev-empty">No orders in this period.</div>`;
        return;
      }

      const W = 760, H = 280, left = 56, right = 12, top = 12, bottom = 34;
      const plotW = W - left - right, plotH = H - top - bottom;
      const { top: yTop, ticks } = niceTicks(Math.max(...data.points.map(pt => pt.revenue)));
      const n = data.points.length;
      const slot = plotW / n;
      const barW = Math.max(2, Math.min(36, slot - 2)); // thin marks; 2px gap between neighbours
      const y = v => top + plotH - (v / yTop) * plotH;
      const labelEvery = Math.ceil(n / Math.floor(plotW / 54));

      const grid = ticks.map(t => `
        <line class="grid-line" x1="${left}" x2="${W - right}" y1="${y(t)}" y2="${y(t)}"></line>
        <text class="axis-text" x="${left - 8}" y="${y(t) + 4}" text-anchor="end">${rupees(t)}</text>`).join("");

      const bars = data.points.map((pt, i) => {
        const cx = left + slot * i + slot / 2;
        const x = cx - barW / 2;
        const h = Math.max(0, plotH - (y(pt.revenue) - top));
        const r = Math.min(4, barW / 2, h);
        // Rounded top ends only, anchored flat to the baseline.
        const path = h <= 0 ? "" : `M${x},${top + plotH} V${top + plotH - h + r} Q${x},${top + plotH - h} ${x + r},${top + plotH - h} H${x + barW - r} Q${x + barW},${top + plotH - h} ${x + barW},${top + plotH - h + r} V${top + plotH} Z`;
        const label = `${periodLabel(pt, data.bucket)}: ${rupees(pt.revenue)}, ${pt.orders} order${pt.orders === 1 ? "" : "s"}`;
        const xLabel = i % labelEvery === 0 ? `<text class="axis-text" x="${cx}" y="${H - 12}" text-anchor="middle">${periodLabel(pt, data.bucket, true)}</text>` : "";
        return `<g>
          <rect class="hit" x="${left + slot * i}" y="${top}" width="${slot}" height="${plotH}" tabindex="0"
                role="img" aria-label="${escapeHtml(label)}" data-i="${i}" data-cx="${cx}" data-top="${y(pt.revenue)}"></rect>
          <path class="bar" d="${path}"></path>${xLabel}</g>`;
      }).join("");

      chart.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="group" aria-label="Revenue per ${data.bucket}">${grid}${bars}</svg><div class="rev-tip" hidden></div>`;

      const tip = chart.querySelector(".rev-tip");
      const svg = chart.querySelector("svg");
      const show = el => {
        const pt = data.points[Number(el.dataset.i)];
        tip.innerHTML = `<b>${escapeHtml(periodLabel(pt, data.bucket))}</b><br>${rupees(pt.revenue)} revenue<br>${pt.orders} order${pt.orders === 1 ? "" : "s"}`;
        tip.hidden = false;
        const scale = svg.getBoundingClientRect().width / W;
        const maxLeft = chart.clientWidth - tip.offsetWidth / 2;
        tip.style.left = Math.min(Math.max(Number(el.dataset.cx) * scale, tip.offsetWidth / 2), maxLeft) + "px";
        tip.style.top = Math.max(Number(el.dataset.top) * scale - 8, tip.offsetHeight + 4) + "px";
      };
      chart.querySelectorAll(".hit").forEach(el => {
        el.addEventListener("mouseenter", () => show(el));
        el.addEventListener("focus", () => show(el));
        el.addEventListener("mouseleave", () => { tip.hidden = true; });
        el.addEventListener("blur", () => { tip.hidden = true; });
      });
    }

    function toggleRevenueView() {
      const table = document.getElementById("revTable");
      const showTable = table.hidden;
      table.hidden = !showTable;
      document.getElementById("revChart").hidden = showTable;
      document.getElementById("revViewToggle").textContent = showTable ? "View as chart" : "View as table";
    }

    // ---------- price-match requests ----------
    const PM_URL = "/pricematch";
    let priceMatches = [];

    function renderPriceMatches() {
      const wrap = document.getElementById("pmTableWrap");
      if (!priceMatches.length) {
        wrap.innerHTML = "<p>No open price-match requests.</p>";
        return;
      }
      wrap.innerHTML = `<table class="orders-table"><thead><tr>
        <th>#</th><th>Product</th><th>Customer</th><th>Our price</th><th>Their price</th><th>Link</th><th>Note to customer</th><th>Start a sale (optional)</th><th>Action</th>
      </tr></thead><tbody>${priceMatches.map((r, i) => `
        <tr>
          <td>${r.id}</td>
          <td>${r.productId}</td>
          <td>${r.customerPhno}</td>
          <td>${Number(r.ourPrice).toFixed(2)}</td>
          <td>${Number(r.competitorPrice).toFixed(2)}</td>
          <td style="white-space:normal; max-width:220px; word-break:break-all;">${/^https?:\/\//i.test(r.competitorUrl || "") ? `<a href="${escapeHtml(r.competitorUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(r.competitorUrl)}</a>` : escapeHtml(r.competitorUrl || "")}</td>
          <td><input type="text" id="pmNote${i}" maxlength="300" placeholder="Optional note" /></td>
          <td><input type="number" id="pmSale${i}" min="0.01" step="0.01" placeholder="Sale price" style="width:90px" />
              <input type="number" id="pmDays${i}" min="1" max="90" value="7" style="width:56px" title="Days the sale runs" /> days</td>
          <td><button class="blue" onclick="resolvePriceMatch(${i}, true)">Approve</button>
              <button onclick="resolvePriceMatch(${i}, false)">Decline</button></td>
        </tr>`).join("")}</tbody></table>`;
    }

    async function loadPriceMatches() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      priceMatches = (await apiCall(`${PM_URL}/open`, { headers: { "X-Service-Key": serviceKey } })) || [];
      renderPriceMatches();
      showStatus(`${priceMatches.length} open price-match request(s).`, "success");
    }

    async function resolvePriceMatch(index, approved) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const r = priceMatches[index];
      const note = document.getElementById("pmNote" + index).value.trim();
      const salePrice = parseFloat(document.getElementById("pmSale" + index).value);
      const saleDays = parseInt(document.getElementById("pmDays" + index).value, 10) || 7;
      let saleQuery = "";
      if (approved && salePrice > 0) {
        if (!confirm(`Approve AND put product ${r.productId} on sale at ${salePrice.toFixed(2)} for ${saleDays} day(s)? Every customer will see the sale price.`)) return;
        saleQuery = `&salePrice=${salePrice}&saleDays=${saleDays}`;
      }
      await apiCall(`${PM_URL}/${r.id}/resolve?approved=${approved}&note=${encodeURIComponent(note)}${saleQuery}`, {
        method: "PUT",
        headers: { "X-Service-Key": serviceKey }
      });
      priceMatches.splice(index, 1);
      renderPriceMatches();
      showStatus(`Request #${r.id} ${approved ? "approved" : "declined"}.`, "success");
    }

    // ---------- customer questions ----------
    const QA_URL = "/questions";
    let pendingQuestions = [];

    function renderQuestionsTable() {
      const wrap = document.getElementById("qaTableWrap");
      if (!pendingQuestions.length) {
        wrap.innerHTML = "<p>No unanswered questions.</p>";
        return;
      }
      wrap.innerHTML = `<table class="orders-table"><thead><tr>
        <th>#</th><th>Product</th><th>Asked by</th><th>Question</th><th>Your answer</th><th>Action</th>
      </tr></thead><tbody>${pendingQuestions.map((q, i) => `
        <tr>
          <td>${q.id}</td>
          <td>${q.productId}</td>
          <td>${q.askerPhno}</td>
          <td style="white-space:normal; min-width:200px;">${escapeHtml(q.question || "")}</td>
          <td><input type="text" id="qaAnswer${i}" maxlength="1000" placeholder="Type an answer" /></td>
          <td><button class="blue" onclick="answerQuestion(${i})">Answer</button>
              <button onclick="deleteQuestion(${i})">Delete</button></td>
        </tr>`).join("")}</tbody></table>`;
    }

    async function loadPendingQuestions() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      pendingQuestions = (await apiCall(`${QA_URL}/pending`, { headers: { "X-Service-Key": serviceKey } })) || [];
      renderQuestionsTable();
      showStatus(`${pendingQuestions.length} unanswered question(s).`, "success");
    }

    async function answerQuestion(index) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const q = pendingQuestions[index];
      const answer = document.getElementById("qaAnswer" + index).value.trim();
      if (!answer) return showStatus("Please type an answer first.", "error");
      await apiCall(`${QA_URL}/${q.id}/answer?answer=${encodeURIComponent(answer)}`, {
        method: "PUT",
        headers: { "X-Service-Key": serviceKey }
      });
      pendingQuestions.splice(index, 1);
      renderQuestionsTable();
      showStatus(`Question #${q.id} answered and published.`, "success");
    }

    async function deleteQuestion(index) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const q = pendingQuestions[index];
      if (!confirm(`Delete question #${q.id}?`)) return;
      await apiCall(`${QA_URL}/${q.id}`, { method: "DELETE", headers: { "X-Service-Key": serviceKey } });
      pendingQuestions.splice(index, 1);
      renderQuestionsTable();
      showStatus(`Question #${q.id} deleted.`, "success");
    }

    // ---------- support requests ----------
    const SUPPORT_URL = "/support/admin/tickets";

    async function loadSupportQueue() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const status = document.getElementById("supportStatus").value;
      const rows = (await apiCall(`${SUPPORT_URL}?status=${status}`, { headers: { "X-Service-Key": serviceKey } })) || [];
      document.getElementById("supportDetail").innerHTML = "";
      document.getElementById("supportTableWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>#</th><th>Order</th><th>Customer</th><th>Problem</th><th>Status</th><th>Last update</th><th></th></tr></thead><tbody>${rows.map(t => `
            <tr><td>${t.id}</td><td>#${t.orderId}</td><td>${t.customerPhno}</td><td>${escapeHtml(t.category)}</td>
            <td>${escapeHtml(t.status)}</td><td>${new Date(t.updatedAt).toLocaleString()}</td>
            <td><button onclick="openSupportTicket(${t.id})">Open</button></td></tr>`).join("")}</tbody></table>`
        : "<p>No requests here.</p>";
      showStatus(`${rows.length} support request(s).`, "success");
    }

    async function openSupportTicket(id) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const v = await apiCall(`${SUPPORT_URL}/${id}`, { headers: { "X-Service-Key": serviceKey } });
      const t = v.ticket;
      const money = n => "₹" + Number(n).toFixed(2);
      const photo = t.photoUrl && /^https?:\/\//.test(t.photoUrl)
        ? `<p>Photo: <a href="${escapeHtml(t.photoUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(t.photoUrl)}</a></p>` : "";
      document.getElementById("supportDetail").innerHTML = `
        <h3 style="margin-top:16px;">Request #${t.id} &middot; ${escapeHtml(t.category)} &middot; ${escapeHtml(t.status)}</h3>
        <p>Customer ${t.customerPhno} &middot; Order #${t.orderId}${t.productId ? " &middot; product " + t.productId : ""}
          ${v.order ? ` &middot; order is ${escapeHtml(v.order.status)}, ${escapeHtml(v.order.paymentMethod)}${v.order.paid ? " (paid)" : ""}, total ${money(v.order.totalPrice)}, refunded ${money(v.order.refundedAmount)}` : " &middot; order no longer exists"}</p>
        ${photo}
        ${v.messages.map(m => `<p><strong>${m.author === "STAFF" ? "Staff" : "Customer"}</strong> &middot; ${new Date(m.createdAt).toLocaleString()}<br>${escapeHtml(m.body)}</p>`).join("")}
        ${t.resolutionNote ? `<p><em>Resolution: ${escapeHtml(t.resolutionNote)}</em></p>` : ""}
        <textarea id="supportReply" rows="3" maxlength="1000" placeholder="Reply to the customer (emailed to them)" style="width:100%;"></textarea>
        <div class="orders-actions">
          <button class="blue" onclick="replySupportTicket(${t.id})">Send Reply</button>
          ${t.status !== "RESOLVED" ? `<button onclick="resolveSupportTicket(${t.id})">Mark Resolved</button>` : ""}
        </div>`;
    }

    async function replySupportTicket(id) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const message = document.getElementById("supportReply").value.trim();
      if (!message) return showStatus("Please type a reply first.", "error");
      await apiCall(`${SUPPORT_URL}/${id}/reply`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({ message })
      });
      showStatus(`Reply sent on request #${id}.`, "success");
      await openSupportTicket(id);
    }

    async function resolveSupportTicket(id) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const note = prompt("Resolution note for the customer (optional), e.g. Refunded the damaged item:", "");
      if (note === null) return;
      await apiCall(`${SUPPORT_URL}/${id}/resolve${note.trim() ? "?note=" + encodeURIComponent(note.trim()) : ""}`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus(`Request #${id} resolved.`, "success");
      await openSupportTicket(id);
    }

    // ---------- customer lookup ----------
    async function lookupCustomer() {
      const phno = document.getElementById("lookupPhno").value.trim();
      const email = document.getElementById("lookupEmail").value.trim();
      if (!phno === !email) return showStatus("Enter a phone number OR an email (not both).", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const query = phno ? `phno=${encodeURIComponent(phno)}` : `email=${encodeURIComponent(email)}`;
      const c = await apiCall(`/customer/admin/lookup?${query}`, { headers: { "X-Service-Key": serviceKey } });
      const money = v => "₹" + Number(v).toFixed(2);
      const statuses = Object.entries(c.ordersByStatus).map(([s, n]) => `${escapeHtml(s)}: ${n}`).join(" &middot; ") || "none";
      document.getElementById("lookupResult").innerHTML = `
        <p><strong>${c.phno}</strong> &middot; ${c.email ? escapeHtml(c.email) + " (verified " + new Date(c.emailVerifiedAt).toLocaleDateString() + ")" : "never signed in to the storefront"}</p>
        <p>${c.totalOrders} orders (${statuses}) &middot; net spend ${money(c.netSpend)}${c.averageOrderValue > 0 ? " &middot; average order " + money(c.averageOrderValue) : ""}</p>
        ${c.firstOrderAt ? `<p>Customer since ${new Date(c.firstOrderAt).toLocaleDateString()} &middot; last order ${new Date(c.lastOrderAt).toLocaleDateString()}</p>` : ""}
        <p>Loyalty: ${escapeHtml(c.loyaltyTier)}, ${c.loyaltyPointsBalance} points (${c.lifetimePointsEarned} earned in total) &middot; ${c.wishlistCount} wishlisted &middot; ${c.reviewCount} reviews</p>
        ${c.recentOrders.length ? `<table class="orders-table"><thead><tr><th>Order</th><th>Status</th><th>Payment</th><th>Total</th><th>Refunded</th></tr></thead><tbody>${c.recentOrders.map(o => `
          <tr><td>#${o.orderId}</td><td>${escapeHtml(o.status)}</td><td>${escapeHtml(o.paymentMethod)}${o.paymentMethod === "CASH" ? (o.paid ? " (paid)" : o.status === "CANCELLED" ? "" : " (unpaid)") : ""}</td><td>${money(o.totalPrice)}</td><td>${money(o.refundedAmount)}</td></tr>`).join("")}</tbody></table>` : "<p>No orders.</p>"}
        ${c.addresses.length ? "<p>Addresses:</p><ul>" + c.addresses.map(a => `<li>${escapeHtml(a.label || "Address")}${a.isDefault ? " (default)" : ""}: ${escapeHtml(a.address)}</li>`).join("") + "</ul>" : "<p>No saved addresses.</p>"}
        <div id="codPanel"></div>`;
      showStatus("Customer found.", "success");
      await loadCodPanel(c.phno);
    }

    // Cash-on-delivery status for the customer shown above, with the admin override (see CodRiskService).
    async function loadCodPanel(phno) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const cod = await apiCall(`/customer/admin/cod?phno=${encodeURIComponent(phno)}`, { headers: { "X-Service-Key": serviceKey } });
      const panel = document.getElementById("codPanel");
      if (!cod || !panel) return;
      const state = cod.available
        ? (cod.maxAmount ? `allowed up to ₹${Number(cod.maxAmount).toFixed(2)} (no delivered order yet)` : "allowed")
        : "blocked";
      panel.innerHTML = `
        <p><strong>Cash on delivery:</strong> ${state}${cod.override ? ` &middot; admin override: ${escapeHtml(cod.override)}${cod.overrideNote ? " (" + escapeHtml(cod.overrideNote) + ")" : ""}` : " &middot; automatic rules"}
          &middot; ${cod.failedCashOrders} recent cancelled/returned cash order(s)</p>
        <div class="orders-actions">
          <button onclick="setCodOverride(${phno}, 'ALLOW')">Always allow COD</button>
          <button onclick="setCodOverride(${phno}, 'BLOCK')">Block COD</button>
          <button onclick="setCodOverride(${phno}, 'AUTO')">Use automatic rules</button>
        </div>`;
    }

    async function setCodOverride(phno, mode) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      let note = "";
      if (mode !== "AUTO") {
        note = prompt("Reason for this override (optional, shown to admins only):", "");
        if (note === null) return;
      }
      await apiCall(`/customer/admin/cod?phno=${encodeURIComponent(phno)}&mode=${mode}${note ? "&note=" + encodeURIComponent(note) : ""}`,
        { method: "PUT", headers: { "X-Service-Key": serviceKey } });
      showStatus("Cash-on-delivery setting saved.", "success");
      await loadCodPanel(phno);
    }

    // ---------- admin accounts ----------
    const ADMIN_ACCOUNTS_URL = `${ADMIN_ORIGIN}/admin/accounts`;

    async function loadAdminAccounts() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall(ADMIN_ACCOUNTS_URL, { headers: { "X-Service-Key": serviceKey } });
      const wrap = document.getElementById("adminAccountsWrap");
      const roleOptions = current => ["SUPPORT", "MANAGER", "OWNER"].map(r => `<option value="${r}"${r === current ? " selected" : ""}>${r}</option>`).join("");
      wrap.innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>Username</th><th>Role</th><th>Status</th><th>Created by</th><th>Last sign-in</th><th>Failed since</th><th>New password</th></tr></thead><tbody>${rows.map(a => `
            <tr><td>${escapeHtml(a.username)}</td>
            <td><select id="role-${escapeHtml(a.username)}">${roleOptions(a.role)}</select>
                <button class="small-btn" onclick="setAdminRole('${escapeHtml(a.username)}')">Save role</button></td>
            <td>${a.active ? "Active" : "Disabled"}
                <button class="small-btn" onclick="setAdminActive('${escapeHtml(a.username)}', ${!a.active})">${a.active ? "Disable" : "Enable"}</button></td>
            <td>${escapeHtml(a.createdBy || "")}</td>
            <td>${a.lastLoginAt ? new Date(a.lastLoginAt).toLocaleString() : "never"}</td>
            <td>${a.failedLogins ? `<strong>${a.failedLogins}</strong> (last ${new Date(a.lastFailedLoginAt).toLocaleString()})` : "0"}</td>
            <td><input type="password" id="pw-${escapeHtml(a.username)}" placeholder="10+ characters" autocomplete="new-password" style="width:150px;margin:0 6px 0 0;padding:6px 8px;" />
                <button class="small-btn" onclick="resetAdminPassword('${escapeHtml(a.username)}')">Set</button>
                <button class="small-btn" onclick="deleteAdminAccount('${escapeHtml(a.username)}')">Delete</button></td></tr>`).join("")}</tbody></table>`
        : "<p>No admin accounts yet. Create the first owner above (with the Service Key), then sign in as them.</p>";
      showStatus(`${rows.length} admin account(s).`, "success");
    }

    async function createAdminAccount() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const username = document.getElementById("newAdminUsername").value.trim();
      const password = document.getElementById("newAdminPassword").value;
      if (!username || !password) {
        showStatus("Enter a username and password for the new account.", "error");
        return;
      }
      const created = await apiCall(ADMIN_ACCOUNTS_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({ username, password, role: document.getElementById("newAdminRole").value })
      });
      document.getElementById("newAdminUsername").value = "";
      document.getElementById("newAdminPassword").value = "";
      showStatus(`Created ${created.username} (${created.role}).`, "success");
      await loadAdminAccounts();
    }

    async function setAdminRole(username) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const role = document.getElementById("role-" + username).value;
      await apiCall(`${ADMIN_ACCOUNTS_URL}/${encodeURIComponent(username)}/role?role=${encodeURIComponent(role)}`,
        { method: "PUT", headers: { "X-Service-Key": serviceKey } });
      await loadAdminAccounts();
      showStatus(`${username} is now ${role}.`, "success");
    }

    async function setAdminActive(username, active) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`${ADMIN_ACCOUNTS_URL}/${encodeURIComponent(username)}/active?active=${active}`,
        { method: "PUT", headers: { "X-Service-Key": serviceKey } });
      await loadAdminAccounts();
      showStatus(`${username} ${active ? "enabled" : "disabled and signed out"}.`, "success");
    }

    async function deleteAdminAccount(username) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      if (!confirm(`Delete the account "${username}"? Its past actions stay in the audit log under that name. To just stop someone signing in, use Disable instead.`)) return;
      await apiCall(`${ADMIN_ACCOUNTS_URL}/${encodeURIComponent(username)}`, { method: "DELETE", headers: { "X-Service-Key": serviceKey } });
      await loadAdminAccounts();
      showStatus(`Deleted ${username}.`, "success");
    }

    // Last 90 days of sign-in attempts, newest first - wrong passwords and unknown names included, so guessing shows up.
    async function loadAdminLogins() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall(`${ADMIN_ORIGIN}/admin/logins?limit=100`, { headers: { "X-Service-Key": serviceKey } });
      const wrap = document.getElementById("adminLoginsWrap");
      const labels = { OK: "Signed in", WRONG_PASSWORD: "Wrong password", UNKNOWN_USER: "No such user", DISABLED: "Account disabled" };
      wrap.innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>When</th><th>Username</th><th>Result</th><th>From</th></tr></thead><tbody>${rows.map(r => `
            <tr><td>${new Date(r.at).toLocaleString()}</td><td>${escapeHtml(r.username || "")}</td>
            <td>${r.success ? "&#9989; " : "&#10060; "}${escapeHtml(labels[r.result] || r.result)}</td><td>${escapeHtml(r.remoteAddr || "")}</td></tr>`).join("")}</tbody></table>`
        : "<p>No sign-in attempts recorded yet.</p>";
      showStatus(`${rows.length} sign-in attempt(s).`, "success");
    }

    async function resetAdminPassword(username) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const input = document.getElementById("pw-" + username);
      if (!input.value) {
        showStatus("Type the new password first.", "error");
        return;
      }
      await apiCall(`${ADMIN_ACCOUNTS_URL}/${encodeURIComponent(username)}/password`, {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({ newPassword: input.value })
      });
      input.value = "";
      showStatus(`Password changed for ${username}; they have been signed out.`, "success");
    }

    // ---------- audit log ----------
    async function loadAuditLog() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const filter = document.getElementById("auditFilter").value.trim();
      const actor = document.getElementById("auditActor").value.trim();
      const rows = await apiCall(`/audit/recent?limit=200${filter ? "&pathContains=" + encodeURIComponent(filter) : ""}${actor ? "&actor=" + encodeURIComponent(actor) : ""}`,
        { headers: { "X-Service-Key": serviceKey } });
      const wrap = document.getElementById("auditTableWrap");
      wrap.innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>When</th><th>Who</th><th>Action</th><th>Result</th><th>From</th></tr></thead><tbody>${rows.map(r => `
            <tr><td>${new Date(r.timestamp).toLocaleString()}</td><td>${escapeHtml(r.actor || "(before named accounts)")}</td><td>${escapeHtml(r.method)} ${escapeHtml(r.path)}</td>
            <td>${r.status}</td><td>${escapeHtml(r.remoteAddr || "")}</td></tr>`).join("")}</tbody></table>`
        : "<p>No recorded actions yet.</p>";
      showStatus(`${rows.length} recorded action(s).`, "success");
    }

    // ---------- order feedback ----------
    async function loadFeedbackSummary() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const s = await apiCall(`/feedback/summary`, { headers: { "X-Service-Key": serviceKey } });
      const stars = n => "★".repeat(n) + "☆".repeat(5 - n);
      const counts = Object.entries(s.ratingCounts).reverse().map(([n, c]) => `${stars(Number(n))} ${c}`).join(" &middot; ");
      document.getElementById("feedbackWrap").innerHTML = s.totalFeedback === 0 ? "<p>No feedback yet.</p>" : `
        <p><strong>${s.totalFeedback}</strong> rating(s) &middot; overall <strong>${s.averageRating}</strong>/5
           ${s.averageDeliveryRating !== null ? "&middot; delivery <strong>" + s.averageDeliveryRating + "</strong>/5" : ""}</p>
        <p>${counts}</p>
        <table class="orders-table"><thead><tr><th>Order</th><th>Customer</th><th>Overall</th><th>Delivery</th><th>Comment</th><th>When</th></tr></thead><tbody>${s.recent.map(f => `
          <tr><td>#${f.orderId}</td><td>${f.customerPhno}</td><td>${stars(f.rating)}</td><td>${f.deliveryRating ? stars(f.deliveryRating) : "-"}</td>
          <td style="white-space:normal;">${escapeHtml(f.comment || "")}</td><td>${new Date(f.createdAt).toLocaleDateString()}</td></tr>`).join("")}</tbody></table>`;
      showStatus(`${s.totalFeedback} feedback entr${s.totalFeedback === 1 ? "y" : "ies"}.`, "success");
    }

    // ---------- coupon performance ----------
    async function loadCouponReport() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const report = await apiCall("/coupons/report", { headers: { "X-Service-Key": serviceKey } });
      if (!report || !Array.isArray(report.rows)) return;
      const money = n => "&#8377;" + Number(n).toFixed(2);
      document.getElementById("couponReportWrap").innerHTML = report.rows.length
        ? `<p>Discount given: <strong>${money(report.totalDiscountGiven)}</strong> &middot; Net revenue from coupon orders: <strong>${money(report.totalNetRevenue)}</strong></p>`
          + `<table class="orders-table"><thead><tr><th>Code</th><th>Off</th><th>Status</th><th>Orders</th><th>Cancelled / returned</th><th>Discount given</th><th>Net revenue</th><th>Avg order</th><th>Used</th></tr></thead><tbody>${report.rows.map(r => `
            <tr><td>${escapeHtml(r.code)}${r.unlisted ? " (single-use)" : ""}</td><td>${r.discountPercent == null ? "?" : r.discountPercent + "%"}</td>
            <td>${!r.known ? "deleted" : !r.active ? "inactive" : r.expired ? "expired" : "active"}</td>
            <td>${r.standingOrders}</td><td>${r.cancelledOrReturned}</td><td>${money(r.discountGiven)}</td><td>${money(r.netRevenue)}</td>
            <td>${r.averageOrderValue == null ? "-" : money(r.averageOrderValue)}</td><td>${r.redemptionCount}${r.maxRedemptions != null ? " / " + r.maxRedemptions : ""}</td></tr>`).join("")}</tbody></table>`
        : `<p>No coupons yet.</p>`;
      showStatus(`${report.rows.length} coupon(s).`, "success");
    }

    // ---------- refunds to settle ----------
    async function loadRefundFailures() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall("/refundfailures", { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      document.getElementById("refundFailuresWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>When</th><th>Order</th><th>Customer</th><th>What</th><th>Amount</th><th>Why</th><th></th></tr></thead><tbody>${rows.map(r => `
            <tr><td>${new Date(r.createdAt).toLocaleString()}</td><td>#${r.orderId}</td><td>${r.customerPhno}</td>
            <td>${escapeHtml(r.kind === "STORE_CREDIT" ? "Store credit" : "PhonePe refund")}${r.paymentTransactionId ? " (payment " + r.paymentTransactionId + ")" : ""}</td>
            <td>&#8377;${Number(r.amount).toFixed(2)}</td><td style="white-space:normal;">${escapeHtml(r.reason || "")}</td>
            <td><button onclick="retryRefundFailure(${r.id})">Retry refund</button> <button onclick="resolveRefundFailure(${r.id})">Resolve</button></td></tr>`).join("")}</tbody></table>`
        : `<p>Nothing to settle.</p>`;
      showStatus(`${rows.length} refund(s) to settle.`, "success");
    }

    async function retryRefundFailure(id) {
      if (!confirm("Pay this refund back to the customer now? It is sent once, even if you press the button again.")) return;
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const done = await apiCall(`/refundfailures/${id}/retry`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });
      if (done) showStatus("Refund sent and marked resolved.", "success");
      await loadRefundFailures();
    }

    async function resolveRefundFailure(id) {
      const note = prompt("How was it settled? (for example: refunded by hand from the PhonePe dashboard)");
      if (!note || !note.trim()) return;
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`/refundfailures/${id}/resolve?note=${encodeURIComponent(note.trim())}`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });
      await loadRefundFailures();
    }

    async function loadReturnRequests() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall("/cart/returns/pending", { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      document.getElementById("returnRequestsWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>Order</th><th>Customer</th><th>Total</th><th>Reason</th><th></th></tr></thead><tbody>${rows.map(r => `
            <tr><td>#${r.orderId}</td><td>${r.customerPhno}</td><td>&#8377;${Number(r.totalPrice).toFixed(2)}</td>
            <td style="white-space:normal;">${escapeHtml(r.returnRequestReason || "")}</td>
            <td><button onclick="decideReturn(${r.orderId}, true)">Approve</button> <button onclick="decideReturn(${r.orderId}, false)">Reject</button></td></tr>`).join("")}</tbody></table>`
        : `<p>No return requests waiting.</p>`;
      showStatus(`${rows.length} return request(s) waiting.`, "success");
    }

    async function loadReturnPickups() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall("/cart/returns/pickups", { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      document.getElementById("returnPickupsWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>Order</th><th>Customer</th><th>Total</th><th>Reason</th><th>Status</th><th></th></tr></thead><tbody>${rows.map(r => `
            <tr><td>#${r.orderId}</td><td>${r.customerPhno}</td><td>&#8377;${Number(r.totalPrice).toFixed(2)}</td>
            <td style="white-space:normal;">${escapeHtml(r.returnReason || "")}</td><td>${escapeHtml(r.returnPickupStatus)}</td>
            <td>${r.returnPickupStatus === "AWAITING_PICKUP"
              ? `<button onclick="stepReturnPickup(${r.orderId}, 'pickedup')">Mark picked up</button>`
              : `<button onclick="stepReturnPickup(${r.orderId}, 'received')">Mark received &amp; refund</button>`}</td></tr>`).join("")}</tbody></table>`
        : `<p>No returns on their way back.</p>`;
      const items = await apiCall("/cart/returns/item-pickups", { headers: { "X-Service-Key": serviceKey } });
      document.getElementById("itemPickupsWrap").innerHTML = Array.isArray(items) && items.length
        ? `<p><strong>Single items</strong></p><table class="orders-table"><thead><tr><th>Order</th><th>Customer</th><th>Item</th><th>Qty</th><th>Reason</th><th>Status</th><th></th></tr></thead><tbody>${items.map(r => `
            <tr><td>#${r.orderId}</td><td>${r.customerPhno}</td><td>#${r.productId}</td><td>${r.quantity}</td>
            <td style="white-space:normal;">${escapeHtml(r.reason || "")}</td><td>${escapeHtml(r.status)}</td>
            <td>${r.status === "AWAITING_PICKUP"
              ? `<button onclick="stepItemPickup(${r.id}, 'pickedup')">Mark picked up</button>`
              : `<button onclick="stepItemPickup(${r.id}, 'received')">Mark received &amp; refund</button>`}</td></tr>`).join("")}</tbody></table>` : "";
      showStatus(`${rows.length} return(s) and ${Array.isArray(items) ? items.length : 0} item(s) on their way back.`, "success");
    }

    async function loadExchanges() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall("/cart/exchanges/open", { headers: { "X-Service-Key": serviceKey } });
      document.getElementById("exchangesWrap").innerHTML = Array.isArray(rows) && rows.length
        ? `<table class="orders-table"><thead><tr><th>Order</th><th>Customer</th><th>Item</th><th>Qty</th><th>Swap for</th><th>Price diff</th><th>Reason</th><th>Status</th><th></th></tr></thead><tbody>${rows.map(r => `
            <tr><td>#${r.orderId}</td><td>${r.customerPhno}</td><td>#${r.productId}</td><td>${r.quantity}</td><td>#${r.replacementProductId}</td><td>${r.priceDifference ? (r.priceDifference > 0 ? "+" : "") + Number(r.priceDifference).toFixed(2) : "-"}</td>
            <td style="white-space:normal;">${escapeHtml(r.reason || "")}</td><td>${escapeHtml(r.status)}</td>
            <td>${r.status === "REQUESTED"
              ? `<button onclick="stepExchange(${r.id}, 'approve')">Approve</button> <button onclick="stepExchange(${r.id}, 'reject')">Reject</button>`
              : `<button onclick="stepExchange(${r.id}, 'received')">Mark received</button>`}</td></tr>`).join("")}</tbody></table>`
        : `<p>No exchanges waiting.</p>`;
    }

    let lastSlipIds = [];

    async function markSlipsPacked() {
      const serviceKey = requireServiceKey();
      if (!serviceKey || !lastSlipIds.length) return;
      if (!confirm(`Mark ${lastSlipIds.length} order(s) as packed? They will leave the pick list.`)) return;
      const params = new URLSearchParams();
      lastSlipIds.forEach(id => params.append("orderIds", id));
      const data = await apiCall(`/cart/packed?${params}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (data) { showStatus(`${data.marked} order(s) marked as packed.`, "success"); document.getElementById("psMarkPacked").disabled = true; }
    }

    // Ships the last printed batch, but only the orders that were ticked off as packed (the server skips the rest and says so).
    async function shipPackedSlips() {
      const serviceKey = requireServiceKey();
      if (!serviceKey || !lastSlipIds.length) return;
      if (!confirm(`Ship the packed orders among these ${lastSlipIds.length}? Customers are emailed.`)) return;
      const params = new URLSearchParams({ packedOnly: "true" });
      const carrier = document.getElementById("psCarrier").value.trim();
      if (carrier) params.set("carrier", carrier);
      const data = await apiCall(`/cart/bulk/ship?${params}`, {
        method: "POST", headers: { "X-Service-Key": serviceKey, "Content-Type": "application/json" }, body: JSON.stringify(lastSlipIds) });
      if (!data) return;
      const skipped = (data.results || []).filter(r => !r.success).map(r => `#${r.orderId}: ${r.message}`);
      showStatus(`${data.succeeded} shipped, ${data.failed} not shipped.${skipped.length ? " " + skipped.join("; ") : ""}`, data.failed ? "error" : "success");
      lastSlipIds = [];
      document.getElementById("psShipPacked").disabled = true;
      document.getElementById("psMarkPacked").disabled = true;
    }

    async function printPackingSlips() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const ids = document.getElementById("psOrderIds").value.split(",").map(x => x.trim()).filter(Boolean);
      if (ids.some(x => !/^\d+$/.test(x))) { showStatus("Order IDs must be numbers separated by commas.", "error"); return; }
      const params = new URLSearchParams();
      if (ids.length) ids.forEach(id => params.append("orderIds", id)); else params.set("status", document.getElementById("psStatus").value);
      if (document.getElementById("psIncludePacked").checked) params.set("includePacked", "true");
      const data = await apiCall(`/cart/packing-slips?${params}`, { headers: { "X-Service-Key": serviceKey } });
      if (!data || !Array.isArray(data.slips)) return;
      lastSlipIds = data.slips.map(s => s.orderId);
      document.getElementById("psMarkPacked").disabled = !lastSlipIds.length;
      document.getElementById("psShipPacked").disabled = !lastSlipIds.length;
      if (!data.slips.length) { showStatus("No orders to pack.", "success"); return; }
      const e = escapeHtml;
      const pick = `<section class="slip"><h2>Pick list &mdash; ${data.slips.length} order(s)</h2><table><tr><th>Product</th><th>Units</th><th>Orders</th></tr>${data.pickList.map(l =>
        `<tr><td>${e(l.name)} (#${l.productId})</td><td><strong>${l.quantity}</strong></td><td>${l.orders}</td></tr>`).join("")}</table></section>`;
      const slips = data.slips.map(s => `<section class="slip">
          <h2>Order #${s.orderId}</h2>
          <p><strong>${e(s.customerName || "")}</strong> &middot; ${e(String(s.customerPhno))}<br>${e(s.address || "No address on file")}</p>
          ${s.deliverySlot ? `<p>Delivery window: ${e(s.deliverySlot)}</p>` : ""}
          ${s.deliveryNote ? `<p>Instructions: ${e(s.deliveryNote)}</p>` : ""}
          ${s.giftWrap || s.giftMessage ? `<p class="gift">GIFT${s.giftWrap ? " &mdash; wrap it" : ""}${s.giftMessage ? `<br>Card: &ldquo;${e(s.giftMessage)}&rdquo;` : ""}</p>` : ""}
          <table><tr><th>Item</th><th>Qty</th></tr>${s.lines.map(l => `<tr><td>${e(l.name)} (#${l.productId})</td><td>${l.quantity}</td></tr>`).join("")}</table>
          ${s.amountToCollect > 0 ? `<p class="cash">Collect cash: &#8377;${Number(s.amountToCollect).toFixed(2)}</p>` : `<p>${e(s.paymentMethod)} &mdash; nothing to collect</p>`}
        </section>`).join("");
      const w = window.open("about:blank", "_blank");
      if (!w) { alert("New tab blocked. Please allow popups/tabs for this page."); return; }
      w.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>Packing slips</title><style>
        body{font-family:Arial,sans-serif;margin:0;padding:16px;color:#111}
        .slip{border:1px solid #999;padding:14px;margin-bottom:16px;page-break-after:always;break-inside:avoid}
        table{border-collapse:collapse;width:100%} th,td{border-bottom:1px solid #ccc;text-align:left;padding:4px 6px}
        .gift{border:2px dashed #333;padding:6px;font-weight:bold} .cash{font-size:18px;font-weight:bold}
        @media print{.slip{border:none}}</style></head><body>${pick}${slips}<script>window.onload=function(){window.print()}<\/script></body></html>`);
      w.document.close();
    }

    function dfOrderId() {
      const id = Number(document.getElementById("dfOrderId").value);
      if (!Number.isInteger(id) || id < 1) { showStatus("Enter an order ID.", "error"); return null; }
      return id;
    }

    async function showDeliveryAttempts() {
      const id = dfOrderId(); const serviceKey = requireServiceKey();
      if (!id || !serviceKey) return;
      const rows = await apiCall(`/cart/${id}/delivery-attempts`, { headers: { "X-Service-Key": serviceKey } });
      document.getElementById("dfWrap").innerHTML = Array.isArray(rows) && rows.length
        ? `<table class="orders-table"><thead><tr><th>Attempt</th><th>Reason</th><th>Note</th><th>When</th></tr></thead><tbody>${rows.map(a => `
            <tr><td>${a.attemptNo}</td><td>${escapeHtml(a.reason)}</td><td style="white-space:normal;">${escapeHtml(a.note || "")}</td><td>${escapeHtml(String(a.createdAt))}</td></tr>`).join("")}</tbody></table>`
        : `<p>No failed attempts on order #${id}.</p>`;
    }

    async function recordDeliveryFailure() {
      const id = dfOrderId(); const serviceKey = requireServiceKey();
      if (!id || !serviceKey) return;
      const params = new URLSearchParams({ reason: document.getElementById("dfReason").value, note: document.getElementById("dfNote").value });
      const data = await apiCall(`/cart/${id}/delivery-failed?${params}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (data && data.giveUpAdvised) showStatus(`Attempt ${data.attemptsSoFar} of ${data.maxAttempts} failed - time to send it back to stock.`, "error");
      await showDeliveryAttempts();
    }

    async function markOrderDelayed() {
      const id = dfOrderId(); const serviceKey = requireServiceKey();
      if (!id || !serviceKey) return;
      const date = document.getElementById("dfExpected").value;
      const reason = document.getElementById("dfNote").value.trim();
      if (!date || !reason) { showStatus("Pick the new expected date and write the reason in the note box.", "error"); return; }
      const params = new URLSearchParams({ expectedDate: date, reason });
      await apiCall(`/cart/${id}/delay?${params}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
    }

    async function returnUndelivered() {
      const id = dfOrderId(); const serviceKey = requireServiceKey();
      if (!id || !serviceKey) return;
      if (!confirm(`Cancel order #${id}, put it back in stock and refund it?`)) return;
      await apiCall(`/cart/${id}/delivery-failed/return-to-stock`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
    }

    async function stepExchange(id, step) {
      if (step === "received" && !confirm("Original item is back? It goes back into stock now.")) return;
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`/cart/exchanges/${id}/${step}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      await loadExchanges();
    }

    async function stepItemPickup(id, step) {
      if (step === "received" && !confirm("Item is back? This returns those units and pays the refund now.")) return;
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`/cart/returns/item-pickups/${id}/${step}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      await loadReturnPickups();
    }

    async function stepReturnPickup(orderId, step) {
      if (step === "received" && !confirm("Goods are back? This returns the order and pays the refund now.")) return;
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`/cart/${orderId}/return/${step}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      await loadReturnPickups();
    }

    async function decideReturn(orderId, approve) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      let query = "";
      if (!approve) {
        const note = prompt("Why is this return not approved? (the customer sees this)");
        if (note === null) return;
        query = "?note=" + encodeURIComponent(note.trim());
      }
      await apiCall(`/cart/${orderId}/return/${approve ? "approve" : "reject"}${query}`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });
      await loadReturnRequests();
    }

    // ---------- internal order notes ----------
    const NOTES_URL = "/ordernotes";

    async function loadOrderNotes() {
      const orderId = document.getElementById("noteOrderId").value.trim();
      if (!orderId) return showStatus("Enter an order id first.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const notes = await apiCall(`${NOTES_URL}?orderId=${encodeURIComponent(orderId)}`, { headers: { "X-Service-Key": serviceKey } });
      document.getElementById("notesWrap").innerHTML = notes.length
        ? `<table class="orders-table"><thead><tr><th>When</th><th>Note</th><th></th></tr></thead><tbody>${notes.map(n => `
            <tr><td>${new Date(n.createdAt).toLocaleString()}</td><td style="white-space:normal;">${escapeHtml(n.note)}</td>
            <td><button onclick="deleteOrderNote(${n.id})">Delete</button></td></tr>`).join("")}</tbody></table>`
        : `<p>No notes on order #${escapeHtml(orderId)}.</p>`;
      showStatus(`${notes.length} note(s) on order #${orderId}.`, "success");
    }

    async function addOrderNote() {
      const orderId = document.getElementById("noteOrderId").value.trim();
      const note = document.getElementById("noteText").value.trim();
      if (!orderId || !note) return showStatus("Enter an order id and a note.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`${NOTES_URL}?orderId=${encodeURIComponent(orderId)}&note=${encodeURIComponent(note)}`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });
      document.getElementById("noteText").value = "";
      await loadOrderNotes();
    }

    async function deleteOrderNote(noteId) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      if (!confirm("Delete this note?")) return;
      await apiCall(`${NOTES_URL}/${noteId}`, { method: "DELETE", headers: { "X-Service-Key": serviceKey } });
      await loadOrderNotes();
    }

    // ---------- customer insights ----------
    async function loadCustomerInsights() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const r = await apiCall(`${BASE_URL}/analytics/customers?top=10`, { headers: { "X-Service-Key": serviceKey } });
      const money = v => v === null ? "-" : "₹" + Number(v).toFixed(2);
      document.getElementById("insightsWrap").innerHTML = r.totalCustomers === 0 ? "<p>No orders yet.</p>" : `
        <p><strong>${r.totalCustomers}</strong> customers &middot; <strong>${r.newCustomers}</strong> new (one order) &middot;
           <strong>${r.repeatCustomers}</strong> repeat (${r.repeatRate}%)</p>
        <p>${r.totalOrders} orders &middot; net revenue ${money(r.netRevenue)} &middot; average order ${money(r.averageOrderValue)}
           &middot; ${r.averageOrdersPerCustomer} orders per customer</p>
        <table class="orders-table"><thead><tr><th>#</th><th>Customer</th><th>Phone</th><th>Orders</th><th>Net spend</th></tr></thead><tbody>${r.topCustomers.map((c, i) => `
          <tr><td>${i + 1}</td><td>${escapeHtml(c.name || "")}</td><td>${c.phno}</td><td>${c.orders}</td><td class="num">${money(c.netSpend)}</td></tr>`).join("")}</tbody></table>`;
      showStatus("Customer insights loaded.", "success");
    }

    async function downloadCustomersCsv() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const segment = document.getElementById("customerSegment").value;
      const dormantDays = Number(document.getElementById("dormantDays").value) || 90;
      try {
        const response = await fetch(`${BASE_URL}/analytics/customers/export?segment=${encodeURIComponent(segment)}&dormantDays=${dormantDays}`, { headers: { "X-Service-Key": serviceKey } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP Error: ${response.status}`);
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = `customers-${segment}.csv`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        showStatus(`customers-${segment}.csv downloaded.`, "success");
      } catch (error) {
        showStatus("CSV download failed: " + error.message, "error");
      }
    }

    // ---------- cancellation reasons ----------
    async function loadCancellationReport() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const r = await apiCall(`${BASE_URL}/analytics/cancellations`, { headers: { "X-Service-Key": serviceKey } });
      const rows = Object.entries(r.byReason).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]);
      document.getElementById("cancelReportWrap").innerHTML = r.totalCancelled === 0 ? "<p>No cancelled orders.</p>" : `
        <p><strong>${r.totalCancelled}</strong> cancelled order(s)</p>
        <table class="orders-table"><thead><tr><th>Reason</th><th>Orders</th><th>Share</th></tr></thead><tbody>${rows.map(([k, n]) => `
          <tr><td>${escapeHtml(k.replaceAll("_", " ").toLowerCase())}</td><td>${n}</td><td>${(100 * n / r.totalCancelled).toFixed(0)}%</td></tr>`).join("")}</tbody></table>
        ${r.recentNotes.length ? "<p>Latest notes:</p><ul>" + r.recentNotes.map(n => `<li>#${n.orderId}: ${escapeHtml(n.note)}</li>`).join("") + "</ul>" : ""}`;
      showStatus(`${r.totalCancelled} cancelled order(s).`, "success");
    }

    // ---------- returns & cancellations ----------
    function reasonsQuery() {
      const q = new URLSearchParams();
      const from = document.getElementById("reasonsFrom").value;
      const to = document.getElementById("reasonsTo").value;
      if (from) q.set("from", from);
      if (to) q.set("to", to);
      const text = q.toString();
      return text ? "?" + text : "";
    }

    async function loadReasonsReport() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const r = await apiCall(`${BASE_URL}/analytics/reasons${reasonsQuery()}`, { headers: { "X-Service-Key": serviceKey } });
      if (!r) return;
      const table = (head, rows) => rows.length
        ? `<table class="orders-table"><thead><tr>${head.map(h => `<th>${h}</th>`).join("")}</tr></thead><tbody>${rows.map(cells => `<tr>${cells.map(c => `<td>${escapeHtml(String(c))}</td>`).join("")}</tr>`).join("")}</tbody></table>`
        : "<p>None.</p>";
      document.getElementById("reasonsWrap").innerHTML = `
        <p>${escapeHtml(r.from)} to ${escapeHtml(r.to)}: <strong>${r.ordersPlaced}</strong> order(s) placed &middot;
          <strong>${r.ordersWithCancellations}</strong> with cancellations (${r.unitsCancelled} unit(s)) &middot;
          <strong>${r.ordersWithReturns}</strong> with returns (${r.unitsReturned} unit(s))</p>
        <h4>Why orders were cancelled</h4>${table(["Reason", "Units"], r.cancelReasons.map(c => [c.reason, c.units]))}
        <h4>Why items were returned</h4>${table(["Reason", "Units"], r.returnReasons.map(c => [c.reason, c.units]))}
        <h4>By week placed</h4>${table(["Week starting", "Orders", "Cancelled units", "Returned units"], r.weeks.map(w => [w.weekStart, w.ordersPlaced, w.unitsCancelled, w.unitsReturned]))}
        <h4>Products sent back most</h4>${table(["Product", "Ordered", "Cancelled", "Returned", "Sent back"], r.products.map(p => [p.productId + " " + p.productName, p.unitsOrdered, p.unitsCancelled, p.unitsReturned, p.sentBackPercent + "%"]))}`;
      showStatus("Returns & cancellations report loaded.", "success");
    }

    async function downloadReasonsCsv() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      try {
        const response = await fetch(`${BASE_URL}/analytics/reasons/export${reasonsQuery()}`, { headers: { "X-Service-Key": serviceKey } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP Error: ${response.status}`);
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "returns-and-cancellations.csv";
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        showStatus("returns-and-cancellations.csv downloaded.", "success");
      } catch (error) {
        showStatus("CSV download failed: " + error.message, "error");
      }
    }

    // ---------- today ----------
    // One row per kind of thing waiting on someone. "Open" scrolls to that row's card and loads it (pre-filtered where the
    // card has a filter). A row that could not be worked out says so instead of showing 0.
    const ATTENTION_TARGETS = {
      supportTickets: { card: "card-support", open: () => { document.getElementById("supportStatus").value = "OPEN"; return loadSupportQueue(); } },
      unansweredQuestions: { card: "card-questions", open: () => loadPendingQuestions() },
      priceMatches: { card: "card-pricematch", open: () => loadPriceMatches() },
      flaggedReviews: { card: "card-moderation", open: () => loadFlaggedReviews() },
      lowStock: { card: "card-lowstock", open: () => getLowStockReport() },
      stuckPayments: { card: "card-orders", open: () => { document.getElementById("ordersStatus").value = "PENDING_PAYMENT"; return loadOrdersTable(); } },
      unpacked: { card: "card-packingslips", open: () => {} },
      failedRefunds: { card: "card-refundfailures", open: () => loadRefundFailures() },
      failedNotifications: { card: "card-notifications", open: (item) => {
        if (item.orderIds && item.orderIds.length) { document.getElementById("notificationsOrderId").value = item.orderIds[0]; return viewNotifications(); }
      } }
    };
    let attentionItems = [];

    function renderAttention(data) {
      attentionItems = data.items;
      const escape = v => String(v ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
      document.getElementById("attentionWrap").innerHTML =
        `<p><strong>${data.total === 0 ? "All clear" : data.total + " item(s) waiting"}</strong> <span style="opacity:.7;">as of ${new Date(data.generatedAt).toLocaleTimeString()}</span></p>`
        + `<table style="width:100%; border-collapse:collapse; font-size:14px;">`
        + data.items.map((item, i) => {
          const shown = item.unavailable ? "?" : (item.count + (item.more ? "+" : ""));
          const waiting = item.unavailable || item.count > 0;
          const ids = item.orderIds && item.orderIds.length ? ` <span style="opacity:.7;">(orders ${item.orderIds.map(escape).join(", ")})</span>` : "";
          return `<tr style="border-top:1px solid rgba(128,128,128,.25);">`
            + `<td style="padding:8px; width:56px; font-size:20px; font-weight:700; ${item.unavailable ? "color:#b45309;" : waiting ? "color:#b45309;" : "opacity:.45;"}">${shown}</td>`
            + `<td style="padding:8px;">${escape(item.title)}<br><span style="opacity:.7; font-size:12px;">${escape(item.detail)}${ids}</span></td>`
            + `<td style="padding:8px; text-align:right;"><button style="width:auto;" onclick="openAttention(${i})">Open</button></td></tr>`;
        }).join("")
        + `</table>`;
    }

    async function loadAttention() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`${BASE_URL}/attention`, { headers: { "X-Service-Key": serviceKey } });
      if (!data || !data.items) return;
      renderAttention(data);
      showStatus(data.total === 0 ? "Nothing needs attention." : `${data.total} item(s) need attention.`, "success");
    }

    async function openAttention(index) {
      const item = attentionItems[index];
      const target = item && ATTENTION_TARGETS[item.key];
      if (!target) return;
      document.getElementById(target.card).scrollIntoView({ behavior: "smooth", block: "start" });
      await target.open(item);
    }

    async function loadToday() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const t = await apiCall(`${BASE_URL}/analytics/today?zone=${encodeURIComponent(zone)}`, { headers: { "X-Service-Key": serviceKey } });
      const ids = list => list.length ? list.map(id => "#" + id).join(", ") : "none";
      const a = t.attention;
      document.getElementById("todayWrap").innerHTML = `
        <p><strong>${t.date}</strong> (${escapeHtml(t.zone)}): <strong>${t.ordersPlaced}</strong> order(s) placed &middot;
           net revenue <strong>₹${t.netRevenue.toFixed(2)}</strong> &middot; ${t.delivered} delivered &middot; ${t.cancelled} cancelled</p>
        <p><strong>Needs attention</strong></p>
        <ul>
          <li>Waiting over ${a.staleHours}h to ship: ${ids(a.unshipped)}</li>
          <li>Delivered cash orders not marked paid: ${ids(a.cashDeliveredUnpaid)}</li>
          <li>Placed orders: ${a.unpacked} not yet packed, ${a.packedWaiting} packed and waiting to ship</li>
          <li>UPI payments still pending: ${a.pendingPayments}</li>
          <li>Unanswered customer questions: ${a.unansweredQuestions}</li>
        </ul>`;
      showStatus("Today's snapshot loaded.", "success");
    }

    async function loadCodReport() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const r = await apiCall(`${BASE_URL}/analytics/cod?zone=${encodeURIComponent(zone)}`, { headers: { "X-Service-Key": serviceKey } });
      if (!r || !r.collectedByDay) return;
      const money = n => "&#8377;" + Number(n).toFixed(2);
      document.getElementById("codReportWrap").innerHTML = `
        <p>Collected today <strong>${money(r.collectedToday)}</strong> &middot; still owed <strong>${money(r.outstandingTotal)}</strong> on ${r.outstandingOrders} order(s)
           &middot; overdue (delivered over ${r.overdueAfterDays} day(s) ago) <strong>${money(r.overdueTotal)}</strong></p>
        <table class="orders-table"><thead><tr><th>Day</th><th>Collected</th><th>Orders</th></tr></thead><tbody>${
          r.collectedByDay.slice().reverse().map(d => `<tr><td>${d.date}</td><td>${money(d.collected)}</td><td>${d.orders}</td></tr>`).join("")}</tbody></table>
        ${r.overdue.length ? `<p><strong>Overdue cash</strong></p><table class="orders-table"><thead><tr><th>Order</th><th>Customer</th><th>Due</th><th>Delivered</th><th>Days</th></tr></thead><tbody>${
          r.overdue.map(o => `<tr><td>#${o.orderId}</td><td>${o.customerPhno}</td><td>${money(o.amountDue)}</td><td>${new Date(o.deliveredAt).toLocaleString()}</td><td>${o.daysSinceDelivery}</td></tr>`).join("")}</tbody></table>` : `<p>No overdue cash.</p>`}`;
      showStatus("Cash collection loaded.", "success");
    }

    async function loadMissedSearches() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall(`${BASE_URL}/analytics/missed-searches`, { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      document.getElementById("missedSearchWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>Search</th><th>Times</th><th>Last searched</th><th>Action</th></tr></thead><tbody>${rows.map(r => `
            <tr><td>${escapeHtml(r.term)}</td><td>${r.times}</td><td>${r.lastSearchedAt ? new Date(r.lastSearchedAt).toLocaleString() : "-"}</td>
              <td><a href="${location.protocol}//${location.hostname}:8082/product.html?newProduct=${encodeURIComponent(r.term)}" target="_blank" rel="noopener noreferrer">Add product</a>
                <button onclick="dismissMissedSearch(${JSON.stringify(r.term).replace(/"/g, "&quot;")})">Done</button></td></tr>`).join("")}</tbody></table>`
        : "<p>No missed searches yet - every search so far found something.</p>";
      showStatus(`${rows.length} missed search term(s) loaded.`, "success");
    }

    async function loadInventoryValue() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const v = await apiCall(`${BASE_URL}/analytics/inventory-value`, { headers: { "X-Service-Key": serviceKey } });
      if (!v || !v.categories) return;
      document.getElementById("inventoryValueWrap").innerHTML = `
        <p><strong>₹${v.totalValue.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</strong>
           across ${v.units} unit(s) in ${v.products} product(s)</p>
        <table class="orders-table"><thead><tr><th>Category</th><th>Products</th><th>Units</th><th>Value</th></tr></thead><tbody>
        ${v.categories.map(c => `<tr><td>${escapeHtml(c.category)}</td><td>${c.products}</td><td>${c.units}</td>
          <td>₹${c.value.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td></tr>`).join("")}
        </tbody></table>`;
      showStatus("Inventory value loaded.", "success");
    }

    async function dismissMissedSearch(term) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`${BASE_URL}/analytics/missed-searches?term=${encodeURIComponent(term)}`, { method: "DELETE", headers: { "X-Service-Key": serviceKey } });
      await loadMissedSearches();
    }

    async function loadSlowMovers() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const days = parseInt(document.getElementById("slowDays").value, 10) || 30;
      const rows = await apiCall(`${BASE_URL}/analytics/slow-movers?days=${days}`, { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      document.getElementById("slowMoversWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>Product</th><th>Category</th><th>In stock</th><th>Value tied up</th><th>Last sold</th><th>Action</th></tr></thead><tbody>${rows.map(r => `
            <tr><td>${escapeHtml(r.productName)} (#${r.productId})</td><td>${escapeHtml(r.productCategory || "")}</td><td>${r.productStock}</td>
              <td>₹${r.stockValue.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td>
              <td>${r.lastSoldAt ? `${r.daysSinceLastSale} day(s) ago` : "never"}</td>
              <td><button onclick="markDownSlowMover(${r.productId})">Mark down...</button></td></tr>`).join("")}</tbody></table>`
        : `<p>Nothing is stuck - every stocked product sold within the last ${days} day(s).</p>`;
      showStatus(`${rows.length} slow mover(s) loaded.`, "success");
    }

    async function loadReorderSuggestions() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const days = parseInt(document.getElementById("reorderDays").value, 10) || 30;
      const cover = parseInt(document.getElementById("reorderCover").value, 10) || 30;
      const rows = await apiCall(`${BASE_URL}/analytics/reorder-suggestions?days=${days}&cover=${cover}`, { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      document.getElementById("reorderSuggestionsWrap").innerHTML = rows.length
        ? `<table class="orders-table"><thead><tr><th>Product</th><th>In stock</th><th>Sold (last ${days}d)</th><th>Per day</th><th>Days left</th><th>Buy about</th></tr></thead><tbody>${rows.map(r => `
            <tr><td>${escapeHtml(r.productName)} (#${r.productId})</td><td>${r.productStock}</td><td>${r.unitsSold}</td>
              <td>${r.dailyRate}</td><td>${r.daysOfStock}</td><td><strong>${r.suggestedQuantity}</strong></td></tr>`).join("")}</tbody></table>`
        : `<p>Nothing is due - everything that sold lately has at least ${cover} day(s) of stock.</p>`;
      showStatus(`${rows.length} product(s) to reorder.`, "success");
    }

    async function markDownSlowMover(productId) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const answer = prompt("Mark this product down by what percent of its current price? (1-70)\nCustomers who wishlisted it get a price-drop alert.", "10");
      if (answer === null) return;
      const percent = parseInt(answer, 10);
      if (!Number.isInteger(percent) || percent < 1 || percent > 70) { showStatus("Enter a whole percent from 1 to 70.", "error"); return; }
      const r = await apiCall(`${BASE_URL}/analytics/slow-movers/markdown?productId=${productId}&percent=${percent}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (r) showStatus(`${r.productName}: \u20b9${r.oldPrice.toFixed(2)} \u2192 \u20b9${r.newPrice.toFixed(2)} (${r.percent}% off).`, "success");
    }

    // ---------- review moderation ----------
    // ProductService's queue only holds flagged-but-NOT-hidden reviews, so a review leaves it the moment it is
    // hidden. Keep it on screen (struck through, with Undo) for this page view so a mistaken hide can be reversed.
    let moderationRows = [];

    function renderModerationTable() {
      const wrap = document.getElementById("modTableWrap");
      if (!moderationRows.length) {
        wrap.innerHTML = '<p>No flagged reviews waiting - nothing to moderate.</p>';
        return;
      }
      wrap.innerHTML = `<table class="orders-table"><thead><tr>
        <th>Review</th><th>Product</th><th>Reviewer</th><th>Rating</th><th>Comment</th><th>Photo</th><th>Reported because</th><th>Action</th>
      </tr></thead><tbody>${moderationRows.map((r, i) => `
        <tr style="${r.hidden ? "opacity:0.55;" : ""}">
          <td>#${r.reviewId}</td>
          <td>${r.productId}</td>
          <td>${escapeHtml(r.reviewerName || "")} (${r.reviewerPhno})</td>
          <td>${"★".repeat(r.rating)}${"☆".repeat(Math.max(0, 5 - r.rating))}</td>
          <td style="white-space:normal; min-width:200px; ${r.hidden ? "text-decoration:line-through;" : ""}">${escapeHtml(r.comment || "")}</td>
          <td>${r.photoUrl && /^https?:\/\//i.test(r.photoUrl) ? `<a href="${escapeHtml(r.photoUrl)}" target="_blank" rel="noopener noreferrer">View photo</a>` : "-"}</td>
          <td style="white-space:normal;">${escapeHtml(r.flagReason || "(no reason given)")}</td>
          <td>${r.hidden
            ? `<button class="blue" onclick="moderateReview(${i}, 'unhide')">Undo (show again)</button>`
            : `<button onclick="moderateReview(${i}, 'hide')">Hide</button>`}</td>
        </tr>`).join("")}</tbody></table>`;
    }

    async function loadFlaggedReviews() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall(`${BASE_URL}/reviews/flagged?size=100`, { headers: { "X-Service-Key": serviceKey } });
      moderationRows = rows || [];
      renderModerationTable();
      showStatus(`${moderationRows.length} flagged review(s) waiting.`, "success");
    }

    // Preview (dryRun=true) first; the delete button asks again with the count from a fresh preview.
    async function tidyReviewPhotos(apply) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      if (apply) {
        const preview = await apiCall(`${BASE_URL}/reviews/photo/tidy?dryRun=true`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
        if (!preview) return;
        if (preview.orphaned === 0) {
          document.getElementById("photoTidyOut").textContent = "No unused photos - nothing to delete.";
          return showStatus("No unused photos.", "success");
        }
        if (!confirm(`Delete ${preview.orphaned} unused photo(s)? This cannot be undone.`)) return;
      }
      const data = await apiCall(`${BASE_URL}/reviews/photo/tidy?dryRun=${!apply}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (!data) return;
      const kb = Math.round(data.orphanedBytes / 1024);
      document.getElementById("photoTidyOut").innerHTML =
        `<p><strong>${data.dryRun ? "Preview (nothing deleted)" : "Deleted"}:</strong> ${data.orphaned} unused photo(s), ${kb} KB. `
        + `${data.inUse} in use, ${data.tooRecent} too recent to touch, ${data.total} photo(s) in total.</p>`;
      showStatus(data.dryRun ? `${data.orphaned} unused photo(s) found.` : `${data.orphaned} unused photo(s) deleted.`, "success");
    }

    async function moderateReview(index, action) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const row = moderationRows[index];
      await apiCall(`${BASE_URL}/reviews/${row.reviewId}/${action}?productId=${row.productId}`, {
        method: "PUT",
        headers: { "X-Service-Key": serviceKey }
      });
      row.hidden = action === "hide";
      renderModerationTable();
      showStatus(`Review #${row.reviewId} ${row.hidden ? "hidden from" : "shown on"} the storefront.`, "success");
    }

    async function checkHealth() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`${BASE_URL}/health`, { headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(data)) return;
      document.getElementById("healthBoard").innerHTML = healthBoardHtml(data)
        + `<div style="font-size:12px; opacity:.7; margin-top:6px;">Checked ${new Date().toLocaleTimeString()}</div>`;
    }

    // Uptime strip: one square per hour for the last 24 hours, per service - green all probes up, amber some down, red
    // none up, grey no probes that hour (nobody was looking, which is not the same as down). Samples are taken by the
    // background job when HEALTH_HISTORY_ENABLED=true, or by "Record a sample now".
    async function loadHealthHistory() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`${BASE_URL}/health/history?hours=24`, { headers: { "X-Service-Key": serviceKey } });
      document.getElementById("healthHistory").innerHTML = healthHistoryHtml(data);
    }

    async function recordHealthSample() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const rows = await apiCall(`${BASE_URL}/health/history/record`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (!Array.isArray(rows)) return;
      showStatus(`Sample recorded: ${rows.filter(r => r.status === "UP").length} of ${rows.length} up.`, "success");
      loadHealthHistory();
    }

    function healthHistoryHtml(history) {
      if (!history || !history.services || !history.services.length) {
        return `<p style="font-size:13px; opacity:.8;">No samples in the last ${history ? history.hours : 24} hours yet. Click "Record a sample now", or start OrderService with HEALTH_HISTORY_ENABLED=true to sample every few minutes.</p>`;
      }
      const colour = b => b.samples === 0 ? "#cbd5e1" : b.up === b.samples ? "#16a34a" : b.up === 0 ? "#dc2626" : "#f59e0b";
      const tip = b => {
        const hour = new Date(b.hourStart).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
        return b.samples === 0 ? `${hour}: no samples` : `${hour}: ${b.up} of ${b.samples} probes up`;
      };
      return `<div style="font-size:13px; margin-bottom:4px;"><strong>Last ${history.hours} hours</strong> &middot; ${history.totalSamples} sample(s) &middot; older than ${history.retentionDays} days is deleted</div>`
        + history.services.map(s => `<div style="display:flex; align-items:center; gap:8px; padding:3px 0; flex-wrap:wrap;">
            <strong style="min-width:140px;">${escapeHtml(s.name)}</strong>
            <span class="health-strip" style="display:inline-flex; gap:2px;">${s.buckets.map(b => `<span title="${escapeHtml(tip(b))}" style="width:9px; height:18px; border-radius:2px; background:${colour(b)}; display:inline-block;"></span>`).join("")}</span>
            <span style="font-size:13px;">${s.uptimePercent === null ? "no data" : s.uptimePercent.toFixed(1) + "% up"}</span>
            <span style="opacity:.7; font-size:12px;">${s.lastStatus} at ${new Date(s.lastCheckedAt).toLocaleTimeString()}</span>
          </div>`).join("");
    }

    function healthBoardHtml(rows) {
      return rows.map(r => {
        const up = r.status === "UP";
        return `<div style="display:flex; align-items:center; gap:8px; padding:4px 0;">
          <span style="width:10px; height:10px; border-radius:50%; background:${up ? "#16a34a" : "#dc2626"}; display:inline-block;"></span>
          <strong style="min-width:140px;">${escapeHtml(r.name)}</strong>
          <span>${up ? "UP" : "DOWN"}</span>
          <span style="opacity:.7; font-size:13px;">${r.responseMillis} ms${r.detail ? " - " + escapeHtml(r.detail) : ""}</span>
        </div>`;
      }).join("");
    }

    const BUNDLES_URL = "/bundles";

    async function createBundle() {
      const name = document.getElementById("bundleName").value.trim();
      const price = document.getElementById("bundlePrice").value.trim();
      const items = document.getElementById("bundleItems").value.trim();
      if (!name || !price || !items) return showStatus("Name, price and items are required.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const params = new URLSearchParams({ name, price, items });
      const data = await apiCall(`${BUNDLES_URL}?${params}`, { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (data) {
        showStatus(`Bundle ${data.id} created.`, "success");
        openResultTab("Bundle", data);
      }
    }

    async function listBundles() {
      const data = await apiCall(BUNDLES_URL, {});
      if (data) openResultTab("Active Bundles", data);
    }

    async function retireBundle() {
      const id = document.getElementById("bundleRetireId").value.trim();
      if (!id) return showStatus("Please enter a bundle ID.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`${BUNDLES_URL}/${encodeURIComponent(id)}`, { method: "DELETE", headers: { "X-Service-Key": serviceKey } });
      showStatus(`Bundle ${id} retired.`, "success");
    }

    async function getSalesAnalytics() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`${BASE_URL}/analytics`, {
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus("Sales analytics loaded successfully. Result opened in new tab.", "success");
      openResultTab("Sales Analytics", data);
    }

    function ordersQuery() {
      const q = new URLSearchParams();
      [["status", "ordersStatus"], ["paymentMethod", "ordersMethod"], ["phno", "ordersPhno"], ["from", "ordersFrom"], ["to", "ordersTo"]]
        .forEach(([name, id]) => {
          const value = document.getElementById(id).value.trim();
          if (value) q.set(name, value);
        });
      return q.toString();
    }

    function escapeCell(value) {
      return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
    }

    // Table state: which page is showing and how it is sorted (the server does both).
    let ordersPage = 0;
    let ordersSort = "orderId";
    let ordersDir = "desc";
    const ORDERS_PAGE_SIZE = 25;

    function sortOrdersBy(column) {
      if (ordersSort === column) {
        ordersDir = ordersDir === "desc" ? "asc" : "desc";
      } else {
        ordersSort = column;
        ordersDir = column === "customerName" || column === "status" ? "asc" : "desc";
      }
      ordersPage = 0;
      loadOrdersTable(true);
    }

    function goToOrdersPage(page) {
      ordersPage = page;
      loadOrdersTable(true);
    }

    // keepPage is true when paging/sorting; a fresh "Load Orders" click (filters may have changed) goes back to page 1.
    async function loadOrdersTable(keepPage) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      if (keepPage !== true) ordersPage = 0;

      const query = `${ordersQuery()}&sort=${ordersSort}&dir=${ordersDir}&page=${ordersPage}&size=${ORDERS_PAGE_SIZE}`;
      const result = await apiCall(`${BASE_URL}/orders/page?${query}`, {
        headers: { "X-Service-Key": serviceKey }
      });
      // A shrinking result (an order cancelled elsewhere, a narrower filter) can leave the page past the end.
      if (result.rows.length === 0 && result.totalElements > 0 && ordersPage > 0) {
        ordersPage = result.totalPages - 1;
        return loadOrdersTable(true);
      }
      const rows = result.rows;
      const wrap = document.getElementById("ordersTableWrap");
      if (!rows.length) {
        wrap.innerHTML = `<p>No orders match these filters.</p>`;
      } else {
        const arrow = col => ordersSort === col ? (ordersDir === "asc" ? " &#9650;" : " &#9660;") : "";
        const th = (col, label) => `<th style="cursor:pointer" onclick="sortOrdersBy('${col}')" title="Sort by ${label}">${label}${arrow(col)}</th>`;
        const pageTotal = rows.reduce((sum, r) => sum + r.totalPrice, 0);
        const pager = result.totalPages > 1 ? `<div style="margin-top:10px;display:flex;gap:10px;align-items:center;">
            <button ${result.page === 0 ? "disabled" : ""} onclick="goToOrdersPage(${result.page - 1})">Previous</button>
            <span>Page ${result.page + 1} of ${result.totalPages}</span>
            <button ${result.page >= result.totalPages - 1 ? "disabled" : ""} onclick="goToOrdersPage(${result.page + 1})">Next</button></div>` : "";
        wrap.innerHTML = `<table class="orders-table"><thead><tr>
            <th><input type="checkbox" onclick="toggleAllOrderChecks(this.checked)" title="Select all" /></th>${th("orderId", "Order")}${th("placedAt", "Placed")}${th("customerName", "Customer")}<th>Phone</th>${th("status", "Status")}<th>Payment</th><th>Paid</th><th>Items</th><th>Coupon</th><th>Note</th>${th("totalPrice", "Total")}
          </tr></thead><tbody>${rows.map(r => `<tr>
            <td><input type="checkbox" class="orderCheck" value="${r.orderId}" /></td><td>#${r.orderId}</td><td>${r.placedAt ? new Date(r.placedAt).toLocaleString() : "-"}</td>
            <td>${escapeCell(r.customerName)}</td><td>${r.customerPhno}</td><td>${escapeCell(r.status)}</td>
            <td>${escapeCell(r.paymentMethod)}</td><td>${r.paid ? "Yes" : "No"}</td><td>${escapeCell(r.items)}</td>
            <td>${escapeCell(r.couponCode || "")}</td><td>${escapeCell(r.deliveryNote || "")}</td><td class="num">₹${r.totalPrice.toFixed(2)}</td></tr>`).join("")}
          </tbody><tfoot><tr><td colspan="11"><strong>${result.totalElements} order(s) match &middot; showing ${rows.length} &middot; all matching: ₹${result.matchingTotal.toFixed(2)}</strong></td><td class="num"><strong>₹${pageTotal.toFixed(2)}</strong></td></tr></tfoot></table>${pager}`;
      }
      showStatus(`${result.totalElements} order(s) match; showing ${rows.length}.`, "success");
    }

    function toggleAllOrderChecks(checked) {
      document.querySelectorAll(".orderCheck").forEach(cb => { cb.checked = checked; });
    }

    // Ships/delivers every ticked order via the bulk endpoints, then reloads the table. Orders that can't move
    // (wrong status, etc.) are listed in the result tab; the others still go through.
    async function bulkAction(action) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const ids = [...document.querySelectorAll(".orderCheck:checked")].map(cb => Number(cb.value));
      if (!ids.length) return showStatus("Tick at least one order in the table first.", "error");
      if (!confirm(`${action === "ship" ? "Ship" : "Deliver"} ${ids.length} order(s)? Customers will be emailed.`)) return;
      const result = await apiCall(`${BASE_URL}/bulk/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify(ids)
      });
      await loadOrdersTable(true);
      showStatus(`${result.succeeded} order(s) updated, ${result.failed} skipped.`, result.failed ? "error" : "success");
      if (result.failed) openResultTab("Bulk " + action, result);
    }

    // ---------- gift cards ----------
    const GIFT_CARDS_URL = "/giftcards";

    async function mintGiftCards() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const amount = Number(document.getElementById("gcAmount").value);
      const count = Number(document.getElementById("gcCount").value);
      const days = document.getElementById("gcDays").value;
      if (!amount || !count) {
        showStatus("Enter how many cards and the value of each.", "error");
        return;
      }
      const result = await apiCall(`${GIFT_CARDS_URL}/mint`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({ count, amount, note: document.getElementById("gcNote").value, expiresInDays: days === "" ? null : Number(days) })
      });
      const box = document.getElementById("gcMinted");
      box.value = result.cards.map(c => `${c.code}   ₹${c.amount}   ${c.expiresAt ? "expires " + new Date(c.expiresAt).toLocaleDateString() : "no expiry"}`).join("\n");
      box.hidden = false;
      box.focus();
      box.select();
      await loadGiftCards();
      showStatus(`Minted ${result.cards.length} card(s) worth ₹${result.totalValue}. Copy the codes now - they are not shown again.`, "success");
    }

    async function loadGiftCards() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const status = document.getElementById("gcStatus").value;
      const r = await apiCall(`${GIFT_CARDS_URL}?limit=200${status ? "&status=" + status : ""}`, { headers: { "X-Service-Key": serviceKey } });
      document.getElementById("gcSummary").innerHTML =
        `<div><b>${r.outstandingCards}</b><span>cards still redeemable</span></div><div><b>₹${Number(r.outstandingValue).toFixed(2)}</b><span>owed in goods</span></div>`;
      document.getElementById("gcTable").innerHTML = r.cards.length
        ? `<table class="orders-table"><thead><tr><th>Card</th><th class="num">Value</th><th>Status</th><th>Made</th><th>Note</th><th>Expires</th><th>Redeemed by</th><th></th></tr></thead><tbody>${r.cards.map(c => `
            <tr><td>GC-····-${escapeHtml(c.last4)}</td><td class="num">₹${Number(c.amount).toFixed(2)}</td><td>${escapeHtml(c.status)}</td>
            <td>${new Date(c.createdAt).toLocaleDateString()} by ${escapeHtml(c.createdBy || "")}</td><td>${escapeHtml(c.note || "")}</td>
            <td>${c.expiresAt ? new Date(c.expiresAt).toLocaleDateString() : "never"}</td>
            <td>${c.redeemedBy ? escapeHtml(String(c.redeemedBy)) + " &middot; " + new Date(c.redeemedAt).toLocaleDateString() : ""}</td>
            <td>${c.status === "ACTIVE" ? `<button class="small-btn" onclick="voidGiftCard(${c.id})">Void</button>` : ""}</td></tr>`).join("")}</tbody></table>`
        : "<p>No gift cards" + (status ? " with that status" : " yet") + ".</p>";
    }

    async function voidGiftCard(id) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      if (!confirm("Void this gift card? Its code stops working.")) return;
      await apiCall(`${GIFT_CARDS_URL}/${id}/void`, { method: "PUT", headers: { "X-Service-Key": serviceKey } });
      await loadGiftCards();
      showStatus("Gift card voided.", "success");
    }

    // ---------- GST report ----------
    function gstQuery() {
      const from = document.getElementById("gstFrom").value;
      const to = document.getElementById("gstTo").value;
      return new URLSearchParams({ ...(from ? { from } : {}), ...(to ? { to } : {}) }).toString();
    }

    async function loadGstReport() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const r = await apiCall(`/gst/report?${gstQuery()}`, { headers: { "X-Service-Key": serviceKey } });
      const money = n => "₹" + Number(n).toFixed(2);
      document.getElementById("gstSummary").innerHTML =
        `<div><b>${r.invoices}</b><span>invoices</span></div><div><b>${r.creditNotes}</b><span>credit notes</span></div>`
        + `<div><b>${money(r.invoiceTotals.totalTax)}</b><span>GST on invoices</span></div>`
        + `<div><b>${money(r.creditNoteTotals.totalTax)}</b><span>reversed by credit notes</span></div>`
        + `<div><b>${money(r.net.totalTax)}</b><span>net GST</span></div>`;
      document.getElementById("gstTable").innerHTML = r.rows.length
        ? `<table class="orders-table"><thead><tr><th>Month</th><th>Type</th><th>State</th><th>GST %</th><th>Docs</th><th class="num">Taxable</th><th class="num">CGST</th><th class="num">SGST</th><th class="num">IGST</th><th class="num">Value</th></tr></thead><tbody>${r.rows.map(x => `
            <tr><td>${escapeHtml(x.month)}</td><td>${x.type === "CREDIT_NOTE" ? "Credit note" : "Invoice"}</td><td>${escapeHtml(x.placeOfSupply || "-")}</td><td>${x.gstRate}</td><td>${x.documents}</td>
            <td class="num">${money(x.taxableValue)}</td><td class="num">${money(x.cgst)}</td><td class="num">${money(x.sgst)}</td><td class="num">${money(x.igst)}</td><td class="num">${money(x.value)}</td></tr>`).join("")}</tbody></table>`
        : "<p>No invoices or credit notes in this range.</p>";
      showStatus(`GST report for ${r.from} to ${r.to}.`, "success");
    }

    async function downloadGstCsv() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      try {
        const response = await fetch(`/gst/report/export?${gstQuery()}`, { headers: { "X-Service-Key": serviceKey } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP Error: ${response.status}`);
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "gst-report.csv";
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        showStatus("gst-report.csv downloaded.", "success");
      } catch (error) {
        showStatus("CSV download failed: " + error.message, "error");
      }
    }

    async function downloadGstPdf() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      try {
        const response = await fetch(`/gst/report/pdf?${gstQuery()}`, { headers: { "X-Service-Key": serviceKey } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP Error: ${response.status}`);
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "gst-report.pdf";
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        showStatus("gst-report.pdf downloaded.", "success");
      } catch (error) {
        showStatus("PDF download failed: " + error.message, "error");
      }
    }

    // ---------- sales breakdown ----------
    let sbSort = "revenue";
    let sbDir = "desc";

    (function initSalesBreakdownDates() {
      const to = new Date();
      const from = new Date();
      from.setDate(from.getDate() - 29);
      document.getElementById("sbTo").value = localIsoDate(to);
      document.getElementById("sbFrom").value = localIsoDate(from);
    })();

    function salesBreakdownQuery() {
      const q = new URLSearchParams();
      const from = document.getElementById("sbFrom").value;
      const to = document.getElementById("sbTo").value;
      if (from) q.set("from", from);
      if (to) q.set("to", to);
      q.set("groupBy", document.getElementById("sbGroup").value);
      q.set("sort", sbSort);
      q.set("dir", sbDir);
      q.set("zone", Intl.DateTimeFormat().resolvedOptions().timeZone);
      return q.toString();
    }

    function sortSalesBreakdownBy(column) {
      if (sbSort === column) {
        sbDir = sbDir === "desc" ? "asc" : "desc";
      } else {
        sbSort = column;
        sbDir = column === "label" ? "asc" : "desc";
      }
      loadSalesBreakdown(true);
    }

    // keepSort is true when a column heading was clicked; a fresh Load keeps whatever sort is showing too.
    async function loadSalesBreakdown(keepSort) {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const result = await apiCall(`${BASE_URL}/analytics/breakdown?${salesBreakdownQuery()}`, {
        headers: { "X-Service-Key": serviceKey }
      });
      const byProduct = result.groupBy === "product";
      document.getElementById("sbSummary").innerHTML =
        `<strong>${rupees(result.totalRevenue)}</strong> from <strong>${result.totalUnits}</strong> unit(s) in <strong>${result.totalOrders}</strong> order(s), ${escapeCell(result.from)} to ${escapeCell(result.to)}`
        + (result.truncated ? ` &middot; showing the top ${result.rows.length} rows (the CSV has every row)` : "");
      const wrap = document.getElementById("sbTable");
      if (!result.rows.length) {
        wrap.innerHTML = `<p>No sales in this range.</p>`;
        return;
      }
      const arrow = col => sbSort === col ? (sbDir === "asc" ? " &#9650;" : " &#9660;") : "";
      const th = (col, label, cls) => `<th ${cls ? `class="${cls}"` : ""} style="cursor:pointer" onclick="sortSalesBreakdownBy('${col}')" title="Sort by ${label}">${label}${arrow(col)}</th>`;
      wrap.innerHTML = `<table class="orders-table"><thead><tr>
          ${th("label", byProduct ? "Product" : "Category")}${byProduct ? "<th>Category</th>" : ""}${th("units", "Units", "num")}${th("orders", "Orders", "num")}${th("revenue", "Revenue", "num")}<th class="num">Share</th>
        </tr></thead><tbody>${result.rows.map(r => `<tr>
          <td>${escapeCell(r.label)}</td>${byProduct ? `<td>${escapeCell(r.category)}</td>` : ""}<td class="num">${r.units}</td><td class="num">${r.orders}</td>
          <td class="num">${rupees(r.revenue)}</td><td class="num">${r.sharePercent.toFixed(1)}%</td></tr>`).join("")}
        </tbody></table>`;
    }

    async function downloadSalesBreakdownCsv() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      try {
        const response = await fetch(`${BASE_URL}/analytics/breakdown/export?${salesBreakdownQuery()}`, { headers: { "X-Service-Key": serviceKey } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP Error: ${response.status}`);
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = `sales-by-${document.getElementById("sbGroup").value}.csv`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        showStatus("Sales breakdown CSV downloaded.", "success");
      } catch (error) {
        showStatus("CSV download failed: " + error.message, "error");
      }
    }

    async function downloadOrdersCsv() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      try {
        const response = await fetch(`${BASE_URL}/orders/export?${ordersQuery()}`, { headers: { "X-Service-Key": serviceKey } });
        if (!response.ok) throw new Error((await response.text()) || `HTTP Error: ${response.status}`);
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "orders.csv";
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        showStatus("orders.csv downloaded.", "success");
      } catch (error) {
        showStatus("CSV download failed: " + error.message, "error");
      }
    }

    async function getLowStockReport() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`${BASE_URL}/lowstock`, {
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus(data.length ? `${data.length} product(s) need restocking. Result opened in new tab.` : "Everything is well stocked.", "success");
      openResultTab("Low Stock Report", data);
    }

    async function deleteProductFromCart() {
      const phno = document.getElementById("deletePhno").value;
      const productId = document.getElementById("deleteProductId").value;

      if (!phno || !productId) return showStatus("Please enter phone number and product ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`${BASE_URL}/deleteproduct?phno=${encodeURIComponent(phno)}&productId=${encodeURIComponent(productId)}`, {
        method: "DELETE",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Product deleted from cart successfully. Result opened in new tab.", "success");
      openResultTab("Delete Product From Cart", data);
    }

    async function addToWishlist() {
      const phno = document.getElementById("wishlistAddPhno").value;
      const productId = document.getElementById("wishlistAddProductId").value;
      if (!phno || !productId) return showStatus("Please enter phone number and product ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`/wishlist/add?phno=${encodeURIComponent(phno)}&productId=${encodeURIComponent(productId)}`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Added to wishlist successfully. Result opened in new tab.", "success");
      openResultTab("Add To Wishlist", data);
    }

    async function viewWishlist() {
      const phno = document.getElementById("wishlistViewPhno").value;
      if (!phno) return showStatus("Please enter customer phone number.", "error");

      const data = await apiCall(`/wishlist/byphno?phno=${encodeURIComponent(phno)}`);
      showStatus("Wishlist loaded successfully. Result opened in new tab.", "success");
      openResultTab("Wishlist", data);
    }

    async function removeFromWishlist() {
      const phno = document.getElementById("wishlistRemovePhno").value;
      const productId = document.getElementById("wishlistRemoveProductId").value;
      if (!phno || !productId) return showStatus("Please enter phone number and product ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`/wishlist/remove?phno=${encodeURIComponent(phno)}&productId=${encodeURIComponent(productId)}`, {
        method: "DELETE",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Removed from wishlist successfully. Result opened in new tab.", "success");
      openResultTab("Remove From Wishlist", data);
    }

    async function viewCustomerProfile() {
      const phno = document.getElementById("profilePhno").value;
      if (!phno) return showStatus("Please enter customer phone number.", "error");

      const data = await apiCall(`/customer/profile?phno=${encodeURIComponent(phno)}`);

      showStatus("Customer profile loaded. Result opened in new tab.", "success");
      openResultTab("Customer Profile", data);
    }

    async function viewFrequentlyBoughtTogether() {
      const productId = document.getElementById("fbtProductId").value;
      const limit = document.getElementById("fbtLimit").value;
      if (!productId) return showStatus("Please enter a product ID.", "error");

      const query = limit ? `productId=${encodeURIComponent(productId)}&limit=${encodeURIComponent(limit)}` : `productId=${encodeURIComponent(productId)}`;
      const data = await apiCall(`${BASE_URL}/frequentlyboughttogether?${query}`);

      showStatus("Frequently bought together loaded. Result opened in new tab.", "success");
      openResultTab("Frequently Bought Together", data);
    }

    async function viewPriceDrops() {
      const phno = document.getElementById("priceDropsPhno").value;
      if (!phno) return showStatus("Please enter customer phone number.", "error");

      const data = await apiCall(`/wishlist/pricedrops?phno=${encodeURIComponent(phno)}`);

      showStatus("Price drop check complete. Result opened in new tab.", "success");
      openResultTab("Wishlist Price Drops", data);
    }

    async function viewLoyalty() {
      const phno = document.getElementById("loyaltyViewPhno").value;
      if (!phno) return showStatus("Please enter customer phone number.", "error");

      const [balance, history] = await Promise.all([
        apiCall(`/loyalty/byphno?phno=${encodeURIComponent(phno)}`),
        apiCall(`/loyalty/history?phno=${encodeURIComponent(phno)}`)
      ]);

      showStatus("Loyalty points loaded. Result opened in new tab.", "success");
      openResultTab("Loyalty Points", [balance, ...history]);
    }

    async function rebindSignInEmail() {
      const phno = document.getElementById("rebindEmailPhno").value;
      const email = document.getElementById("rebindEmailAddress").value.trim();
      if (!phno || !email) return showStatus("Please enter the phone number and the new email.", "error");
      if (!requireServiceKey()) return;

      await apiCall(`/customer/admin/email?phno=${encodeURIComponent(phno)}&email=${encodeURIComponent(email)}`, {
        method: "PUT"
      });
      showStatus("Sign-in email rebound. Existing sessions for that number were signed out.", "success");
    }

    async function adjustLoyalty() {
      const phno = document.getElementById("loyaltyAdjustPhno").value;
      const points = document.getElementById("loyaltyAdjustPoints").value;
      const reason = document.getElementById("loyaltyAdjustReason").value.trim();
      if (!phno || !points || !reason) return showStatus("Please enter phone number, points and a reason.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`/loyalty/adjust`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Service-Key": serviceKey
        },
        body: JSON.stringify({
          customerPhno: Number(phno),
          points: Number(points),
          reason
        })
      });

      showStatus("Loyalty points adjusted successfully. Result opened in new tab.", "success");
      openResultTab("Adjust Loyalty Points", data);
    }

    async function addCoupon() {
      const code = document.getElementById("newCouponCode").value.trim();
      const discountPercent = document.getElementById("newCouponDiscount").value;
      const expiry = document.getElementById("newCouponExpiry").value;
      const maxRedemptions = document.getElementById("newCouponMaxRedemptions").value;
      const perCustomerLimit = document.getElementById("newCouponPerCustomerLimit").value;

      if (!code || !discountPercent) return showStatus("Please enter a coupon code and discount percent.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`/coupons/add`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Service-Key": serviceKey
        },
        body: JSON.stringify({
          code,
          discountPercent: Number(discountPercent),
          active: true,
          expiryDate: expiry ? new Date(expiry + "T23:59:59Z").toISOString() : null,
          maxRedemptions: maxRedemptions ? Number(maxRedemptions) : null,
          perCustomerLimit: perCustomerLimit ? Number(perCustomerLimit) : null
        })
      });

      showStatus("Coupon saved successfully. Result opened in new tab.", "success");
      openResultTab("Add Coupon", data);
      await warnIfCouponSellsBelowCost(Number(discountPercent), serviceKey);
    }

    // After a coupon is saved: products that would sell below cost at this percent off (only those with a cost price set).
    async function warnIfCouponSellsBelowCost(percent, serviceKey) {
      const check = await apiCall(`/coupons/margincheck?discountPercent=${encodeURIComponent(percent)}`,
        { headers: { "X-Service-Key": serviceKey } });
      if (!check) return;
      if (!check.atLoss.length) {
        showStatus(check.productsWithCost === 0
          ? "Coupon saved. No product has a cost price set, so its margin could not be checked."
          : `Coupon saved. None of the ${check.productsWithCost} products with a cost price would sell below cost.`, "success");
        return;
      }
      const names = check.atLoss.slice(0, 5).map(p => `${p.productName} (-\u20b9${p.lossPerUnit.toFixed(2)} each)`).join(", ");
      showStatus(`Coupon saved, but ${percent}% off sells ${check.atLoss.length} product(s) below cost: ${names}${check.atLoss.length > 5 ? ", ..." : ""}.`, "error");
    }

    async function viewStoreCredit() {
      const phno = document.getElementById("creditPhno").value.trim();
      if (!phno) return showStatus("Please enter the customer's phone number.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`/storecredit/byphno?phno=${encodeURIComponent(phno)}`, { headers: { "X-Service-Key": serviceKey } });
      if (!data) return;
      showStatus(`Store credit balance: ₹${Number(data.balance).toFixed(2)}. History opened in new tab.`, "success");
      openResultTab("Store Credit", data);
    }

    async function adjustStoreCredit() {
      const phno = document.getElementById("creditPhno").value.trim();
      const amount = document.getElementById("creditAmount").value.trim();
      const note = document.getElementById("creditNote").value.trim();
      if (!phno || !amount || !note) return showStatus("Phone number, amount and a note are all required.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`/storecredit/adjust?phno=${encodeURIComponent(phno)}&amount=${encodeURIComponent(amount)}&note=${encodeURIComponent(note)}`,
        { method: "POST", headers: { "X-Service-Key": serviceKey } });
      if (!data) return;
      showStatus(`Store credit is now ₹${Number(data.balance).toFixed(2)}.`, "success");
    }

    async function generateBulkCoupons() {
      const prefix = document.getElementById("bulkCouponPrefix").value.trim();
      const count = document.getElementById("bulkCouponCount").value;
      const discountPercent = document.getElementById("bulkCouponDiscount").value;
      const expiry = document.getElementById("bulkCouponExpiry").value;
      if (!count || !discountPercent) return showStatus("Please enter how many codes and the discount percent.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`/coupons/bulk`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({
          prefix: prefix || null,
          count: Number(count),
          discountPercent: Number(discountPercent),
          expiryDate: expiry ? new Date(expiry + "T23:59:59Z").toISOString() : null
        })
      });
      if (!data || !data.codes) return;
      document.getElementById("bulkCouponCodes").value = data.codes.join("\n");
      showStatus(`${data.codes.length} single-use codes generated.`, "success");
    }

    async function saveFaq() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const id = document.getElementById("faqId").value.trim();
      const data = await apiCall(`/faq/save`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({ id: id ? Number(id) : null, sortOrder: Number(document.getElementById("faqOrder").value || 0),
          question: document.getElementById("faqQuestion").value, answer: document.getElementById("faqAnswer").value })
      });
      showStatus("FAQ entry saved. Result opened in new tab.", "success");
      openResultTab("FAQ Entry", data);
    }

    async function listFaq() {
      const data = await apiCall(`/faq`, {});
      openResultTab("FAQ Entries", data);
    }

    async function deleteFaq() {
      const id = document.getElementById("faqId").value.trim();
      if (!id) return showStatus("Enter the entry id to delete.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      if (!confirm(`Delete FAQ entry ${id}?`)) return;
      await apiCall(`/faq/${encodeURIComponent(id)}`, {
        method: "DELETE",
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus("FAQ entry deleted.", "success");
    }

    async function addPincode() {
      const pincode = document.getElementById("pinCode").value.trim();
      const deliveryDays = document.getElementById("pinDays").value;
      if (!pincode || !deliveryDays) return showStatus("Please enter a pincode and delivery days.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`/pincodes/add`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Service-Key": serviceKey },
        body: JSON.stringify({ pincode, deliveryDays: Number(deliveryDays),
          city: document.getElementById("pinCity").value.trim(), state: document.getElementById("pinState").value.trim(),
          deliveryFee: document.getElementById("pinFee").value.trim() === "" ? null : Number(document.getElementById("pinFee").value) })
      });
      showStatus("Pincode saved. Result opened in new tab.", "success");
      openResultTab("Add Pincode", data);
    }

    async function importPincodes() {
      const csv = document.getElementById("pinImportCsv").value;
      if (!csv.trim()) return showStatus("Paste some pincode rows first.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`/pincodes/import`, {
        method: "POST",
        headers: { "Content-Type": "text/plain", "X-Service-Key": serviceKey },
        body: csv
      });
      showStatus("Import finished. Result opened in new tab.", "success");
      openResultTab("Import Pincodes", data);
    }

    async function listPincodes() {
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const data = await apiCall(`/pincodes/all`, { headers: { "X-Service-Key": serviceKey } });
      openResultTab("Serviceable Pincodes", data);
    }

    async function removePincode() {
      const pincode = document.getElementById("pinCode").value.trim();
      if (!pincode) return showStatus("Please enter the pincode to remove.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      await apiCall(`/pincodes/remove?pincode=${encodeURIComponent(pincode)}`, {
        method: "DELETE",
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus("Pincode removed.", "success");
    }

    async function addAddress() {
      const phno = document.getElementById("addrPhno").value;
      const line1 = document.getElementById("addrLine1").value.trim();
      const city = document.getElementById("addrCity").value.trim();
      const state = document.getElementById("addrState").value.trim();
      const pincode = document.getElementById("addrPincode").value.trim();

      if (!phno || !line1 || !city || !state || !pincode) {
        return showStatus("Phone, address line 1, city, state and pincode are required.", "error");
      }

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`/addresses/add`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Service-Key": serviceKey
        },
        body: JSON.stringify({
          customerPhno: Number(phno),
          label: document.getElementById("addrLabel").value.trim() || null,
          line1,
          line2: document.getElementById("addrLine2").value.trim() || null,
          city,
          state,
          pincode,
          isDefault: document.getElementById("addrIsDefault").checked
        })
      });

      showStatus("Address saved successfully. Result opened in new tab.", "success");
      openResultTab("Add Address", data);
    }

    async function viewAddresses() {
      const phno = document.getElementById("viewAddrPhno").value;
      if (!phno) return showStatus("Please enter a customer phone number.", "error");

      const data = await apiCall(`/addresses/byphno?phno=${encodeURIComponent(phno)}`);

      showStatus("Addresses loaded. Result opened in new tab.", "success");
      openResultTab("View Addresses", data);
    }

    async function removeAddress() {
      const phno = document.getElementById("removeAddrPhno").value;
      const addressId = document.getElementById("removeAddrId").value;
      if (!phno || !addressId) return showStatus("Please enter a phone number and address ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      await apiCall(`/addresses/remove?phno=${encodeURIComponent(phno)}&addressId=${encodeURIComponent(addressId)}`, {
        method: "DELETE",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Address removed successfully.", "success");
    }

    async function shipOrder() {
      const orderId = document.getElementById("shipOrderId").value;
      if (!orderId) return showStatus("Please enter an order ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const carrier = document.getElementById("shipCarrier").value.trim();
      const tracking = document.getElementById("shipTracking").value.trim();
      const params = new URLSearchParams();
      if (carrier) params.set("carrier", carrier);
      if (tracking) params.set("trackingNumber", tracking);
      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/ship${params.toString() ? "?" + params : ""}`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Order shipped successfully. Result opened in new tab.", "success");
      openResultTab("Ship Order", data);
    }

    // For an order that is already shipped: sends whichever of carrier/tracking is filled in (a blank-but-present
    // value is not possible from this form, so it only sets, never clears).
    async function updateShipment() {
      const orderId = document.getElementById("shipOrderId").value;
      const carrier = document.getElementById("shipCarrier").value.trim();
      const tracking = document.getElementById("shipTracking").value.trim();
      if (!orderId || (!carrier && !tracking)) return showStatus("Enter an order ID and a carrier and/or tracking number.", "error");
      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const params = new URLSearchParams();
      if (carrier) params.set("carrier", carrier);
      if (tracking) params.set("trackingNumber", tracking);
      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/shipment?${params}`, {
        method: "PUT",
        headers: { "X-Service-Key": serviceKey }
      });
      showStatus("Shipment details updated. Result opened in new tab.", "success");
      openResultTab("Shipment Details", data);
    }

    async function deliverOrder() {
      const orderId = document.getElementById("deliverOrderId").value;
      if (!orderId) return showStatus("Please enter an order ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/deliver`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Order delivered successfully. Result opened in new tab.", "success");
      openResultTab("Deliver Order", data);
    }

    async function markPaid() {
      const orderId = document.getElementById("markPaidOrderId").value;
      if (!orderId) return showStatus("Please enter an order ID.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;

      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/markpaid`, {
        method: "POST",
        headers: { "X-Service-Key": serviceKey }
      });

      showStatus("Order marked paid successfully. Result opened in new tab.", "success");
      openResultTab("Mark Paid", data);
    }

    async function trackOrder() {
      const orderId = document.getElementById("trackOrderId").value;
      if (!orderId) return showStatus("Please enter an order ID.", "error");

      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/tracking`);

      showStatus("Tracking timeline loaded. Result opened in new tab.", "success");
      openResultTab("Track Order", data);
    }

    async function returnOrder() {
      const orderId = document.getElementById("returnOrderId").value;
      const reason = document.getElementById("returnReason").value.trim();
      if (!orderId || !reason) return showStatus("Please enter an order ID and a reason.", "error");

      const serviceKey = requireServiceKey();
      if (!serviceKey) return;
      const authorization = requireBuyerAuthorization();
      if (!authorization) return;

      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/return?reason=${encodeURIComponent(reason)}`, {
        method: "POST",
        headers: {
          "X-Service-Key": serviceKey,
          "Authorization": authorization
        }
      });

      showStatus("Order returned successfully. Result opened in new tab.", "success");
      openResultTab("Return Order", data);
    }

    async function viewNotifications() {
      const orderId = document.getElementById("notificationsOrderId").value;
      if (!orderId) return showStatus("Please enter an order ID.", "error");

      const data = await apiCall(`${BASE_URL}/${encodeURIComponent(orderId)}/notifications`);

      showStatus("Notifications loaded. Result opened in new tab.", "success");
      openResultTab("View Notifications", data);
    }

    function flattenRows(data) {
      const rows = data === null || data === undefined
        ? [{ message: "Operation completed successfully" }]
        : Array.isArray(data)
          ? data
          : [data];

      const flatRows = [];

      rows.forEach((row, index) => {
        if (row && Array.isArray(row.orderItems)) {
          row.orderItems.forEach((item, itemIndex) => {
            flatRows.push({
              cartNo: index + 1,
              itemNo: itemIndex + 1,
              cartId: row.cartId ?? row.id ?? "-",
              customerPhno: row.customerPhno ?? row.customer_phno ?? "-",
              totalPrice: row.totalPrice ?? row.total_price ?? "-",
              productId: item.productId ?? "-",
              productQuantity: item.productQuantity ?? "-",
              productPrice: item.productPrice ?? "-"
            });
          });
        } else {
          flatRows.push(row || {});
        }
      });

      return flatRows;
    }

    function openResultTab(title, data) {
      const resultTab = window.open("about:blank", "_blank");

      if (!resultTab) {
        alert("New tab blocked. Please allow popups/tabs for this page.");
        return;
      }

      const rows = flattenRows(data);
      let tableHtml = "";

      if (rows.length === 0) {
        tableHtml = `<div class="empty">No records found.</div>`;
      } else {
        const columns = [...new Set(rows.flatMap(row => Object.keys(row || {})))];
        const thead = columns.map(col => `<th>${escapeHtml(formatLabel(col))}</th>`).join("");
        const tbody = rows.map((row, index) => {
          const cells = columns.map(col => `<td>${valueHtml(row[col])}</td>`).join("");
          return `<tr><td class="serial">${index + 1}</td>${cells}</tr>`;
        }).join("");

        tableHtml = `
          <div class="table-card">
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>S.No</th>
                    ${thead}
                  </tr>
                </thead>
                <tbody>${tbody}</tbody>
              </table>
            </div>
          </div>
        `;
      }

      resultTab.document.open();
      resultTab.document.write(`
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>${escapeHtml(title)}</title>
          <style>
            * {
              box-sizing: border-box;
              margin: 0;
              padding: 0;
              font-family: Inter, Segoe UI, Arial, sans-serif;
            }

            body {
              min-height: 100vh;
              background: #f8fafc;
              color: #0f172a;
            }

            .header {
              background: linear-gradient(135deg, #111827, #312e81, #6d28d9);
              color: white;
              padding: 38px 28px 60px;
              text-align: center;
            }

            .header h1 {
              font-size: 38px;
              margin-bottom: 10px;
              letter-spacing: -0.7px;
            }

            .header p {
              color: #ede9fe;
              font-size: 17px;
            }

            .summary {
              width: min(1180px, calc(100% - 32px));
              margin: -30px auto 24px;
              background: white;
              border-radius: 24px;
              padding: 22px 24px;
              box-shadow: 0 20px 50px rgba(15, 23, 42, 0.12);
              display: flex;
              justify-content: space-between;
              align-items: center;
              gap: 16px;
            }

            .summary h2 {
              font-size: 23px;
              color: #4c1d95;
            }

            .count {
              background: #f3e8ff;
              color: #6d28d9;
              padding: 10px 15px;
              border-radius: 999px;
              font-weight: 950;
              white-space: nowrap;
            }

            .content {
              width: min(1180px, calc(100% - 32px));
              margin: 0 auto 38px;
            }

            .table-card, .empty {
              background: white;
              border: 1px solid #e2e8f0;
              border-radius: 24px;
              box-shadow: 0 16px 35px rgba(15, 23, 42, 0.08);
              overflow: hidden;
            }

            .empty {
              padding: 30px;
              font-weight: 900;
              color: #64748b;
            }

            .table-wrap {
              overflow-x: auto;
            }

            table {
              width: 100%;
              border-collapse: collapse;
              min-width: 850px;
            }

            th {
              background: #f5f3ff;
              color: #4c1d95;
              padding: 15px 16px;
              font-size: 14px;
              text-align: left;
              border-bottom: 1px solid #ddd6fe;
              white-space: nowrap;
            }

            td {
              padding: 15px 16px;
              border-bottom: 1px solid #edf2f7;
              font-size: 15px;
              color: #0f172a;
              font-weight: 750;
              vertical-align: top;
            }

            tr:hover td {
              background: #faf7ff;
            }

            .serial {
              color: #6d28d9;
              font-weight: 950;
            }

            .actions {
              width: min(1180px, calc(100% - 32px));
              margin: 0 auto 35px;
              display: flex;
              gap: 12px;
            }

            button {
              border: none;
              border-radius: 14px;
              padding: 13px 18px;
              font-weight: 950;
              cursor: pointer;
              color: white;
              background: #6d28d9;
            }

            button.close {
              background: #0f172a;
            }
          </style>
        </head>
        <body>
          <div class="header">
            <h1>${escapeHtml(title)}</h1>
            <p>Cart Order Service API Result</p>
          </div>

          <div class="summary">
            <h2>Result Table</h2>
            <div class="count">${rows.length} record(s)</div>
          </div>

          <main class="content">
            ${tableHtml}
          </main>

          <div class="actions">
            <button onclick="window.print()">Print Result</button>
            <button class="close" onclick="window.close()">Close Tab</button>
          </div>
        </body>
        </html>
      `);
      resultTab.document.close();
    }
