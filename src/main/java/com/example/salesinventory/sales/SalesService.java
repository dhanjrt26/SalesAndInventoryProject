package com.example.salesinventory.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.salesinventory.api.ApiException;
import com.example.salesinventory.security.ActorContext;

@Service
public class SalesService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final ActorContext actorContext;

    public SalesService(JdbcTemplate jdbc, ActorContext actorContext) {
        this.jdbc = jdbc;
        this.actorContext = actorContext;
    }

    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request) {
        UUID actorId = actorContext.userId();
        if (request.customerId() != null && !customerExists(request.customerId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Customer not found.");
        }

        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        try {
            for (CheckoutRequest.LineItem item : request.items()) {
                quantities.merge(item.productId(), item.quantity(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Combined product quantity is out of range.");
        }

        List<CheckoutResponse.ReceiptItem> receiptItems = new ArrayList<>();
        List<LockedProduct> lockedProducts = new ArrayList<>();
        BigDecimal subtotal = ZERO;
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            LockedProduct product = lockProduct(entry.getKey());
            if (product == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "A product in the cart was not found or is inactive.");
            }
            int quantity = entry.getValue();
            if (product.stockQty() < quantity) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Insufficient stock for " + product.name() + ".");
            }
            BigDecimal lineTotal = product.sellingPrice()
                    .multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);
            lockedProducts.add(product.withQuantity(quantity));
            receiptItems.add(new CheckoutResponse.ReceiptItem(
                    product.name(), product.sku(), quantity, product.sellingPrice(), lineTotal));
        }

        BigDecimal total = subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tendered = BigDecimal.valueOf(request.amountTenderedInCents(), 2);
        boolean cash = "CASH".equals(request.paymentMethod());
        if ((cash && tendered.compareTo(total) < 0)
                || (!cash && tendered.compareTo(total) != 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, cash
                    ? "Cash tendered must be at least the sale total."
                    : "The payment amount must equal the sale total.");
        }
        if (request.paymentReference() != null && request.paymentReference().length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Payment reference cannot exceed 120 characters.");
        }

        UUID saleId = UUID.randomUUID();
        String receiptNumber = "S-" + LocalDate.now(ZoneOffset.UTC).format(RECEIPT_DATE)
                + "-" + saleId.toString().substring(0, 8).toUpperCase();
        Instant createdAt = Instant.now();
        jdbc.update("""
                INSERT INTO sales
                    (id, receipt_number, customer_id, cashier_id, subtotal, discount, total, status, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'COMPLETED', ?)
                """, saleId, receiptNumber, request.customerId(), actorId, subtotal, total,
                Timestamp.from(createdAt));

        for (LockedProduct product : lockedProducts) {
            int newStock = product.stockQty() - product.quantity();
            BigDecimal lineTotal = product.sellingPrice()
                    .multiply(BigDecimal.valueOf(product.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            jdbc.update("UPDATE products SET stock_qty = ?, updated_at = now() WHERE id = ?",
                    newStock, product.id());
            jdbc.update("""
                    INSERT INTO sale_items
                        (sale_id, product_id, product_name, sku, quantity, unit_price, unit_cost, line_total)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, saleId, product.id(), product.name(), product.sku(), product.quantity(),
                    product.sellingPrice(), product.costPrice(), lineTotal);
            jdbc.update("""
                    INSERT INTO inventory_transactions
                        (product_id, transaction_type, quantity_change, quantity_after,
                         reference_id, reason, performed_by)
                    VALUES (?, 'SALE', ?, ?, ?, ?, ?)
                    """, product.id(), -product.quantity(), newStock, saleId,
                    "Sale " + receiptNumber, actorId);
        }

        jdbc.update("""
                INSERT INTO payments (sale_id, method, amount, reference)
                VALUES (?, ?, ?, ?)
                """, saleId, request.paymentMethod(), total, request.paymentReference());
        jdbc.update("""
                INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'SALE_COMPLETED', 'SALE', ?,
                        jsonb_build_object('receiptNumber', ?, 'total', ?))
                """, actorId, saleId, receiptNumber, total);
        return new CheckoutResponse(saleId, receiptNumber, subtotal, total, tendered,
                cash ? tendered.subtract(total) : ZERO, request.paymentMethod(), createdAt, receiptItems);
    }

    public Map<String, Object> dashboard() {
        return jdbc.queryForMap("""
                SELECT
                    COALESCE((SELECT SUM(total) FROM sales
                              WHERE status = 'COMPLETED'
                                AND created_at >= date_trunc('day', now())), 0) AS today_sales,
                    COALESCE((SELECT SUM(total) FROM sales
                              WHERE status = 'COMPLETED'
                                AND created_at >= date_trunc('week', now())), 0) AS weekly_sales,
                    COALESCE((SELECT SUM(total) FROM sales
                              WHERE status = 'COMPLETED'
                                AND created_at >= date_trunc('month', now())), 0) AS monthly_sales,
                    COALESCE((SELECT SUM(cost_price * stock_qty) FROM products
                              WHERE status = 'ACTIVE'), 0) AS inventory_value,
                    (SELECT COUNT(*) FROM products
                     WHERE status = 'ACTIVE' AND stock_qty <= min_stock AND stock_qty > 0) AS low_stock_count,
                    (SELECT COUNT(*) FROM products
                     WHERE status = 'ACTIVE' AND stock_qty = 0) AS out_of_stock_count
                """);
    }

    public Map<String, Object> dashboardProfit() {
        return jdbc.queryForMap("""
                WITH sale_profit AS (
                    SELECT s.id, s.created_at,
                           SUM(si.line_total - si.unit_cost * si.quantity) AS gross_profit
                    FROM sales s
                    JOIN sale_items si ON si.sale_id = s.id
                    WHERE s.status = 'COMPLETED'
                    GROUP BY s.id, s.created_at
                )
                SELECT
                    COALESCE(SUM(gross_profit) FILTER (
                        WHERE created_at >= date_trunc('day', now())), 0) AS today_gross_profit,
                    COALESCE(SUM(gross_profit) FILTER (
                        WHERE created_at >= date_trunc('week', now())), 0) AS weekly_gross_profit,
                    COALESCE(SUM(gross_profit) FILTER (
                        WHERE created_at >= date_trunc('month', now())), 0) AS monthly_gross_profit
                FROM sale_profit
                """);
    }

    public SalesHistoryPage salesHistory(Instant from, Instant to, int limit, int offset) {
        if ((from == null) != (to == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Both 'from' and 'to' are required for a date filter.");
        }
        if (from != null && !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'.");
        }

        String dateFilter = from == null ? "" : " AND s.created_at >= ? AND s.created_at < ?";
        List<Object> summaryParameters = new ArrayList<>();
        if (from != null) {
            summaryParameters.add(Timestamp.from(from));
            summaryParameters.add(Timestamp.from(to));
        }
        Map<String, Object> summary = jdbc.queryForMap("""
                WITH filtered_sales AS (
                    SELECT s.id, s.total
                    FROM sales s
                    WHERE s.status = 'COMPLETED'
                """ + dateFilter + """
                )
                SELECT COUNT(*) AS "totalCount",
                       COALESCE(SUM(fs.total), 0) AS "salesTotal",
                       COALESCE(AVG(fs.total), 0) AS "averageSale",
                       COALESCE((
                           SELECT SUM(si.line_total - si.unit_cost * si.quantity)
                           FROM filtered_sales profit_sales
                           JOIN sale_items si ON si.sale_id = profit_sales.id
                       ), 0) AS "grossProfit"
                FROM filtered_sales fs
                """, summaryParameters.toArray());

        List<Object> historyParameters = new ArrayList<>(summaryParameters);
        historyParameters.add(limit);
        historyParameters.add(offset);
        String historySql = """
                WITH recent_sales AS (
                    SELECT s.id, s.receipt_number, s.created_at, s.total,
                           c.name AS customer_name
                    FROM sales s
                    LEFT JOIN customers c ON c.id = s.customer_id
                    WHERE s.status = 'COMPLETED'
                """ + dateFilter + """
                    ORDER BY s.created_at DESC, s.id DESC
                    LIMIT ? OFFSET ?
                )
                SELECT rs.id, rs.receipt_number AS "receiptNumber",
                       COALESCE(rs.customer_name, 'Walk-in customer') AS "customerName",
                       rs.created_at AS "createdAt", rs.total,
                       si.product_name AS "productName", si.sku, si.quantity,
                       si.unit_price AS "unitPrice", si.line_total AS "lineTotal"
                FROM recent_sales rs
                JOIN sale_items si ON si.sale_id = rs.id
                ORDER BY rs.created_at DESC, rs.id DESC, si.product_name, si.sku
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(historySql, historyParameters.toArray());

        Map<UUID, SaleHistoryBuilder> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID saleId = (UUID) row.get("id");
            SaleHistoryBuilder sale = grouped.computeIfAbsent(saleId, ignored -> new SaleHistoryBuilder(row));
            sale.items().add(new SaleHistoryItem(
                    (String) row.get("productName"),
                    (String) row.get("sku"),
                    ((Number) row.get("quantity")).intValue(),
                    (BigDecimal) row.get("unitPrice"),
                    (BigDecimal) row.get("lineTotal")));
        }
        return new SalesHistoryPage(
                ((Number) summary.get("totalCount")).longValue(),
                (BigDecimal) summary.get("salesTotal"),
                (BigDecimal) summary.get("averageSale"),
                (BigDecimal) summary.get("grossProfit"),
                grouped.values().stream().map(SaleHistoryBuilder::build).toList());
    }

    public List<Map<String, Object>> bestSellers(int limit) {
        return jdbc.queryForList("""
                SELECT si.product_id AS "productId", si.product_name AS "productName",
                       SUM(si.quantity) AS "quantitySold", SUM(si.line_total) AS revenue
                FROM sale_items si
                JOIN sales s ON s.id = si.sale_id
                WHERE s.status = 'COMPLETED' AND s.created_at >= date_trunc('month', now())
                GROUP BY si.product_id, si.product_name
                ORDER BY SUM(si.quantity) DESC, si.product_name
                LIMIT ?
                """, limit);
    }

    public ProductReport productReport(Instant from, Instant to, int limit, int offset) {
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'.");
        }

        Timestamp rangeStart = Timestamp.from(from);
        Timestamp rangeEnd = Timestamp.from(to);
        Map<String, Object> summary = jdbc.queryForMap("""
                WITH product_sales AS (
                    SELECT si.product_id,
                           SUM(si.quantity) AS units_sold,
                           SUM(si.line_total) AS revenue,
                           SUM(si.line_total - si.unit_cost * si.quantity) AS gross_profit
                    FROM sale_items si
                    JOIN sales s ON s.id = si.sale_id
                    WHERE s.status = 'COMPLETED'
                      AND s.created_at >= ? AND s.created_at < ?
                    GROUP BY si.product_id
                )
                SELECT
                    (SELECT COUNT(*) FROM products WHERE status = 'ACTIVE') AS product_count,
                    COALESCE((SELECT SUM(units_sold) FROM product_sales), 0) AS units_sold,
                    COALESCE((SELECT SUM(revenue) FROM product_sales), 0) AS revenue,
                    COALESCE((SELECT SUM(gross_profit) FROM product_sales), 0) AS gross_profit,
                    (SELECT COUNT(*) FROM sales
                     WHERE status = 'COMPLETED' AND created_at >= ? AND created_at < ?) AS completed_sales,
                    (SELECT COUNT(*) FROM sales
                     WHERE status = 'COMPLETED' AND customer_id IS NULL
                       AND created_at >= ? AND created_at < ?) AS walk_in_sales,
                    COALESCE((SELECT SUM(total) FROM sales
                              WHERE status = 'COMPLETED' AND customer_id IS NULL
                                AND created_at >= ? AND created_at < ?), 0) AS walk_in_revenue,
                    COALESCE((SELECT SUM(cost_price * stock_qty) FROM products
                              WHERE status = 'ACTIVE'), 0) AS inventory_value,
                    (SELECT COUNT(*) FROM products
                     WHERE status = 'ACTIVE' AND stock_qty <= min_stock AND stock_qty > 0) AS low_stock_count,
                    (SELECT COUNT(*) FROM products
                     WHERE status = 'ACTIVE' AND stock_qty = 0) AS out_of_stock_count
                """, rangeStart, rangeEnd, rangeStart, rangeEnd, rangeStart, rangeEnd, rangeStart, rangeEnd);
        long totalCount = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
        List<Map<String, Object>> products = jdbc.queryForList("""
                WITH product_sales AS (
                    SELECT si.product_id,
                           SUM(si.quantity) AS units_sold,
                           SUM(si.line_total) AS revenue,
                           SUM(si.line_total - si.unit_cost * si.quantity) AS gross_profit
                    FROM sale_items si
                    JOIN sales s ON s.id = si.sale_id
                    WHERE s.status = 'COMPLETED'
                      AND s.created_at >= ? AND s.created_at < ?
                    GROUP BY si.product_id
                )
                SELECT p.id AS "productId", p.name AS "productName", p.sku,
                       c.name AS "categoryName", p.stock_qty AS "stockQty",
                       p.min_stock AS "minStock", p.status,
                       COALESCE(ps.units_sold, 0) AS "unitsSold",
                       COALESCE(ps.revenue, 0) AS revenue,
                       COALESCE(ps.gross_profit, 0) AS "grossProfit",
                       CASE WHEN p.stock_qty = 0 THEN 'OUT_OF_STOCK'
                            WHEN p.stock_qty <= p.min_stock THEN 'LOW_STOCK'
                            ELSE 'IN_STOCK' END AS "stockStatus"
                FROM products p
                LEFT JOIN categories c ON c.id = p.category_id
                LEFT JOIN product_sales ps ON ps.product_id = p.id
                ORDER BY COALESCE(ps.revenue, 0) DESC, p.name, p.sku
                LIMIT ? OFFSET ?
                """, rangeStart, rangeEnd, limit, offset);

        List<Map<String, Object>> categories = jdbc.queryForList("""
                WITH product_sales AS (
                    SELECT si.product_id, SUM(si.quantity) AS units_sold,
                           SUM(si.line_total) AS revenue,
                           SUM(si.line_total - si.unit_cost * si.quantity) AS gross_profit
                    FROM sale_items si
                    JOIN sales s ON s.id = si.sale_id
                    WHERE s.status = 'COMPLETED' AND s.created_at >= ? AND s.created_at < ?
                    GROUP BY si.product_id
                )
                SELECT COALESCE(c.name, 'Uncategorized') AS "categoryName",
                       COUNT(*) AS "productCount", SUM(p.stock_qty) AS "stockQty",
                       COALESCE(SUM(ps.units_sold), 0) AS "unitsSold",
                       COALESCE(SUM(ps.revenue), 0) AS revenue,
                       COALESCE(SUM(ps.gross_profit), 0) AS "grossProfit"
                FROM products p
                LEFT JOIN categories c ON c.id = p.category_id
                LEFT JOIN product_sales ps ON ps.product_id = p.id
                WHERE p.status = 'ACTIVE'
                GROUP BY c.id, c.name
                ORDER BY revenue DESC, "categoryName"
                """, rangeStart, rangeEnd);
        List<Map<String, Object>> brands = jdbc.queryForList("""
                WITH product_sales AS (
                    SELECT si.product_id, SUM(si.quantity) AS units_sold,
                           SUM(si.line_total) AS revenue,
                           SUM(si.line_total - si.unit_cost * si.quantity) AS gross_profit
                    FROM sale_items si
                    JOIN sales s ON s.id = si.sale_id
                    WHERE s.status = 'COMPLETED' AND s.created_at >= ? AND s.created_at < ?
                    GROUP BY si.product_id
                )
                SELECT COALESCE(b.name, 'Unbranded') AS "brandName",
                       COUNT(*) AS "productCount", SUM(p.stock_qty) AS "stockQty",
                       COALESCE(SUM(ps.units_sold), 0) AS "unitsSold",
                       COALESCE(SUM(ps.revenue), 0) AS revenue,
                       COALESCE(SUM(ps.gross_profit), 0) AS "grossProfit"
                FROM products p
                LEFT JOIN brands b ON b.id = p.brand_id
                LEFT JOIN product_sales ps ON ps.product_id = p.id
                WHERE p.status = 'ACTIVE'
                GROUP BY b.id, b.name
                ORDER BY revenue DESC, "brandName"
                """, rangeStart, rangeEnd);
        List<Map<String, Object>> inventoryMovements = jdbc.queryForList("""
                SELECT transaction_type AS "transactionType",
                       COUNT(*) AS "transactionCount",
                       COALESCE(SUM(quantity_change) FILTER (WHERE quantity_change > 0), 0) AS "unitsAdded",
                       COALESCE(-SUM(quantity_change) FILTER (WHERE quantity_change < 0), 0) AS "unitsRemoved",
                       COALESCE(SUM(quantity_change), 0) AS "netChange"
                FROM inventory_transactions
                WHERE created_at >= ? AND created_at < ?
                GROUP BY transaction_type
                ORDER BY transaction_type
                """, rangeStart, rangeEnd);
        Map<String, Object> purchaseSummary = jdbc.queryForMap("""
                WITH order_totals AS (
                    SELECT po.id, po.supplier_id, po.status, po.subtotal,
                           COALESCE(SUM(poi.quantity), 0) AS units_ordered,
                           COALESCE(SUM(poi.received_quantity), 0) AS units_received,
                           COALESCE(SUM(poi.unit_cost * poi.received_quantity), 0) AS received_value
                    FROM purchase_orders po
                    LEFT JOIN purchase_order_items poi ON poi.purchase_order_id = po.id
                    WHERE po.created_at >= ? AND po.created_at < ?
                    GROUP BY po.id
                )
                SELECT COUNT(*) AS order_count,
                       COUNT(DISTINCT supplier_id) AS supplier_count,
                       COUNT(*) FILTER (WHERE status IN ('DRAFT', 'ORDERED')) AS open_order_count,
                       COALESCE(SUM(subtotal), 0) AS ordered_value,
                       COALESCE(SUM(units_ordered), 0) AS units_ordered,
                       COALESCE(SUM(units_received), 0) AS units_received,
                       COALESCE(SUM(received_value), 0) AS received_value
                FROM order_totals
                """, rangeStart, rangeEnd);
        List<Map<String, Object>> supplierPerformance = jdbc.queryForList("""
                WITH order_totals AS (
                    SELECT po.id, po.supplier_id, po.status, po.subtotal,
                           COALESCE(SUM(poi.units_ordered), 0) AS units_ordered,
                           COALESCE(SUM(poi.units_received), 0) AS units_received,
                           COALESCE(SUM(poi.received_value), 0) AS received_value
                    FROM purchase_orders po
                    LEFT JOIN (
                        SELECT purchase_order_id, SUM(quantity) AS units_ordered,
                               SUM(received_quantity) AS units_received,
                               SUM(unit_cost * received_quantity) AS received_value
                        FROM purchase_order_items
                        GROUP BY purchase_order_id
                    ) poi ON poi.purchase_order_id = po.id
                    WHERE po.created_at >= ? AND po.created_at < ?
                    GROUP BY po.id
                )
                SELECT s.name AS "supplierName", s.active,
                       COUNT(ot.id) AS "orderCount",
                       COALESCE(SUM(ot.subtotal), 0) AS "orderedValue",
                       COALESCE(SUM(ot.units_received), 0) AS "unitsReceived",
                       COALESCE(SUM(ot.received_value), 0) AS "receivedValue"
                FROM suppliers s
                LEFT JOIN order_totals ot ON ot.supplier_id = s.id
                GROUP BY s.id, s.name, s.active
                ORDER BY "orderedValue" DESC, s.name
                """, rangeStart, rangeEnd);
        List<Map<String, Object>> paymentMethods = jdbc.queryForList("""
                SELECT p.method AS "paymentMethod",
                       COUNT(*) AS "paymentCount",
                       COUNT(DISTINCT p.sale_id) AS "salesCount",
                       COALESCE(SUM(p.amount), 0) AS amount
                FROM payments p
                JOIN sales s ON s.id = p.sale_id
                WHERE s.status = 'COMPLETED' AND s.created_at >= ? AND s.created_at < ?
                GROUP BY p.method
                ORDER BY amount DESC, p.method
                """, rangeStart, rangeEnd);
        List<Map<String, Object>> customers = jdbc.queryForList("""
                WITH filtered_sales AS (
                    SELECT id, customer_id, total
                    FROM sales
                    WHERE status = 'COMPLETED' AND customer_id IS NOT NULL
                      AND created_at >= ? AND created_at < ?
                ), customer_units AS (
                    SELECT s.customer_id, SUM(si.quantity) AS units_sold
                    FROM sales s
                    JOIN sale_items si ON si.sale_id = s.id
                    WHERE s.status = 'COMPLETED' AND s.customer_id IS NOT NULL
                      AND s.created_at >= ? AND s.created_at < ?
                    GROUP BY s.customer_id
                )
                SELECT c.name AS "customerName",
                       COUNT(fs.id) AS "salesCount",
                       COALESCE(cu.units_sold, 0) AS "unitsPurchased",
                       COALESCE(SUM(fs.total), 0) AS "salesTotal"
                FROM filtered_sales fs
                JOIN customers c ON c.id = fs.customer_id
                LEFT JOIN customer_units cu ON cu.customer_id = c.id
                GROUP BY c.id, c.name, cu.units_sold
                ORDER BY "salesTotal" DESC, c.name
                """, rangeStart, rangeEnd, rangeStart, rangeEnd);

        return new ProductReport(
                totalCount, summary, products, categories, brands, inventoryMovements,
                purchaseSummary, supplierPerformance, paymentMethods, customers);
    }

    public List<Map<String, Object>> salesReport(Instant from, Instant to, int limit, int offset) {
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'.");
        }
        return jdbc.queryForList("""
                SELECT s.id, s.receipt_number AS "receiptNumber", s.customer_id AS "customerId",
                       s.cashier_id AS "cashierId", s.subtotal, s.discount, s.total, s.status,
                       s.created_at AS "createdAt",
                       COALESCE((SELECT string_agg(DISTINCT p.method, ',')
                                 FROM payments p WHERE p.sale_id = s.id), '') AS "paymentMethods"
                FROM sales s
                WHERE s.created_at >= ? AND s.created_at < ?
                ORDER BY s.created_at DESC
                LIMIT ? OFFSET ?
                """, Timestamp.from(from), Timestamp.from(to), limit, offset);
    }

    public Map<String, Object> profitReport(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'.");
        }
        return jdbc.queryForMap("""
                SELECT
                    COALESCE(SUM(si.line_total), 0) AS revenue,
                    COALESCE(SUM(si.unit_cost * si.quantity), 0) AS cost_of_goods,
                    COALESCE(SUM(si.line_total - si.unit_cost * si.quantity), 0) AS gross_profit,
                    COUNT(DISTINCT s.id) AS completed_sales
                FROM sales s
                JOIN sale_items si ON si.sale_id = s.id
                WHERE s.status = 'COMPLETED'
                  AND s.created_at >= ? AND s.created_at < ?
                """, Timestamp.from(from), Timestamp.from(to));
    }

    private boolean customerExists(UUID id) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM customers WHERE id = ? AND active)",
                Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    private LockedProduct lockProduct(UUID id) {
        return jdbc.query("""
                SELECT id, name, sku, selling_price, cost_price, stock_qty
                FROM products WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
                """, rs -> rs.next() ? new LockedProduct(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("selling_price"),
                        rs.getBigDecimal("cost_price"),
                        rs.getInt("stock_qty"),
                        0) : null, id);
    }

    private record LockedProduct(
            UUID id, String name, String sku, BigDecimal sellingPrice, BigDecimal costPrice,
            int stockQty, int quantity) {
        LockedProduct withQuantity(int value) {
            return new LockedProduct(id, name, sku, sellingPrice, costPrice, stockQty, value);
        }
    }

    public record SalesHistoryPage(
            long totalCount,
            BigDecimal salesTotal,
            BigDecimal averageSale,
            BigDecimal grossProfit,
            List<SaleHistory> sales) {
    }

    public record ProductReport(
            long totalCount,
            Map<String, Object> summary,
            List<Map<String, Object>> products,
            List<Map<String, Object>> categories,
            List<Map<String, Object>> brands,
            List<Map<String, Object>> inventoryMovements,
            Map<String, Object> purchaseSummary,
            List<Map<String, Object>> suppliers,
            List<Map<String, Object>> paymentMethods,
            List<Map<String, Object>> customers) {
    }

    public record SaleHistory(
            UUID id,
            String receiptNumber,
            String customerName,
            Instant createdAt,
            BigDecimal total,
            List<SaleHistoryItem> items) {
    }

    public record SaleHistoryItem(
            String productName,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {
    }

    private static final class SaleHistoryBuilder {
        private final UUID id;
        private final String receiptNumber;
        private final String customerName;
        private final Instant createdAt;
        private final BigDecimal total;
        private final List<SaleHistoryItem> items = new ArrayList<>();

        private SaleHistoryBuilder(Map<String, Object> row) {
            id = (UUID) row.get("id");
            receiptNumber = (String) row.get("receiptNumber");
            customerName = (String) row.get("customerName");
            createdAt = ((Timestamp) row.get("createdAt")).toInstant();
            total = (BigDecimal) row.get("total");
        }

        private List<SaleHistoryItem> items() {
            return items;
        }

        private SaleHistory build() {
            return new SaleHistory(id, receiptNumber, customerName, createdAt, total, List.copyOf(items));
        }
    }
}
