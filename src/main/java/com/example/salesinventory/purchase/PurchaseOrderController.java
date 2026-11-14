package com.example.salesinventory.purchase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase-orders")
@PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
public class PurchaseOrderController {
    private final PurchaseOrderService purchaseOrders;

    public PurchaseOrderController(PurchaseOrderService purchaseOrders) {
        this.purchaseOrders = purchaseOrders;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return purchaseOrders.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return purchaseOrders.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody PurchaseOrderRequest request) {
        return purchaseOrders.create(request);
    }

    @PostMapping("/{id}/order")
    public Map<String, Object> markOrdered(@PathVariable UUID id) {
        return purchaseOrders.markOrdered(id);
    }

    @PostMapping("/{id}/receive")
    public Map<String, Object> receive(@PathVariable UUID id) {
        return purchaseOrders.receive(id);
    }
}
