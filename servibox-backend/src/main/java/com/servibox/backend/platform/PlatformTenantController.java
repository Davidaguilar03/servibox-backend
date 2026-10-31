package com.servibox.backend.platform;

import com.servibox.backend.platform.dto.PlatformMetricsResponse;
import com.servibox.backend.platform.dto.PlatformPage;
import com.servibox.backend.platform.dto.PlatformTenantActionRequest;
import com.servibox.backend.platform.dto.PlatformTenantDetailResponse;
import com.servibox.backend.platform.dto.PlatformTenantResponse;
import com.servibox.backend.platform.dto.PlatformTenantUserResponse;
import com.servibox.backend.platform.dto.TenantSessionsRevokedResponse;
import com.servibox.backend.tenant.TenantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Panel de plataforma sobre tenants y sus usuarios (5A). El principal es el id del PlatformAdmin; no hay TenantContext. */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final PlatformTenantService service;

    @GetMapping("/tenants")
    public PlatformPage<PlatformTenantResponse> list(
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, q, page, size);
    }

    @GetMapping("/tenants/{id}")
    public PlatformTenantDetailResponse detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @GetMapping("/tenants/{id}/users")
    public PlatformPage<PlatformTenantUserResponse> users(@PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.users(id, page, size);
    }

    @PostMapping("/tenants/{id}/suspend")
    public PlatformTenantDetailResponse suspend(@PathVariable Long id, @RequestBody PlatformTenantActionRequest body,
            Authentication authentication) {
        return service.suspend(id, body.expectedVersion(), body.reason(), (Long) authentication.getPrincipal());
    }

    @PostMapping("/tenants/{id}/reactivate")
    public PlatformTenantDetailResponse reactivate(@PathVariable Long id,
            @RequestBody PlatformTenantActionRequest body, Authentication authentication) {
        return service.reactivate(id, body.expectedVersion(), body.reason(), (Long) authentication.getPrincipal());
    }

    /** Cuerpo opcional: solo el motivo. */
    @PostMapping("/tenants/{id}/revoke-sessions")
    public TenantSessionsRevokedResponse revokeSessions(@PathVariable Long id,
            @RequestBody(required = false) PlatformTenantActionRequest body, Authentication authentication) {
        return service.revokeTenant(id, body == null ? null : body.reason(), (Long) authentication.getPrincipal());
    }

    /** Cuerpo opcional: solo el motivo. */
    @PostMapping("/tenants/{id}/users/{userId}/revoke")
    public PlatformTenantUserResponse revokeUser(@PathVariable Long id, @PathVariable Long userId,
            @RequestBody(required = false) PlatformTenantActionRequest body, Authentication authentication) {
        return service.revokeUser(id, userId, body == null ? null : body.reason(),
                (Long) authentication.getPrincipal());
    }

    @GetMapping("/metrics")
    public PlatformMetricsResponse metrics() {
        return service.metrics();
    }
}
