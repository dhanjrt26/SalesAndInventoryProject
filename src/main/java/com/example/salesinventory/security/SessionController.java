package com.example.salesinventory.security;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {
    private final SupabaseAuthService authService;

    public SessionController(SupabaseAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public SupabaseAuthService.AuthSession login(
            @Valid @RequestBody SupabaseAuthService.LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    public SupabaseAuthService.AuthSession refresh(
            @Valid @RequestBody SupabaseAuthService.RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }
}
