package com.example.salesinventory.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.salesinventory.security.ActorContext;

class SalesServiceTest {

    @Test
    void returnsGrossProfitForCurrentDayWeekAndMonth() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of(
                "today_gross_profit", new BigDecimal("10.00"),
                "weekly_gross_profit", new BigDecimal("25.00"),
                "monthly_gross_profit", new BigDecimal("80.00")));

        SalesService service = new SalesService(jdbc, mock(ActorContext.class));

        assertThat(service.dashboardProfit()).containsEntry("today_gross_profit", new BigDecimal("10.00"))
                .containsEntry("weekly_gross_profit", new BigDecimal("25.00"))
                .containsEntry("monthly_gross_profit", new BigDecimal("80.00"));
    }

    @Test
    void groupsPurchasedItemsUnderTheirCustomerSale() {
        UUID saleId = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(Instant.parse("2026-11-12T10:15:30Z"));
        List<Map<String, Object>> rows = List.of(
                saleRow(saleId, createdAt, "Rice", "RICE-01", 2, "50.00", "100.00"),
                saleRow(saleId, createdAt, "Milk", "MILK-01", 1, "35.00", "35.00"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "totalCount", 1L,
                "salesTotal", new BigDecimal("135.00"),
                "averageSale", new BigDecimal("135.00"),
                "grossProfit", new BigDecimal("45.00")));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

        SalesService service = new SalesService(jdbc, mock(ActorContext.class));

        SalesService.SalesHistoryPage result = service.salesHistory(null, null, 50, 0);

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.sales()).hasSize(1);
        assertThat(result.sales().get(0).customerName()).isEqualTo("Customer A");
        assertThat(result.sales().get(0).receiptNumber()).isEqualTo("S-20261112-ABC12345");
        assertThat(result.sales().get(0).items())
                .extracting(SalesService.SaleHistoryItem::productName)
                .containsExactly("Rice", "Milk");
        assertThat(result.salesTotal()).isEqualByComparingTo("135.00");
        assertThat(result.averageSale()).isEqualByComparingTo("135.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("45.00");
    }

    private static Map<String, Object> saleRow(
            UUID saleId,
            Timestamp createdAt,
            String productName,
            String sku,
            int quantity,
            String unitPrice,
            String lineTotal) {
        return Map.of(
                "id", saleId,
                "receiptNumber", "S-20261112-ABC12345",
                "customerName", "Customer A",
                "createdAt", createdAt,
                "total", new BigDecimal("135.00"),
                "productName", productName,
                "sku", sku,
                "quantity", quantity,
                "unitPrice", new BigDecimal(unitPrice),
                "lineTotal", new BigDecimal(lineTotal));
    }
}
