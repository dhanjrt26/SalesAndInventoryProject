package com.example.salesinventory.purchase;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
public class PurchaseOrderService {
    private final JdbcTemplate jdbc;
    private final ActorContext actorContext;

    public PurchaseOrderService(JdbcTemplate jdbc, ActorContext actorContext) {
        this.jdbc = jdbc;
        this.actorContext = actorContext;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT po.id, po.order_number AS "orderNumber", po.supplier_id AS "supplierId",
                       s.name AS "supplierName", po.status, po.subtotal, po.notes,
                       po.ordered_at AS "orderedAt", po.received_at AS "receivedAt",
                       po.created_at AS "createdAt"
                FROM purchase_orders po
                JOIN suppliers s ON s.id = po.supplier_id
                ORDER BY po.created_at DESC LIMIT 100
                """);
    }

    public Map<String, Object> get(UUID id) {
        return detail(id);
    }

    @Transactional
    public Map<String, Object> create(PurchaseOrderRequest request) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM suppliers WHERE id = ? AND active)",
                Boolean.class, request.supplierId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Supplier not found.");
        }
        for (PurchaseOrderRequest.Item item : request.items()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM products WHERE id = ? AND status = 'ACTIVE')",
                    Boolean.class, item.productId()))) {
                throw new ApiException(HttpStatus.NOT_FOUND, "An active purchase order product was not found.");
            }
        }
        UUID id = UUID.randomUUID();
        String orderNumber = "PO-" + id.toString().substring(0, 12).toUpperCase();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchaseOrderRequest.Item item : request.items()) {
            subtotal = subtotal.add(item.unitCost().multiply(BigDecimal.valueOf(item.quantity())));
        }
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, order_number, supplier_id, status, subtotal, notes, created_by)
                VALUES (?, ?, ?, 'DRAFT', ?, ?, ?)
                """, id, orderNumber, request.supplierId(), subtotal, request.notes(), actorContext.userId());
        for (PurchaseOrderRequest.Item item : request.items()) {
            jdbc.update("""
                    INSERT INTO purchase_order_items
                        (purchase_order_id, product_id, quantity, unit_cost)
                    VALUES (?, ?, ?, ?)
                    """, id, item.productId(), item.quantity(), item.unitCost());
        }
        return detail(id);
    }

    @Transactional
    public Map<String, Object> markOrdered(UUID id) {
        int changed = jdbc.update("""
                UPDATE purchase_orders SET status = 'ORDERED', ordered_at = now(), updated_at = now()
                WHERE id = ? AND status = 'DRAFT'
                """, id);
        if (changed == 0) {
            requireOrder(id);
            throw new ApiException(HttpStatus.CONFLICT, "Only draft purchase orders can be ordered.");
        }
        return detail(id);
    }

    @Transactional
    public Map<String, Object> receive(UUID id) {
        Map<String, Object> order = jdbc.query("""
                SELECT status FROM purchase_orders WHERE id = ? FOR UPDATE
                """, rs -> rs.next() ? Map.of("status", rs.getString("status")) : null, id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Purchase order not found.");
        }
        if (!"ORDERED".equals(order.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Only ordered purchase orders can be received.");
        }
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT product_id, quantity, unit_cost
                FROM purchase_order_items WHERE purchase_order_id = ?
                """, id);
        UUID actorId = actorContext.userId();
        for (Map<String, Object> item : items) {
            UUID productId = (UUID) item.get("product_id");
            int quantity = ((Number) item.get("quantity")).intValue();
            BigDecimal unitCost = (BigDecimal) item.get("unit_cost");
            Integer stock = jdbc.query("""
                    SELECT stock_qty FROM products WHERE id = ? FOR UPDATE
                    """, rs -> rs.next() ? rs.getInt(1) : null, productId);
            if (stock == null) {
                throw new ApiException(HttpStatus.CONFLICT, "A purchase order product no longer exists.");
            }
            int nextStock;
            try {
                nextStock = Math.addExact(stock, quantity);
            } catch (ArithmeticException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "Received quantity exceeds the stock limit.");
            }
            jdbc.update("""
                    UPDATE products SET stock_qty = ?, cost_price = ?, updated_at = now()
                    WHERE id = ?
                    """, nextStock, unitCost, productId);
            jdbc.update("""
                    UPDATE purchase_order_items SET received_quantity = quantity
                    WHERE purchase_order_id = ? AND product_id = ?
                    """, id, productId);
            jdbc.update("""
                    INSERT INTO inventory_transactions
                        (product_id, transaction_type, quantity_change, quantity_after,
                         reference_id, reason, performed_by)
                    VALUES (?, 'PURCHASE', ?, ?, ?, 'Purchase order received', ?)
                    """, productId, quantity, nextStock, id, actorId);
        }
        jdbc.update("""
                UPDATE purchase_orders SET status = 'RECEIVED', received_at = now(), updated_at = now()
                WHERE id = ?
                """, id);
        return detail(id);
    }

    private Map<String, Object> detail(UUID id) {
        Map<String, Object> header = jdbc.query("""
                SELECT po.id, po.order_number AS "orderNumber", po.supplier_id AS "supplierId",
                       s.name AS "supplierName", po.status, po.subtotal, po.notes,
                       po.ordered_at AS "orderedAt", po.received_at AS "receivedAt",
                       po.created_at AS "createdAt"
                FROM purchase_orders po JOIN suppliers s ON s.id = po.supplier_id
                WHERE po.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Purchase order not found.");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getObject("id", UUID.class));
            result.put("orderNumber", rs.getString("orderNumber"));
            result.put("supplierId", rs.getObject("supplierId", UUID.class));
            result.put("supplierName", rs.getString("supplierName"));
            result.put("status", rs.getString("status"));
            result.put("subtotal", rs.getBigDecimal("subtotal"));
            result.put("notes", rs.getString("notes"));
            result.put("orderedAt", nullableInstant(rs.getTimestamp("orderedAt")));
            result.put("receivedAt", nullableInstant(rs.getTimestamp("receivedAt")));
            result.put("createdAt", nullableInstant(rs.getTimestamp("createdAt")));
            return result;
        }, id);
        header.put("items", jdbc.queryForList("""
                SELECT poi.id, poi.product_id AS "productId", p.name AS "productName",
                       poi.quantity, poi.unit_cost AS "unitCost",
                       poi.received_quantity AS "receivedQuantity"
                FROM purchase_order_items poi
                JOIN products p ON p.id = poi.product_id
                WHERE poi.purchase_order_id = ?
                ORDER BY p.name
                """, id));
        return header;
    }

    private void requireOrder(UUID id) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM purchase_orders WHERE id = ?)",
                Boolean.class, id);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Purchase order not found.");
        }
    }

    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
