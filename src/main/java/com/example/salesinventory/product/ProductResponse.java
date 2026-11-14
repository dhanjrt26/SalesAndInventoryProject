package com.example.salesinventory.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String barcode,
        String sku,
        String name,
        String description,
        UUID categoryId,
        UUID brandId,
        BigDecimal costPrice,
        BigDecimal sellingPrice,
        int stockQty,
        int minStock,
        String image,
        LocalDate expirationDate,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
