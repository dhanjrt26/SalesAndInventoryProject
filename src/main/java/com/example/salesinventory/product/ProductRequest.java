package com.example.salesinventory.product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductRequest(
        @Size(max = 64) String barcode,
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 180) String name,
        String description,
        UUID categoryId,
        UUID brandId,
        @NotNull @DecimalMin("0.00") BigDecimal costPrice,
        @NotNull @DecimalMin("0.00") BigDecimal sellingPrice,
        @Min(0) Integer stockQty,
        @Min(0) Integer minStock,
        String image,
        LocalDate expirationDate,
        Boolean active) {
}
