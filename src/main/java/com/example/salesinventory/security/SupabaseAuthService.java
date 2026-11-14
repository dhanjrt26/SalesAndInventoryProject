package com.example.salesinventory.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import com.example.salesinventory.api.ApiException;

@Service
public class SupabaseAuthService {
    private final RestClient restClient;
    private final String anonKey;

    public SupabaseAuthService(
            RestClient.Builder restClientBuilder,
            @Value("${app.supabase.url}") String supabaseUrl,
            @Value("${app.supabase.anon-key:}") String anonKey) {
        this.restClient = restClientBuilder.baseUrl(supabaseUrl).build();
        this.anonKey = anonKey;
    }

    public AuthSession login(String username, String password) {
        return request("token?grant_type=password",
                Map.of("email", username, "password", password));
    }

    public AuthSession refresh(String refreshToken) {
        return request("token?grant_type=refresh_token",
                Map.of("refresh_token", refreshToken));
    }

    private AuthSession request(String path, Map<String, String> body) {
        if (anonKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Authentication is not configured. Set SUPABASE_ANON_KEY on the server.");
        }
        try {
            AuthSession session = restClient.post()
                    .uri("/auth/v1/" + path)
                    .header("apikey", anonKey)
                    .body(body)
                    .retrieve()
                    .body(AuthSession.class);
            if (session == null || session.accessToken() == null || session.refreshToken() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Supabase returned an incomplete authentication response.");
            }
            return session;
        } catch (RestClientResponseException exception) {
            HttpStatus status = exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                    ? HttpStatus.TOO_MANY_REQUESTS
                    : HttpStatus.UNAUTHORIZED;
            String message = status == HttpStatus.TOO_MANY_REQUESTS
                    ? "Too many sign-in attempts. Please wait and try again."
                    : "Invalid username or password.";
            throw new ApiException(status, message);
        } catch (ResourceAccessException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Supabase Auth. Check the server connection and try again.");
        }
    }

    public record AuthSession(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken,
            @com.fasterxml.jackson.annotation.JsonProperty("token_type") String tokenType,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") Long expiresIn) {
    }

    public record LoginRequest(
            @jakarta.validation.constraints.NotBlank String username,
            @jakarta.validation.constraints.NotBlank String password) {
    }

    public record RefreshRequest(
            @jakarta.validation.constraints.NotBlank
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken) {
    }
}
