package com.example.salesinventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.example.salesinventory.security.ActorContext;

class ProductServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void sellingPriceAdjustmentAuditsActorAndOldAndNewPrices() {
        UUID productId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        BigDecimal oldPrice = new BigDecimal("10.00");
        BigDecimal newPrice = new BigDecimal("12.50");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActorContext actorContext = mock(ActorContext.class);
        when(actorContext.userId()).thenReturn(actorId);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(product(productId, oldPrice)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ProductService service = new ProductService(jdbc, actorContext);
        service.adjustSellingPrice(productId, newPrice, "Supplier increase");

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("PRODUCT_PRICE_CHANGED"), parameters.capture());
        assertThat(parameters.getValue())
                .containsExactly(actorId, productId, oldPrice, newPrice, "Supplier increase");
    }

    @Test
    @SuppressWarnings("unchecked")
    void productHistoryIncludesCreationPriceChangesAndStockTransactions() {
        UUID productId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(product(productId, new BigDecimal("10.00"))));
        List<Map<String, Object>> expected = List.of(Map.of("eventType", "PRICE_CHANGED"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(expected);

        ProductService service = new ProductService(jdbc, mock(ActorContext.class));
        List<Map<String, Object>> result = service.history(productId, 25, 0);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(query.capture(), parameters.capture());
        assertThat(query.getValue()).contains("PRODUCT_CREATED", "PRODUCT_PRICE_CHANGED", "inventory_transactions");
        assertThat(parameters.getValue()).containsExactly(productId, productId, productId, 25, 0);
    }

    private ProductResponse product(UUID id, BigDecimal sellingPrice) {
        return new ProductResponse(id, "12345", "SKU-1", "Rice", null, null, null,
                new BigDecimal("8.00"), sellingPrice, 4, 1, null, null, "ACTIVE", null, null);
    }
}
