package com.example.salesinventory.purchase;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PurchaseOrderRequest(
        @NotNull UUID supplierId,
        @NotEmpty List<@Valid Item> items,
        String notes) {

    public record Item(
            @NotNull UUID productId,
            @Min(1) int quantity,
            @NotNull @DecimalMin("0.00") BigDecimal unitCost) {
    }
}
