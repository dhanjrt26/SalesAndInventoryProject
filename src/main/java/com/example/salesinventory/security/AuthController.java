package com.example.salesinventory.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> currentUser(JwtAuthenticationToken authentication) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", authentication.getToken().getSubject());
        response.put("email", authentication.getToken().getClaimAsString("email"));
        response.put("roles", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList());
        response.put("expiresAt", authentication.getToken().getExpiresAt());
        return response;
    }
}
