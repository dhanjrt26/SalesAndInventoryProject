package com.example.salesinventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void mapsActiveAppUserRolesToSpringRoles() {
        UUID userId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(userId)))
                .thenReturn(List.of("ADMIN", "CASHIER"));
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .build();

        assertThat(securityConfig.jwtAuthenticationConverter(jdbc).convert(token).getAuthorities())
                .extracting("authority")
                .contains("ROLE_ADMIN", "ROLE_CASHIER");
    }

    @Test
    void doesNotGrantRolesFromJwtMetadataWhenUserHasNoDatabaseRole() {
        UUID userId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(userId)))
                .thenReturn(List.of());
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("user_metadata", Map.of("roles", List.of("ADMIN")))
                .claim("app_metadata", Map.of("roles", List.of("ADMIN")))
                .build();

        assertThat(securityConfig.jwtAuthenticationConverter(jdbc).convert(token).getAuthorities())
                .extracting("authority")
                .doesNotContain("ROLE_ADMIN");
    }
}
