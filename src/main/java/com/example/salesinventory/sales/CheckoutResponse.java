package com.example.salesinventory.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutResponse(
        UUID saleId,
        String receiptNumber,
        BigDecimal subtotal,
        BigDecimal total,
        BigDecimal amountTendered,
        BigDecimal changeDue,
        String paymentMethod,
        Instant createdAt,
        List<ReceiptItem> items) {

    public record ReceiptItem(String productName, String sku, int quantity,
            BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
