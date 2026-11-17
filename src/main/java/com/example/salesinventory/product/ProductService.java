package com.example.salesinventory.product;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.salesinventory.api.ApiException;
import com.example.salesinventory.security.ActorContext;

@Service
public class ProductService {
    private static final RowMapper<ProductResponse> PRODUCT_MAPPER = ProductService::mapProduct;

    private final JdbcTemplate jdbc;
    private final ActorContext actorContext;

    public ProductService(JdbcTemplate jdbc, ActorContext actorContext) {
        this.jdbc = jdbc;
        this.actorContext = actorContext;
    }

    public List<ProductResponse> list(String query, int limit, int offset) {
        String search = query == null ? "" : query.trim();
        return jdbc.query("""
                SELECT p.* FROM products p
                WHERE (? = '' OR p.name ILIKE '%' || ? || '%'
                    OR p.sku ILIKE '%' || ? || '%'
                    OR p.barcode ILIKE '%' || ? || '%'
                    OR EXISTS (SELECT 1 FROM product_barcodes b
                               WHERE b.product_id = p.id AND b.barcode ILIKE '%' || ? || '%'))
                ORDER BY p.name
                LIMIT ? OFFSET ?
                """, PRODUCT_MAPPER, search, search, search, search, search, limit, offset);
    }

    public ProductResponse get(UUID id) {
        return jdbc.query("""
                SELECT * FROM products WHERE id = ?
                """, PRODUCT_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found."));
    }

    public ProductResponse getByBarcode(String barcode) {
        return jdbc.query("""
                SELECT p.* FROM products p
                WHERE p.status = 'ACTIVE' AND (p.barcode = ? OR EXISTS (
                    SELECT 1 FROM product_barcodes b
                    WHERE b.product_id = p.id AND b.barcode = ?))
                """, PRODUCT_MAPPER, barcode, barcode).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No product matches that barcode."));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        UUID id = UUID.randomUUID();
        UUID actorId = actorContext.userId();
        int stock = valueOrZero(request.stockQty());
        int minStock = valueOrZero(request.minStock());
        String status = Boolean.FALSE.equals(request.active()) ? "INACTIVE" : "ACTIVE";
        jdbc.update("""
                INSERT INTO products
                    (id, barcode, sku, name, description, category_id, brand_id,
                     cost_price, selling_price, stock_qty, min_stock, image,
                     expiration_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, blankToNull(request.barcode()), request.sku().trim(), request.name().trim(),
                request.description(), request.categoryId(), request.brandId(),
                request.costPrice(), request.sellingPrice(), stock, minStock,
                request.image(), request.expirationDate(), status);
        if (stock > 0) {
            jdbc.update("""
                    INSERT INTO inventory_transactions
                        (product_id, transaction_type, quantity_change, quantity_after, reason, performed_by)
                    VALUES (?, 'ADJUSTMENT', ?, ?, 'Initial stock', ?)
                    """, id, stock, stock, actorId);
        }
        jdbc.update("""
                INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'PRODUCT_CREATED', 'PRODUCT', ?,
                        jsonb_build_object('sellingPrice', ?::numeric, 'initialStock', ?::integer))
                """, actorId, id, request.sellingPrice(), stock);
        return get(id);
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        ProductResponse current = getForUpdate(id);
        if (request.stockQty() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Use the inventory adjustment endpoint to change stock.");
        }
        String status = Boolean.FALSE.equals(request.active()) ? "INACTIVE" : "ACTIVE";
        int changed = jdbc.update("""
                UPDATE products SET barcode = ?, sku = ?, name = ?, description = ?,
                    category_id = ?, brand_id = ?, cost_price = ?, selling_price = ?,
                    min_stock = ?, image = ?, expiration_date = ?, status = ?, updated_at = now()
                WHERE id = ?
                """,
                blankToNull(request.barcode()), request.sku().trim(), request.name().trim(),
                request.description(), request.categoryId(), request.brandId(),
                request.costPrice(), request.sellingPrice(), valueOrZero(request.minStock()),
                request.image(), request.expirationDate(), status, id);
        if (changed != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Product not found.");
        }
        if (current.sellingPrice().compareTo(request.sellingPrice()) != 0) {
            recordSellingPriceChange(id, current.sellingPrice(), request.sellingPrice(),
                    "Product details updated");
        }
        return get(id);
    }

    @Transactional
    public ProductResponse adjustSellingPrice(UUID id, BigDecimal sellingPrice, String reason) {
        if (sellingPrice == null || sellingPrice.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Selling price must be zero or greater.");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason of up to 500 characters is required.");
        }
        ProductResponse current = getForUpdate(id);
        if (current.sellingPrice().compareTo(sellingPrice) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The new selling price matches the current price.");
        }
        jdbc.update("""
                UPDATE products SET selling_price = ?, updated_at = now()
                WHERE id = ?
                """, sellingPrice, id);
        recordSellingPriceChange(id, current.sellingPrice(), sellingPrice, reason.trim());
        return get(id);
    }

    public List<Map<String, Object>> history(UUID productId, int limit, int offset) {
        get(productId);
        return jdbc.queryForList("""
                SELECT h.id, h.event_type AS "eventType", h.created_at AS "createdAt",
                       COALESCE(u.display_name, 'Unknown user') AS "changedBy",
                       h.reason, h.old_selling_price AS "oldSellingPrice",
                       h.new_selling_price AS "newSellingPrice",
                       h.quantity_change AS "quantityChange", h.quantity_after AS "quantityAfter"
                FROM (
                    SELECT id, 'PRODUCT_CREATED'::text AS event_type, actor_id, created_at,
                           CASE WHEN details->>'legacy' = 'true' THEN details->>'note' END AS reason,
                           NULL::numeric AS old_selling_price,
                           NULLIF(details->>'sellingPrice', '')::numeric AS new_selling_price,
                           NULL::integer AS quantity_change,
                           NULLIF(details->>'initialStock', '')::integer AS quantity_after
                    FROM audit_logs
                    WHERE entity_type = 'PRODUCT' AND entity_id = ? AND action = 'PRODUCT_CREATED'
                    UNION ALL
                    SELECT id, 'PRICE_CHANGED'::text, actor_id, created_at,
                           details->>'reason',
                           (details->>'oldSellingPrice')::numeric,
                           (details->>'newSellingPrice')::numeric,
                           NULL::integer, NULL::integer
                    FROM audit_logs
                    WHERE entity_type = 'PRODUCT' AND entity_id = ? AND action = 'PRODUCT_PRICE_CHANGED'
                    UNION ALL
                    SELECT id,
                           CASE transaction_type
                               WHEN 'ADJUSTMENT' THEN 'STOCK_ADJUSTMENT'
                               WHEN 'SALE' THEN 'STOCK_SALE'
                               WHEN 'PURCHASE' THEN 'STOCK_PURCHASE'
                               WHEN 'RETURN' THEN 'STOCK_RETURN'
                           END::text,
                           performed_by, created_at, reason,
                           NULL::numeric, NULL::numeric, quantity_change, quantity_after
                    FROM inventory_transactions
                    WHERE product_id = ?
                ) h
                LEFT JOIN app_users u ON u.auth_user_id = h.actor_id
                ORDER BY h.created_at DESC, h.id DESC
                LIMIT ? OFFSET ?
                """, productId, productId, productId, limit, offset);
    }

    @Transactional
    public void addBarcode(UUID productId, String barcode) {
        get(productId);
        if (barcode == null || barcode.isBlank() || barcode.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Barcode must contain 1 to 64 characters.");
        }
        jdbc.update("INSERT INTO product_barcodes (product_id, barcode) VALUES (?, ?)",
                productId, barcode.trim());
    }

    private static ProductResponse mapProduct(ResultSet rs, int row) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        java.sql.Date expiration = rs.getDate("expiration_date");
        return new ProductResponse(
                rs.getObject("id", UUID.class),
                rs.getString("barcode"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("category_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("selling_price"),
                rs.getInt("stock_qty"),
                rs.getInt("min_stock"),
                rs.getString("image"),
                expiration == null ? null : expiration.toLocalDate(),
                rs.getString("status"),
                toInstant(created),
                toInstant(updated));
    }

    private void recordSellingPriceChange(
            UUID productId, BigDecimal oldPrice, BigDecimal newPrice, String reason) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'PRODUCT_PRICE_CHANGED', 'PRODUCT', ?,
                        jsonb_build_object('oldSellingPrice', ?::numeric,
                                           'newSellingPrice', ?::numeric, 'reason', ?))
                """, actorContext.userId(), productId, oldPrice, newPrice, reason);
    }

    private ProductResponse getForUpdate(UUID id) {
        return jdbc.query("""
                SELECT * FROM products WHERE id = ? FOR UPDATE
                """, PRODUCT_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Product not found."));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
