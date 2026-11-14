package com.example.salesinventory.sales;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CheckoutRequest(
        UUID customerId,
        @NotEmpty List<@Valid LineItem> items,
        @NotNull @Pattern(regexp = "CASH|CARD|EWALLET") String paymentMethod,
        @Min(0) long amountTenderedInCents,
        String paymentReference) {

    public record LineItem(@NotNull UUID productId, @Min(1) int quantity) {
    }
}
