package com.example.salesinventory.inventory;

import java.time.Instant;
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
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final ActorContext actorContext;

    public InventoryService(JdbcTemplate jdbc, ActorContext actorContext) {
        this.jdbc = jdbc;
        this.actorContext = actorContext;
    }

    @Transactional
    public Map<String, Object> adjust(UUID productId, int quantityChange, String reason) {
        if (quantityChange == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Adjustment quantity cannot be zero.");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason of up to 500 characters is required.");
        }
        Integer current = jdbc.query("""
                SELECT stock_qty FROM products WHERE id = ? FOR UPDATE
                """, rs -> rs.next() ? rs.getInt(1) : null, productId);
        if (current == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Product not found.");
        }
        int next;
        try {
            next = Math.addExact(current, quantityChange);
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Adjustment quantity is out of range.");
        }
        if (next < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Adjustment would result in negative stock.");
        }
        UUID actorId = actorContext.userId();
        jdbc.update("UPDATE products SET stock_qty = ?, updated_at = now() WHERE id = ?",
                next, productId);
        jdbc.update("""
                INSERT INTO inventory_transactions
                    (product_id, transaction_type, quantity_change, quantity_after, reason, performed_by)
                VALUES (?, 'ADJUSTMENT', ?, ?, ?, ?)
                """, productId, quantityChange, next, reason.trim(), actorId);
        return Map.of(
                "productId", productId,
                "quantityChange", quantityChange,
                "quantityAfter", next);
    }

    public List<Map<String, Object>> transactions(UUID productId, int limit, int offset) {
        return jdbc.queryForList("""
                SELECT id, transaction_type AS "transactionType",
                       quantity_change AS "quantityChange", quantity_after AS "quantityAfter",
                       reference_id AS "referenceId", reason, performed_by AS "performedBy",
                       created_at AS "createdAt"
                FROM inventory_transactions
                WHERE product_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, productId, limit, offset);
    }

    public List<Map<String, Object>> stockAlerts(int limit) {
        return jdbc.queryForList("""
                SELECT id AS "productId", name, sku, barcode, stock_qty AS "stockQty",
                       min_stock AS "minStock",
                       CASE WHEN stock_qty = 0 THEN 'OUT_OF_STOCK' ELSE 'LOW_STOCK' END AS status
                FROM products
                WHERE status = 'ACTIVE' AND stock_qty <= min_stock
                ORDER BY CASE WHEN stock_qty = 0 THEN 0 ELSE 1 END, name
                LIMIT ?
                """, limit);
    }
}
