package com.example.salesinventory.directory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.salesinventory.api.ApiException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class DirectoryController {
    private final JdbcTemplate jdbc;

    public DirectoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("""
                SELECT id, name, description, active, created_at AS "createdAt"
                FROM categories WHERE active ORDER BY name
                """);
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public Map<String, Object> createCategory(@Valid @RequestBody CategoryRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name, description) VALUES (?, ?, ?)",
                id, request.name().trim(), request.description());
        return jdbc.queryForMap("""
                SELECT id, name, description, active, created_at AS "createdAt"
                FROM categories WHERE id = ?
                """, id);
    }

    @GetMapping("/brands")
    public List<Map<String, Object>> brands() {
        return jdbc.queryForList("""
                SELECT id, name, active, created_at AS "createdAt"
                FROM brands WHERE active ORDER BY name
                """);
    }

    @PostMapping("/brands")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public Map<String, Object> createBrand(@Valid @RequestBody BrandRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO brands (id, name) VALUES (?, ?)", id, request.name().trim());
        return jdbc.queryForMap("""
                SELECT id, name, active, created_at AS "createdAt"
                FROM brands WHERE id = ?
                """, id);
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER')")
    public List<Map<String, Object>> customers(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.trim();
        return jdbc.queryForList("""
                SELECT id, name, phone, email, loyalty_points AS "loyaltyPoints",
                       active, created_at AS "createdAt"
                FROM customers
                WHERE active AND (? = '' OR name ILIKE '%' || ? || '%' OR phone ILIKE '%' || ? || '%')
                ORDER BY name LIMIT 100
                """, query, query, query);
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER')")
    public Map<String, Object> createCustomer(@Valid @RequestBody CustomerRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, name, phone, email) VALUES (?, ?, ?, ?)",
                id, request.name().trim(), blankToNull(request.phone()), blankToNull(request.email()));
        return jdbc.queryForMap("""
                SELECT id, name, phone, email, loyalty_points AS "loyaltyPoints", active
                FROM customers WHERE id = ?
                """, id);
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CASHIER')")
    public Map<String, Object> updateCustomer(
            @PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        int rows = jdbc.update("""
                UPDATE customers SET name = ?, phone = ?, email = ?, updated_at = now()
                WHERE id = ? AND active
                """, request.name().trim(), blankToNull(request.phone()),
                blankToNull(request.email()), id);
        if (rows == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Customer not found.");
        }
        return jdbc.queryForMap("""
                SELECT id, name, phone, email, loyalty_points AS "loyaltyPoints", active
                FROM customers WHERE id = ?
                """, id);
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public List<Map<String, Object>> suppliers(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.trim();
        return jdbc.queryForList("""
                SELECT id, name, contact_name AS "contactName", phone, email, address, active
                FROM suppliers
                WHERE active AND (? = '' OR name ILIKE '%' || ? || '%')
                ORDER BY name LIMIT 100
                """, query, query);
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasAnyRole('ADMIN', 'INVENTORY_MANAGER')")
    public Map<String, Object> createSupplier(@Valid @RequestBody SupplierRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO suppliers (id, name, contact_name, phone, email, address)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, request.name().trim(), request.contactName(),
                blankToNull(request.phone()), blankToNull(request.email()), request.address());
        return jdbc.queryForMap("""
                SELECT id, name, contact_name AS "contactName", phone, email, address, active
                FROM suppliers WHERE id = ?
                """, id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CategoryRequest(@NotBlank @Size(max = 120) String name, String description) {
    }

    public record BrandRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record CustomerRequest(
            @NotBlank @Size(max = 180) String name,
            @Size(max = 40) String phone,
            @Email @Size(max = 254) String email) {
    }

    public record SupplierRequest(
            @NotBlank @Size(max = 180) String name,
            @Size(max = 160) String contactName,
            @Size(max = 40) String phone,
            @Email @Size(max = 254) String email,
            String address) {
    }
}
