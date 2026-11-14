package com.example.salesinventory.security;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.example.salesinventory.api.ApiException;

@Component
public class ActorContext {

    public UUID userId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                return UUID.fromString(jwt.getToken().getSubject());
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "The access token subject must be a UUID.");
            }
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid access token is required.");
    }
}
