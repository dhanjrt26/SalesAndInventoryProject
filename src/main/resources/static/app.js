(() => {
    const $ = (selector) => document.querySelector(selector);
    const state = {
        accessToken: "",
        refreshToken: "",
        user: null,
        products: [],
        adjustingProduct: null,
        posCatalogProducts: [],
        posCatalogLoaded: false,
        posSearchTimer: null,
        posSearchRequest: 0,
        customersLoaded: false,
        salesOffset: 0,
        salesPageSize: 50,
        cart: new Map(),
        page: "dashboard"
    };

    const loginView = $("#login-view");
    const appView = $("#app-view");
    const loginMessage = $("#login-message");
    const appMessage = $("#app-message");
    const globalProgress = $("#global-progress");
    const currency = new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" });
    let activeFetches = 0;
    let progressHideTimer = null;

    function beginFetchProgress() {
        window.clearTimeout(progressHideTimer);
        if (activeFetches === 0) {
            globalProgress.classList.remove("is-complete");
            globalProgress.classList.add("is-visible");
            globalProgress.setAttribute("aria-hidden", "false");
            globalProgress.setAttribute("aria-valuetext", "Loading");
        }
        activeFetches += 1;
    }

    function endFetchProgress() {
        activeFetches = Math.max(0, activeFetches - 1);
        if (activeFetches !== 0) return;

        globalProgress.classList.add("is-complete");
        globalProgress.setAttribute("aria-valuetext", "Loading complete");
        progressHideTimer = window.setTimeout(() => {
            if (activeFetches === 0) {
                globalProgress.classList.remove("is-visible", "is-complete");
                globalProgress.setAttribute("aria-hidden", "true");
                globalProgress.setAttribute("aria-valuetext", "Loading");
            }
        }, 220);
    }

    async function withFetchProgress(request) {
        beginFetchProgress();
        try {
            return await request();
        } finally {
            endFetchProgress();
        }
    }

    function showLogin(message = "") {
        state.accessToken = "";
        state.refreshToken = "";
        state.user = null;
        sessionStorage.removeItem("sales_access_token");
        sessionStorage.removeItem("sales_refresh_token");
        appView.classList.add("hidden");
        loginView.classList.remove("hidden");
        loginMessage.textContent = message;
    }

    function showError(message) {
        appMessage.textContent = message;
        appMessage.classList.remove("hidden");
        appMessage.classList.add("error");
    }

    function showNotice(message) {
        appMessage.textContent = message;
        appMessage.classList.remove("hidden", "error");
        window.setTimeout(() => appMessage.classList.add("hidden"), 4000);
    }

    async function readResponse(response) {
        const text = await response.text();
        let body = null;
        if (text) {
            try {
                body = JSON.parse(text);
            } catch {
                body = { message: text };
            }
        }
        if (!response.ok) {
            const message = body?.message || body?.msg || `Request failed (${response.status}).`;
            const error = new Error(message);
            error.status = response.status;
            throw error;
        }
        return body;
    }

    async function sessionRequest(path, body) {
        return withFetchProgress(async () => {
            const response = await fetch(`/api/session/${path}`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(body)
            });
            return readResponse(response);
        });
    }

    async function refreshSession() {
        if (!state.refreshToken) {
            throw new Error("Your session expired. Please sign in again.");
        }
        const session = await sessionRequest("refresh", { refresh_token: state.refreshToken });
        if (!session.access_token || !session.refresh_token) {
            throw new Error("Supabase did not return refreshed session tokens.");
        }
        saveSession(session);
    }

    function saveSession(session) {
        state.accessToken = session.access_token;
        state.refreshToken = session.refresh_token;
        sessionStorage.setItem("sales_access_token", state.accessToken);
        sessionStorage.setItem("sales_refresh_token", state.refreshToken);
    }

    async function api(path, options = {}, canRefresh = true) {
        return withFetchProgress(async () => {
            const response = await fetch(path, {
                ...options,
                headers: {
                    Accept: "application/json",
                    ...(options.body ? { "Content-Type": "application/json" } : {}),
                    ...(state.accessToken ? { Authorization: `Bearer ${state.accessToken}` } : {}),
                    ...options.headers
                }
            });
            if (response.status === 401 && canRefresh) {
                try {
                    await refreshSession();
                    return api(path, options, false);
                } catch (error) {
                    showLogin("Your session expired. Please sign in again.");
                    throw error;
                }
            }
            return readResponse(response);
        });
    }

    async function signIn(event) {
        event.preventDefault();
        loginMessage.textContent = "";
        const username = $("#username").value.trim();
        const password = $("#password").value;
        const button = event.currentTarget.querySelector("button[type='submit']");
        button.disabled = true;
        button.textContent = "Signing in...";
        try {
            const session = await sessionRequest("login", { username, password });
            if (!session.access_token || !session.refresh_token) {
                throw new Error("Authentication service returned an invalid session.");
            }
            saveSession(session);
            await loadApp();
        } catch (error) {
            loginMessage.textContent = error.message || "Sign-in failed.";
        } finally {
            button.disabled = false;
            button.textContent = "Sign in";
        }
    }

    async function loadApp() {
        const profile = await api("/api/auth/me");
        state.user = profile;
        $("#user-email").textContent = profile.email || "Signed in";
        $("#user-avatar").textContent = (profile.email || "S").slice(0, 1).toUpperCase();
        $("#user-roles").textContent = (profile.roles || []).join(" · ") || "No store role assigned";
        $("#sales-dashboard-card").classList.toggle("hidden", !hasSalesHistoryRole());
        document.querySelectorAll(".dashboard-profit-card").forEach((card) => {
            card.classList.toggle("hidden", !hasAdminRole());
        });
        $("#pos-customer-field").classList.toggle("hidden", !hasSalesHistoryRole());
        appView.classList.remove("hidden");
        loginView.classList.add("hidden");
        await navigate("dashboard");
    }

    async function navigate(page) {
        state.page = page;
        document.querySelectorAll(".nav-link").forEach((button) => {
            button.classList.toggle("active", button.dataset.page === page);
        });
        document.querySelectorAll(".page-section").forEach((section) => section.classList.add("hidden"));
        $(`#${page}-page`).classList.remove("hidden");
        $("#page-title").textContent = page === "pos" ? "Point of sale"
            : page === "inventory" ? "Inventory" : page === "sales" ? "Sales" : "Dashboard";
        appMessage.classList.add("hidden");
        try {
            if (page === "dashboard") await loadDashboard();
            if (page === "pos") {
                const requests = [];
                if (!state.posCatalogLoaded) {
                    $("#product-results-empty").textContent = "Loading products…";
                    $("#product-results-empty").classList.remove("hidden");
                    requests.push(loadPosCatalog());
                }
                if (hasSalesHistoryRole() && !state.customersLoaded) requests.push(loadPosCustomers());
                await Promise.all(requests);
            }
            if (page === "inventory") await loadInventory();
            if (page === "sales") await loadSalesPage();
        } catch (error) {
            showError(error.message);
        }
    }

    async function loadDashboard() {
        const requests = [
            api("/api/dashboard"),
            api("/api/inventory/alerts?limit=100")
        ];
        if (hasSalesHistoryRole()) {
            requests.push(api("/api/sales/history?limit=1&offset=0"));
        }
        const showProfit = hasAdminRole();
        if (showProfit) {
            requests.push(api("/api/dashboard/profit"));
        }
        const [summary, alerts, salesOverview, profitSummary] = await Promise.all(requests);
        $("#stat-today").textContent = currency.format(Number(summary.today_sales || 0));
        $("#stat-week").textContent = currency.format(Number(summary.weekly_sales || 0));
        $("#stat-month").textContent = currency.format(Number(summary.monthly_sales || 0));
        $("#stat-inventory").textContent = currency.format(Number(summary.inventory_value || 0));
        if (hasSalesHistoryRole()) {
            $("#stat-sales-count").textContent = Number(salesOverview.totalCount).toLocaleString();
        }
        if (showProfit) {
            $("#profit-today").textContent = currency.format(Number(profitSummary.today_gross_profit || 0));
            $("#profit-week").textContent = currency.format(Number(profitSummary.weekly_gross_profit || 0));
            $("#profit-month").textContent = currency.format(Number(profitSummary.monthly_gross_profit || 0));
        }
        $("#alert-count").textContent = `${alerts.length} ${alerts.length === 1 ? "item" : "items"}`;
        const body = $("#alerts-table");
        body.replaceChildren();
        for (const product of alerts) {
            const row = document.createElement("tr");
            appendCell(row, product.name);
            appendCell(row, product.sku);
            appendCell(row, product.barcode || "—");
            appendCell(row, product.stockQty);
            appendCell(row, product.minStock);
            const statusCell = document.createElement("td");
            const badge = document.createElement("span");
            badge.className = `badge ${product.status === "OUT_OF_STOCK" ? "out" : "low"}`;
            badge.textContent = product.status === "OUT_OF_STOCK" ? "Out of stock" : "Low stock";
            statusCell.append(badge);
            row.append(statusCell);
            body.append(row);
        }
        $("#alerts-empty").classList.toggle("hidden", alerts.length > 0);
    }

    async function loadSalesPage() {
        const period = getSalesPeriod();
        const parameters = new URLSearchParams({
            limit: String(state.salesPageSize),
            offset: String(state.salesOffset)
        });
        if (period) {
            parameters.set("from", period.from);
            parameters.set("to", period.to);
        }
        const result = await api(`/api/sales/history?${parameters.toString()}`);
        renderSalesPage(result);
    }

    function getSalesPeriod() {
        const type = $("#sales-filter-type").value;
        if (type === "all") return null;

        const value = $("#sales-filter-value").value;
        if (!value) {
            throw new Error("Choose a value for the selected sales period.");
        }

        let start;
        let end;
        if (type === "date") {
            const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
            if (!match) throw new Error("Choose a valid date.");
            const [, year, month, day] = match.map(Number);
            start = new Date(year, month - 1, day);
            end = new Date(year, month - 1, day + 1);
        } else if (type === "month") {
            const match = /^(\d{4})-(\d{2})$/.exec(value);
            if (!match) throw new Error("Choose a valid month.");
            const [, year, month] = match.map(Number);
            start = new Date(year, month - 1, 1);
            end = new Date(year, month, 1);
        } else if (type === "year") {
            if (!/^\d{4}$/.test(value)) throw new Error("Enter a valid year.");
            const year = Number(value);
            start = new Date(year, 0, 1);
            end = new Date(year + 1, 0, 1);
        } else {
            return null;
        }
        return { from: start.toISOString(), to: end.toISOString() };
    }

    function renderSalesPage(result) {
        const sales = result.sales || [];
        const body = $("#sales-table");
        body.replaceChildren();
        $("#sales-summary-count").textContent = Number(result.totalCount).toLocaleString();
        $("#sales-summary-total").textContent = currency.format(Number(result.salesTotal));
        $("#sales-summary-average").textContent = currency.format(Number(result.averageSale));
        $("#sales-summary-profit").textContent = currency.format(Number(result.grossProfit));
        $("#sales-empty").classList.toggle("hidden", sales.length > 0);
        for (const sale of sales) {
            const row = document.createElement("tr");
            const date = new Date(sale.createdAt);
            appendCell(row, Number.isNaN(date.getTime()) ? sale.createdAt : date.toLocaleString());
            appendCell(row, sale.receiptNumber);
            appendCell(row, sale.customerName);
            appendCell(row, (sale.items || [])
                .map((item) => `${item.productName} × ${item.quantity}`)
                .join(", "));
            appendCell(row, currency.format(Number(sale.total)));
            body.append(row);
        }
        const firstSale = result.totalCount === 0 ? 0 : state.salesOffset + 1;
        const lastSale = state.salesOffset + sales.length;
        $("#sales-page-status").textContent = `Showing ${firstSale}–${lastSale} of ${result.totalCount} sales`;
        $("#sales-previous").disabled = state.salesOffset === 0;
        $("#sales-next").disabled = state.salesOffset + sales.length >= result.totalCount;
    }

    async function refreshSalesPage(resetOffset = false) {
        if (resetOffset) state.salesOffset = 0;
        try {
            await loadSalesPage();
        } catch (error) {
            showError(error.message);
        }
    }

    function updateSalesFilterInput() {
        const type = $("#sales-filter-type").value;
        const input = $("#sales-filter-value");
        const field = $("#sales-filter-value-field");
        const inputType = type === "month" ? "month" : type === "year" ? "number" : "date";
        input.type = inputType;
        input.value = "";
        input.required = type !== "all";
        input.min = type === "year" ? "1900" : "";
        input.max = type === "year" ? String(new Date().getFullYear()) : "";
        input.step = type === "year" ? "1" : "";
        field.classList.toggle("hidden", type === "all");
    }

    async function loadPosCustomers() {
        const customers = await api("/api/customers");
        const select = $("#pos-customer");
        const selectedId = select.value;
        const walkIn = document.createElement("option");
        walkIn.value = "";
        walkIn.textContent = "Walk-in customer";
        select.replaceChildren(walkIn);
        for (const customer of customers) {
            const option = document.createElement("option");
            option.value = customer.id;
            option.textContent = customer.phone ? `${customer.name} (${customer.phone})` : customer.name;
            select.append(option);
        }
        select.value = selectedId;
        state.customersLoaded = true;
    }

    async function loadPosCatalog() {
        const products = await api("/api/products?limit=100&offset=0");
        state.posCatalogProducts = products.filter((product) => product.status === "ACTIVE");
        state.posCatalogLoaded = true;
        if (!$("#product-query").value.trim()) {
            state.products = state.posCatalogProducts;
            renderSearchResults();
            if (state.posCatalogProducts.length === 0) {
                $("#product-results-empty").textContent = "No active products available.";
            }
        }
    }

    function schedulePosSearch() {
        window.clearTimeout(state.posSearchTimer);
        const requestId = ++state.posSearchRequest;
        const query = $("#product-query").value.trim();
        if (!query) {
            state.products = state.posCatalogProducts;
            renderSearchResults();
            $("#product-results-empty").textContent = state.posCatalogLoaded
                ? "No active products available."
                : "Loading products…";
            return;
        }

        $("#product-results").replaceChildren();
        $("#product-results-empty").textContent = "Searching products…";
        $("#product-results-empty").classList.remove("hidden");
        state.posSearchTimer = window.setTimeout(() => searchForPos(false, requestId, false), 120);
    }

    async function searchForPos(
            autoAddBarcode = false,
            requestId = ++state.posSearchRequest,
            exactBarcodeLookup = autoAddBarcode) {
        const query = $("#product-query").value.trim();
        if (!query) {
            $("#product-query").focus();
            return;
        }
        try {
            const encoded = encodeURIComponent(query);
            let results = [];
            if (exactBarcodeLookup) {
                try {
                    const byBarcode = await api(`/api/products/barcode/${encoded}`);
                    if (requestId !== state.posSearchRequest) return;
                    results = [byBarcode];
                } catch (error) {
                    if (requestId !== state.posSearchRequest) return;
                    if (error.status !== 404) throw error;
                    results = await api(`/api/products?q=${encoded}&limit=30`);
                }
            } else {
                results = await api(`/api/products?q=${encoded}&limit=30`);
            }
            if (requestId !== state.posSearchRequest) return;
            state.products = results.filter((product) => product.status === "ACTIVE");
            renderSearchResults();
            if (autoAddBarcode && state.products.length === 1 && state.products[0].barcode === query) {
                addToCart(state.products[0]);
            }
        } catch (error) {
            if (requestId === state.posSearchRequest) showError(error.message);
        }
    }

    function renderSearchResults() {
        const container = $("#product-results");
        container.replaceChildren();
        $("#product-results-empty").classList.toggle("hidden", state.products.length > 0);
        if (state.products.length === 0) {
            $("#product-results-empty").textContent = "No matching active products found.";
            return;
        }
        $("#product-results-empty").textContent = "";
        let resultIndex = 0;
        for (const product of state.products) {
            const card = document.createElement("article");
            card.className = "product-result";
            card.style.animationDelay = `${resultIndex * 35}ms`;
            resultIndex += 1;
            const info = document.createElement("div");
            const name = document.createElement("strong");
            name.textContent = product.name;
            const details = document.createElement("small");
            details.textContent = `${product.sku} · Stock ${product.stockQty}`;
            info.append(name, details);
            const action = document.createElement("button");
            action.className = "button secondary";
            action.type = "button";
            action.textContent = `Add · ${currency.format(Number(product.sellingPrice))}`;
            action.disabled = product.stockQty < 1;
            action.addEventListener("click", () => addToCart(product));
            card.append(info, action);
            container.append(card);
        }
    }

    function addToCart(product) {
        if (product.status !== "ACTIVE") {
            showError("This product is inactive.");
            return;
        }
        const line = state.cart.get(product.id);
        const quantity = (line?.quantity || 0) + 1;
        if (quantity > product.stockQty) {
            showError(`Not enough stock for ${product.name}.`);
            return;
        }
        state.cart.set(product.id, { product, quantity });
        renderCart();
        appMessage.classList.add("hidden");
    }

    function updateQuantity(productId, delta) {
        const line = state.cart.get(productId);
        if (!line) return;
        const quantity = line.quantity + delta;
        if (quantity < 1) {
            state.cart.delete(productId);
        } else if (quantity > line.product.stockQty) {
            showError(`Only ${line.product.stockQty} unit(s) are in stock.`);
            return;
        } else {
            state.cart.set(productId, { ...line, quantity });
        }
        renderCart();
    }

    function renderCart() {
        const container = $("#cart-items");
        container.replaceChildren();
        let total = 0;
        let itemCount = 0;
        for (const [id, line] of state.cart) {
            const price = Number(line.product.sellingPrice);
            total += price * line.quantity;
            itemCount += line.quantity;
            const row = document.createElement("div");
            row.className = "cart-line";
            const details = document.createElement("div");
            const name = document.createElement("strong");
            name.textContent = line.product.name;
            const subline = document.createElement("small");
            subline.textContent = `${currency.format(price)} each · ${currency.format(price * line.quantity)}`;
            details.append(name, subline);
            const controls = document.createElement("div");
            controls.className = "quantity-controls";
            const minus = quantityButton("−", () => updateQuantity(id, -1));
            const quantity = document.createElement("span");
            quantity.textContent = line.quantity;
            const plus = quantityButton("+", () => updateQuantity(id, 1));
            const remove = quantityButton("Remove", () => {
                state.cart.delete(id);
                renderCart();
            });
            remove.classList.add("remove");
            controls.append(minus, quantity, plus, remove);
            row.append(details, controls);
            container.append(row);
        }
        $("#cart-empty").classList.toggle("hidden", itemCount > 0);
        $("#cart-count").textContent = `${itemCount} ${itemCount === 1 ? "item" : "items"}`;
        $("#cart-total").textContent = currency.format(total);
        $("#checkout").disabled = itemCount === 0;
    }

    function quantityButton(label, action) {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.addEventListener("click", action);
        return button;
    }

    async function checkout() {
        if (state.cart.size === 0) return;
        const button = $("#checkout");
        button.disabled = true;
        button.classList.add("is-loading");
        button.textContent = "Processing...";
        try {
            const method = $("#payment-method").value;
            const totalCents = [...state.cart.values()].reduce((sum, line) => {
                return sum + Math.round(Number(line.product.sellingPrice) * 100) * line.quantity;
            }, 0);
            const cashValue = $("#cash-tendered").value;
            const amountTenderedInCents = method === "CASH"
                ? Math.round(Number(cashValue) * 100)
                : totalCents;
            if (!Number.isSafeInteger(amountTenderedInCents) || amountTenderedInCents < 0) {
                throw new Error("Enter a valid cash amount.");
            }
            const receipt = await api("/api/sales/checkout", {
                method: "POST",
                body: JSON.stringify({
                    customerId: $("#pos-customer").value || null,
                    items: [...state.cart.values()].map(({ product, quantity }) => ({
                        productId: product.id,
                        quantity
                    })),
                    paymentMethod: method,
                    amountTenderedInCents
                })
            });
            renderReceipt(receipt);
            state.cart.clear();
            renderCart();
            $("#cash-tendered").value = "";
            $("#pos-customer").value = "";
            $("#receipt-dialog").showModal();
            showNotice(`Sale ${receipt.receiptNumber} completed.`);
            await loadDashboard();
        } catch (error) {
            showError(error.message);
        } finally {
            button.classList.remove("is-loading");
            button.textContent = "Complete sale";
            button.disabled = state.cart.size === 0;
        }
    }

    function renderReceipt(receipt) {
        const paper = $("#receipt-content");
        paper.replaceChildren();
        const title = document.createElement("h2");
        title.textContent = "Sari-Sari Store";
        paper.append(title);
        const number = document.createElement("p");
        number.textContent = receipt.receiptNumber;
        number.style.textAlign = "center";
        paper.append(number, receiptRule());
        for (const item of receipt.items || []) {
            const row = document.createElement("div");
            row.className = "receipt-row";
            appendText(row, `${item.productName} × ${item.quantity}`);
            appendText(row, currency.format(Number(item.lineTotal)));
            paper.append(row);
            const sub = document.createElement("small");
            sub.textContent = `${currency.format(Number(item.unitPrice))} each`;
            paper.append(sub);
        }
        const total = document.createElement("div");
        total.className = "receipt-row";
        const totalLabel = document.createElement("strong");
        totalLabel.textContent = "TOTAL";
        const totalValue = document.createElement("strong");
        totalValue.textContent = currency.format(Number(receipt.total));
        total.append(totalLabel, totalValue);
        paper.append(receiptRule(), total);
        if (Number(receipt.changeDue) > 0) {
            const change = document.createElement("div");
            change.className = "receipt-row";
            appendText(change, "Change");
            appendText(change, currency.format(Number(receipt.changeDue)));
            paper.append(change);
        }
        const thanks = document.createElement("p");
        thanks.textContent = "Thank you!";
        thanks.style.textAlign = "center";
        paper.append(receiptRule(), thanks);
    }

    function receiptRule() {
        const rule = document.createElement("div");
        rule.className = "receipt-rule";
        return rule;
    }

    function appendText(parent, text) {
        const span = document.createElement("span");
        span.textContent = text;
        parent.append(span);
    }

    async function loadInventory() {
        await searchInventory();
    }

    async function searchInventory() {
        const query = $("#inventory-query").value.trim();
        try {
            const products = await api(`/api/products?q=${encodeURIComponent(query)}&limit=100`);
            const body = $("#inventory-table");
            body.replaceChildren();
            for (const product of products) {
                const row = document.createElement("tr");
                appendCell(row, product.name);
                appendCell(row, `${product.sku}${product.barcode ? ` / ${product.barcode}` : ""}`);
                appendCell(row, currency.format(Number(product.costPrice)));
                appendCell(row, currency.format(Number(product.sellingPrice)));
                appendCell(row, product.stockQty);
                const status = document.createElement("td");
                const badge = document.createElement("span");
                badge.className = `badge ${product.status === "ACTIVE" ? "low" : "out"}`;
                badge.textContent = product.status;
                status.append(badge);
                row.append(status);
                const actions = document.createElement("td");
                const adjust = document.createElement("button");
                adjust.className = "button secondary";
                adjust.type = "button";
                adjust.textContent = "Adjust";
                adjust.disabled = !hasInventoryRole();
                adjust.addEventListener("click", () => adjustStock(product));
                actions.append(adjust);
                row.append(actions);
                body.append(row);
            }
            $("#inventory-empty").classList.toggle("hidden", products.length > 0);
        } catch (error) {
            showError(error.message);
        }
    }

    function adjustStock(product) {
        state.adjustingProduct = product;
        $("#adjust-stock-product-name").textContent = product.name;
        $("#adjust-stock-product-sku").textContent = product.sku;
        $("#adjust-stock-current").textContent = product.stockQty;
        $("#adjust-stock-form").reset();
        $("#adjust-stock-error").textContent = "";
        $("#adjust-stock-error").classList.add("hidden");
        updateAdjustmentPreview();
        $("#adjust-stock-dialog").showModal();
        $("#adjust-stock-quantity").focus();
    }

    function updateAdjustmentPreview() {
        const product = state.adjustingProduct;
        if (!product) return;

        const raw = $("#adjust-stock-quantity").value;
        const quantityChange = Number(raw);
        const isValidChange = raw !== "" && Number.isInteger(quantityChange) && quantityChange !== 0;
        const nextStock = product.stockQty + (isValidChange ? quantityChange : 0);
        $("#adjust-stock-next").textContent = nextStock.toLocaleString();
        const belowZero = isValidChange && nextStock < 0;
        $("#adjust-stock-next").classList.toggle("is-invalid", belowZero);
        $("#adjust-stock-preview-note").textContent = belowZero
            ? "Stock cannot be reduced below zero."
            : isValidChange
                ? "This is the expected stock after the adjustment."
                : "Enter a positive number to add stock or a negative number to remove it.";
    }

    async function submitStockAdjustment(event) {
        event.preventDefault();
        const product = state.adjustingProduct;
        const quantityChange = Number($("#adjust-stock-quantity").value);
        const reason = $("#adjust-stock-reason").value.trim();
        const errorElement = $("#adjust-stock-error");
        if (!product) {
            errorElement.textContent = "Select a product before adjusting stock.";
            errorElement.classList.remove("hidden");
            return;
        }
        if (!Number.isInteger(quantityChange) || quantityChange === 0) {
            errorElement.textContent = "Enter a non-zero whole number for the quantity change.";
            errorElement.classList.remove("hidden");
            return;
        }
        if (product.stockQty + quantityChange < 0) {
            errorElement.textContent = "The adjustment cannot reduce stock below zero.";
            errorElement.classList.remove("hidden");
            return;
        }
        if (!reason) {
            errorElement.textContent = "A reason for the adjustment is required.";
            errorElement.classList.remove("hidden");
            return;
        }

        const button = $("#save-stock-adjustment");
        button.disabled = true;
        button.textContent = "Saving...";
        try {
            await api(`/api/inventory/${product.id}/adjustments`, {
                method: "POST",
                body: JSON.stringify({ quantityChange, reason })
            });
            $("#adjust-stock-dialog").close();
            showNotice("Inventory adjusted.");
            await searchInventory();
        } catch (error) {
            errorElement.textContent = error.message || "Could not adjust stock.";
            errorElement.classList.remove("hidden");
        } finally {
            button.disabled = false;
            button.textContent = "Save adjustment";
        }
    }

    async function createProduct(event) {
        event.preventDefault();
        const form = event.currentTarget;
        const fields = new FormData(form);
        const payload = {
            name: fields.get("name").trim(),
            sku: fields.get("sku").trim(),
            barcode: fields.get("barcode").trim() || null,
            costPrice: Number(fields.get("costPrice")),
            sellingPrice: Number(fields.get("sellingPrice")),
            stockQty: Number(fields.get("stockQty") || 0),
            minStock: Number(fields.get("minStock") || 0)
        };
        try {
            await api("/api/products", { method: "POST", body: JSON.stringify(payload) });
            form.reset();
            form.classList.add("hidden");
            showNotice("Product created.");
            await searchInventory();
        } catch (error) {
            showError(error.message);
        }
    }

    function appendCell(row, value) {
        const cell = document.createElement("td");
        cell.textContent = value === null || value === undefined || value === "" ? "—" : String(value);
        row.append(cell);
    }

    function hasInventoryRole() {
        const roles = state.user?.roles || [];
        return roles.includes("ADMIN") || roles.includes("INVENTORY_MANAGER");
    }

    function hasSalesHistoryRole() {
        const roles = state.user?.roles || [];
        return roles.includes("ADMIN") || roles.includes("CASHIER");
    }

    function hasAdminRole() {
        return (state.user?.roles || []).includes("ADMIN");
    }

    function canCreateProduct() {
        const roles = state.user?.roles || [];
        return roles.includes("ADMIN") || roles.includes("INVENTORY_MANAGER");
    }

    function setupEvents() {
        $("#login-form").addEventListener("submit", signIn);
        $("#sign-out").addEventListener("click", () => showLogin());
        $("#refresh-dashboard").addEventListener("click", () => navigate("dashboard"));
        $("#sales-dashboard-card").addEventListener("click", () => navigate("sales"));
        $("#sales-filter-type").addEventListener("change", updateSalesFilterInput);
        $("#apply-sales-filter").addEventListener("click", () => refreshSalesPage(true));
        $("#clear-sales-filter").addEventListener("click", () => {
            $("#sales-filter-type").value = "all";
            updateSalesFilterInput();
            refreshSalesPage(true);
        });
        $("#sales-filter-value").addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                refreshSalesPage(true);
            }
        });
        $("#sales-previous").addEventListener("click", () => {
            state.salesOffset = Math.max(0, state.salesOffset - state.salesPageSize);
            refreshSalesPage();
        });
        $("#sales-next").addEventListener("click", () => {
            state.salesOffset += state.salesPageSize;
            refreshSalesPage();
        });
        $("#product-query").addEventListener("input", schedulePosSearch);
        $("#search-products").addEventListener("click", () => {
            window.clearTimeout(state.posSearchTimer);
            searchForPos(true);
        });
        $("#product-query").addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                window.clearTimeout(state.posSearchTimer);
                searchForPos(true);
            }
        });
        $("#clear-cart").addEventListener("click", () => {
            state.cart.clear();
            renderCart();
        });
        $("#checkout").addEventListener("click", checkout);
        $("#payment-method").addEventListener("change", (event) => {
            $("#cash-field").classList.toggle("hidden", event.target.value !== "CASH");
        });
        $("#search-inventory").addEventListener("click", searchInventory);
        $("#inventory-query").addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                searchInventory();
            }
        });
        $("#new-product-toggle").addEventListener("click", () => {
            if (!canCreateProduct()) {
                showError("Only admins and inventory managers can add products.");
                return;
            }
            $("#new-product-form").classList.toggle("hidden");
        });
        $("#new-product-form").addEventListener("submit", createProduct);
        $("#cancel-product").addEventListener("click", () => $("#new-product-form").classList.add("hidden"));
        $("#adjust-stock-form").addEventListener("submit", submitStockAdjustment);
        $("#adjust-stock-quantity").addEventListener("input", updateAdjustmentPreview);
        $("#cancel-stock-adjustment").addEventListener("click", () => $("#adjust-stock-dialog").close());
        $("#adjust-stock-dialog").addEventListener("close", () => {
            state.adjustingProduct = null;
        });
        $("#print-receipt").addEventListener("click", () => window.print());
        $("#close-receipt").addEventListener("click", () => $("#receipt-dialog").close());
        document.querySelectorAll(".nav-link").forEach((button) => {
            button.addEventListener("click", () => navigate(button.dataset.page));
        });
    }

    function restoreSession() {
        state.accessToken = sessionStorage.getItem("sales_access_token") || "";
        state.refreshToken = sessionStorage.getItem("sales_refresh_token") || "";
    }

    setupEvents();
    restoreSession();
    if (state.accessToken) {
        loadApp().catch((error) => showLogin(error.message));
    }
})();
