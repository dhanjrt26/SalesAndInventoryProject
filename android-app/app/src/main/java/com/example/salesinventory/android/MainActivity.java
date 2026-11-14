package com.example.salesinventory.android;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.print.PrintManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, CartLine> cart = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> products = new LinkedHashMap<>();

    private LinearLayout root;
    private ScrollView pageScroll;
    private LinearLayout cartRows;
    private TextView status;
    private TextView totalText;
    private TextView dashboardSummary;
    private EditText barcodeInput;
    private EditText searchInput;
    private EditText tenderedInput;
    private Spinner paymentMethod;
    private Button checkoutButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (accessToken() == null) {
            showLogin();
        } else {
            showPos();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void showLogin() {
        root = page();
        addTitle(root, "Sari-Sari Store");
        addText(root, "Sign in with your Supabase Auth account.");
        EditText email = input("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = input("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(email);
        root.addView(password);
        status = addText(root, "");
        Button signIn = button("Sign in");
        root.addView(signIn);
        signIn.setOnClickListener(view -> {
            String base = BuildConfig.SUPABASE_URL;
            if (base.trim().isEmpty() || BuildConfig.SUPABASE_ANON_KEY.trim().isEmpty()) {
                showMessage("Set Supabase URL and publishable key in android-app/local.properties.");
                return;
            }
            signIn.setEnabled(false);
            status.setText("Signing in...");
            JSONObject body = new JSONObject();
            try {
                body.put("email", email.getText().toString().trim());
                body.put("password", password.getText().toString());
            } catch (Exception exception) {
                signIn.setEnabled(true);
                status.setText("Could not prepare sign-in request.");
                return;
            }
            requestAsync(() -> {
                JSONObject result = request(
                        base + "/auth/v1/token?grant_type=password",
                        "POST",
                        body,
                        BuildConfig.SUPABASE_ANON_KEY,
                        null);
                String token = result.optString("access_token", "");
                if (token.trim().isEmpty()) {
                    throw new IllegalStateException("Supabase did not return an access token.");
                }
                getSharedPreferences("session", MODE_PRIVATE).edit()
                        .putString("access_token", token)
                        .putString("refresh_token", result.optString("refresh_token", ""))
                        .apply();
                runOnUiThread(this::showPos);
            }, message -> {
                signIn.setEnabled(true);
                status.setText(message);
            });
        });
        setContentView(pageScroll);
    }

    private void showPos() {
        cart.clear();
        products.clear();
        root = page();
        addTitle(root, "Point of Sale");
        LinearLayout accountRow = row();
        accountRow.addView(addText(accountRow, "Signed in"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button logout = button("Sign out");
        accountRow.addView(logout);
        root.addView(accountRow);
        logout.setOnClickListener(view -> {
            getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply();
            showLogin();
        });
        Button dashboard = button("Refresh dashboard");
        root.addView(dashboard);
        dashboardSummary = addText(root, "");
        dashboard.setOnClickListener(view -> loadDashboard());

        status = addText(root, "");
        barcodeInput = input("Enter barcode (primary) or SKU", InputType.TYPE_CLASS_TEXT);
        root.addView(barcodeInput);
        Button addBarcode = button("Add barcode to cart");
        root.addView(addBarcode);
        addBarcode.setOnClickListener(view -> lookupBarcode());

        searchInput = input("Search products by name or SKU", InputType.TYPE_CLASS_TEXT);
        root.addView(searchInput);
        Button search = button("Search products");
        root.addView(search);
        search.setOnClickListener(view -> searchProducts());

        TextView resultsTitle = addText(root, "Search results");
        resultsTitle.setTextSize(18);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        TextView cartTitle = addText(root, "Cart");
        cartTitle.setTextSize(18);
        cartRows = new LinearLayout(this);
        cartRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(cartRows);
        totalText = addText(root, "Total: 0.00");
        paymentMethod = new Spinner(this);
        paymentMethod.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"CASH", "CARD", "EWALLET"}));
        root.addView(paymentMethod);
        tenderedInput = input("Cash received (e.g. 100.00)", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(tenderedInput);
        checkoutButton = button("Complete sale");
        root.addView(checkoutButton);
        checkoutButton.setOnClickListener(view -> checkout());
        setContentView(pageScroll);
        searchInput.setOnEditorActionListener((textView, actionId, event) -> {
            searchProducts();
            return true;
        });
        searchResultsView = results;
        loadDashboard();
    }

    private LinearLayout searchResultsView;

    private void loadDashboard() {
        requestAsync(() -> {
            JSONObject summary = api("/api/dashboard", "GET", null);
            runOnUiThread(() -> dashboardSummary.setText(
                    "Today: " + money(summary.optString("today_sales", "0"))
                            + " | Week: " + money(summary.optString("weekly_sales", "0"))
                            + " | Month: " + money(summary.optString("monthly_sales", "0"))
                            + "\nInventory value: " + money(summary.optString("inventory_value", "0"))
                            + " | Low stock: " + summary.optInt("low_stock_count")
                            + " | Out of stock: " + summary.optInt("out_of_stock_count")));
        }, message -> dashboardSummary.setText(message));
    }

    private void lookupBarcode() {
        String barcode = barcodeInput.getText().toString().trim();
        if (barcode.isEmpty()) {
            showMessage("Enter a barcode or SKU.");
            return;
        }
        status.setText("Looking up product...");
        requestAsync(() -> {
            JSONObject product = lookupProduct(barcode);
            runOnUiThread(() -> {
                try {
                    addToCart(product);
                    barcodeInput.setText("");
                    renderCart();
                    status.setText("Added " + product.optString("name") + ".");
                } catch (Exception exception) {
                    status.setText(exception.getMessage());
                }
            });
        }, message -> status.setText(message));
    }

    private void searchProducts() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            showMessage("Enter a product name, SKU, or barcode.");
            return;
        }
        status.setText("Searching...");
        requestAsync(() -> {
            JSONArray result = apiArray("/api/products?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&limit=20", "GET", null);
            runOnUiThread(() -> {
                searchResultsView.removeAllViews();
                for (int i = 0; i < result.length(); i++) {
                    JSONObject product = result.optJSONObject(i);
                    if (product != null && "ACTIVE".equals(product.optString("status"))) {
                        products.put(product.optString("id"), product);
                        Button add = button(product.optString("name") + "  "
                                + product.optString("sku") + "  "
                                + money(product.optString("sellingPrice"))
                                + "  (stock " + product.optInt("stockQty") + ")");
                        searchResultsView.addView(add);
                        add.setOnClickListener(view -> {
                            try {
                                addToCart(product);
                                renderCart();
                                status.setText("Added " + product.optString("name") + ".");
                            } catch (Exception exception) {
                                status.setText(exception.getMessage());
                            }
                        });
                    }
                }
                status.setText(result.length() + " result(s).");
            });
        }, message -> status.setText(message));
    }

    private JSONObject lookupProduct(String barcode) throws Exception {
        try {
            return api("/api/products/barcode/" + URLEncoder.encode(barcode, "UTF-8"), "GET", null);
        } catch (ApiRequestException exception) {
            if (exception.statusCode != 404) {
                throw exception;
            }
            JSONArray matches = apiArray("/api/products?q=" + URLEncoder.encode(barcode, "UTF-8")
                    + "&limit=20", "GET", null);
            JSONObject product = findSku(matches, barcode);
            if (product == null) {
                throw new IllegalStateException("No product matched that barcode or SKU.");
            }
            return product;
        }
    }

    private void checkout() {
        if (cart.isEmpty()) {
            showMessage("The cart is empty.");
            return;
        }
        checkoutButton.setEnabled(false);
        String method = paymentMethod.getSelectedItem().toString();
        long amountTenderedCents;
        try {
            amountTenderedCents = "CASH".equals(method)
                    ? new BigDecimal(tenderedInput.getText().toString().trim())
                            .movePointRight(2).longValueExact()
                    : cartTotalCents();
        } catch (Exception exception) {
            showMessage("Enter a valid cash amount with at most two decimal places.");
            return;
        }
        JSONArray items = new JSONArray();
        for (CartLine line : cart.values()) {
            JSONObject item = new JSONObject();
            try {
                item.put("productId", line.product.optString("id"));
                item.put("quantity", line.quantity);
                items.put(item);
            } catch (Exception exception) {
                showMessage("Could not prepare the cart.");
                return;
            }
        }
        JSONObject body = new JSONObject();
        try {
            body.put("items", items);
            body.put("paymentMethod", method);
            body.put("amountTenderedInCents", amountTenderedCents);
        } catch (Exception exception) {
            showMessage("Could not prepare the payment.");
            return;
        }
        status.setText("Processing sale...");
        requestAsync(() -> {
            JSONObject receipt = api("/api/sales/checkout", "POST", body);
            runOnUiThread(() -> {
                cart.clear();
                renderCart();
                checkoutButton.setEnabled(true);
                status.setText("Sale " + receipt.optString("receiptNumber") + " completed. "
                        + "Change: " + money(receipt.optString("changeDue")));
                printReceipt(receipt);
            });
        }, message -> {
            checkoutButton.setEnabled(true);
            status.setText(message);
        });
    }

    private void printReceipt(JSONObject receipt) {
        StringBuilder html = new StringBuilder("<html><body style='font-family:monospace;width:280px'>")
                .append("<h3>Sari-Sari Store</h3><div>")
                .append(escape(receipt.optString("receiptNumber"))).append("</div><hr>");
        JSONArray items = receipt.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    html.append("<p>").append(escape(item.optString("productName")))
                            .append("<br>").append(item.optInt("quantity")).append(" x ")
                            .append(money(item.optString("unitPrice"))).append(" = ")
                            .append(money(item.optString("lineTotal"))).append("</p>");
                }
            }
        }
        html.append("<hr><strong>Total: ").append(money(receipt.optString("total")))
                .append("</strong><p>Change: ").append(money(receipt.optString("changeDue")))
                .append("</p></body></html>");
        WebView webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                printManager.print("Receipt " + receipt.optString("receiptNumber"),
                        view.createPrintDocumentAdapter("Receipt"),
                        null);
            }
        });
        webView.loadDataWithBaseURL(null, html.toString(), "text/HTML", "UTF-8", null);
    }

    private void addToCart(JSONObject product) {
        if (!"ACTIVE".equals(product.optString("status"))) {
            throw new IllegalStateException("This product is inactive.");
        }
        String id = product.optString("id");
        CartLine current = cart.get(id);
        int nextQuantity = current == null ? 1 : current.quantity + 1;
        if (nextQuantity > product.optInt("stockQty")) {
            throw new IllegalStateException("Not enough stock for " + product.optString("name") + ".");
        }
        cart.put(id, new CartLine(product, nextQuantity));
    }

    private void renderCart() {
        cartRows.removeAllViews();
        for (Map.Entry<String, CartLine> entry : cart.entrySet()) {
            CartLine line = entry.getValue();
            LinearLayout cartRow = row();
            TextView label = addText(cartRow, line.product.optString("name") + " x "
                    + line.quantity + " = " + money(lineTotal(line)));
            cartRow.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button remove = button("Remove");
            cartRow.addView(remove);
            remove.setOnClickListener(view -> {
                cart.remove(entry.getKey());
                renderCart();
            });
            cartRows.addView(cartRow);
        }
        totalText.setText("Total: " + money(BigDecimal.valueOf(cartTotalCents(), 2).toPlainString()));
    }

    private long cartTotalCents() {
        long total = 0;
        for (CartLine line : cart.values()) {
            long lineCents = Math.multiplyExact(
                    new BigDecimal(line.product.optString("sellingPrice"))
                            .movePointRight(2).longValueExact(),
                    line.quantity);
            total = Math.addExact(total, lineCents);
        }
        return total;
    }

    private String lineTotal(CartLine line) {
        return new BigDecimal(line.product.optString("sellingPrice"))
                .multiply(BigDecimal.valueOf(line.quantity)).toPlainString();
    }

    private String accessToken() {
        return getSharedPreferences("session", MODE_PRIVATE).getString("access_token", null);
    }

    private JSONObject api(String path, String method, JSONObject body) throws Exception {
        try {
            return request(apiAddress(path), method, body, null, accessToken());
        } catch (ApiRequestException exception) {
            if (exception.statusCode != 401) {
                throw exception;
            }
            refreshAccessToken();
            return request(apiAddress(path), method, body, null, accessToken());
        }
    }

    private JSONArray apiArray(String path, String method, JSONObject body) throws Exception {
        String response;
        try {
            response = requestRaw(apiAddress(path), method, body, null, accessToken());
        } catch (ApiRequestException exception) {
            if (exception.statusCode != 401) {
                throw exception;
            }
            refreshAccessToken();
            response = requestRaw(apiAddress(path), method, body, null, accessToken());
        }
        return new JSONArray(response);
    }

    private void refreshAccessToken() throws Exception {
        String refreshToken = getSharedPreferences("session", MODE_PRIVATE)
                .getString("refresh_token", "");
        if (refreshToken.trim().isEmpty()) {
            throw new IllegalStateException("Your session expired. Please sign in again.");
        }
        JSONObject body = new JSONObject();
        body.put("refresh_token", refreshToken);
        JSONObject result = request(BuildConfig.SUPABASE_URL
                + "/auth/v1/token?grant_type=refresh_token",
                "POST", body, BuildConfig.SUPABASE_ANON_KEY, null);
        String access = result.optString("access_token", "");
        String refreshed = result.optString("refresh_token", "");
        if (access.trim().isEmpty() || refreshed.trim().isEmpty()) {
            throw new IllegalStateException("Supabase did not return refreshed session tokens.");
        }
        getSharedPreferences("session", MODE_PRIVATE).edit()
                .putString("access_token", access)
                .putString("refresh_token", refreshed)
                .apply();
    }

    private String apiAddress(String path) {
        String base = BuildConfig.API_BASE_URL;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private JSONObject request(String url, String method, JSONObject body,
            String supabaseApiKey, String bearerToken) throws Exception {
        return new JSONObject(requestRaw(url, method, body, supabaseApiKey, bearerToken));
    }

    private String requestRaw(String address, String method, JSONObject body,
            String supabaseApiKey, String bearerToken) throws Exception {
        if (address.startsWith("https://your-api.example.com")) {
            throw new IllegalStateException("Set apiBaseUrl in android-app/local.properties.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (supabaseApiKey != null) {
                connection.setRequestProperty("apikey", supabaseApiKey);
            }
            if (bearerToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode < 400 ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (statusCode < 200 || statusCode >= 300) {
                String message;
                try {
                    message = new JSONObject(response).optString("message",
                            new JSONObject(response).optString("msg", "Request failed (" + statusCode + ")."));
                } catch (Exception exception) {
                    message = "Request failed (" + statusCode + ").";
                }
                throw new ApiRequestException(statusCode, message);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private void requestAsync(ThrowingRunnable task, FailureHandler failure) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? "Request failed." : exception.getMessage();
                runOnUiThread(() -> failure.onFailure(message));
            }
        });
    }

    private JSONObject findSku(JSONArray items, String sku) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject product = items.optJSONObject(i);
            if (product != null && sku.equalsIgnoreCase(product.optString("sku"))) {
                return product;
            }
        }
        return null;
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private String money(String value) {
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception exception) {
            return value;
        }
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private LinearLayout page() {
        pageScroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        pageScroll.addView(root);
        return root;
    }

    private TextView addText(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(0, 8, 0, 8);
        parent.addView(view);
        return view;
    }

    private void addTitle(LinearLayout parent, String text) {
        TextView title = addText(parent, text);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setHint(hint);
        editText.setInputType(inputType);
        return editText;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface FailureHandler {
        void onFailure(String message);
    }

    private static final class CartLine {
        private final JSONObject product;
        private final int quantity;

        private CartLine(JSONObject product, int quantity) {
            this.product = product;
            this.quantity = quantity;
        }
    }

    private static class ApiRequestException extends Exception {
        private final int statusCode;

        private ApiRequestException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
