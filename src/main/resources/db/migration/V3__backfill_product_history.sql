CREATE INDEX IF NOT EXISTS audit_logs_product_history_idx
    ON audit_logs(entity_type, entity_id, created_at DESC);

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, details, created_at)
SELECT NULL, 'PRODUCT_CREATED', 'PRODUCT', id,
       jsonb_build_object(
           'legacy', TRUE,
           'note', 'Product predates product activity tracking; earlier changes are unavailable.'),
       created_at
FROM products p
WHERE NOT EXISTS (
    SELECT 1
    FROM audit_logs a
    WHERE a.entity_type = 'PRODUCT'
      AND a.entity_id = p.id
      AND a.action = 'PRODUCT_CREATED'
);
