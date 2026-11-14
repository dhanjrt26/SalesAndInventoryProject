package com.example.salesinventory.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping("/{productId}/adjustments")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public Map<String, Object> adjust(
            @PathVariable UUID productId,
            @Valid @RequestBody AdjustmentRequest request) {
        return inventory.adjust(productId, request.quantityChange(), request.reason());
    }

    @GetMapping("/{productId}/transactions")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public List<Map<String, Object>> transactions(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return inventory.transactions(productId, limit, offset);
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER', 'INVENTORY_MANAGER')")
    public List<Map<String, Object>> stockAlerts(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return inventory.stockAlerts(limit);
    }

    public record AdjustmentRequest(@NotNull Integer quantityChange, @NotBlank String reason) {
    }
}
