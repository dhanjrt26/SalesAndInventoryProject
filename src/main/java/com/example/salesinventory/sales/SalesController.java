package com.example.salesinventory.sales;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class SalesController {
    private final SalesService sales;

    public SalesController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping("/sales/checkout")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER')")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        return sales.checkout(request);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER', 'INVENTORY_MANAGER')")
    public Map<String, Object> dashboard() {
        return sales.dashboard();
    }

    @GetMapping("/dashboard/profit")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> dashboardProfit() {
        return sales.dashboardProfit();
    }

    @GetMapping("/sales/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER')")
    public SalesService.SalesHistoryPage salesHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return sales.salesHistory(from, to, limit, offset);
    }

    @GetMapping("/reports/best-sellers")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public List<Map<String, Object>> bestSellers(
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return sales.bestSellers(limit);
    }

    @GetMapping("/reports/products")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public SalesService.ProductReport productReport(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return sales.productReport(from, to, limit, offset);
    }

    @GetMapping("/reports/sales")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER')")
    public List<Map<String, Object>> salesReport(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return sales.salesReport(from, to, limit, offset);
    }

    @GetMapping("/reports/profit")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> profitReport(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return sales.profitReport(from, to);
    }
}
