package com.servibox.backend.registration;

import com.servibox.backend.registration.dto.PlatformRegistrationRequestResponse;
import com.servibox.backend.registration.dto.RegistrationRequestPage;
import com.servibox.backend.registration.dto.ReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Revision de solicitudes por la plataforma. El principal es el id del PlatformAdmin; no hay TenantContext. */
@RestController
@RequestMapping("/api/platform/registration-requests")
@RequiredArgsConstructor
public class PlatformRegistrationRequestController {

    private final RegistrationRequestService service;
    private final RegistrationActivationService activationService;

    @GetMapping
    public RegistrationRequestPage list(
            @RequestParam(required = false) RegistrationRequestStatus status,
            @RequestParam(required = false) String tenantName,
            @RequestParam(required = false) String contactName,
            @RequestParam(required = false) String receivedFrom,
            @RequestParam(required = false) String receivedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, tenantName, contactName, receivedFrom, receivedTo, page, size);
    }

    @GetMapping("/{id}")
    public PlatformRegistrationRequestResponse detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/approve")
    public PlatformRegistrationRequestResponse approve(@PathVariable String id, @RequestBody ReviewRequest body,
            Authentication authentication) {
        return service.approve(id, body.expectedVersion(), (Long) authentication.getPrincipal());
    }

    @PostMapping("/{id}/reject")
    public PlatformRegistrationRequestResponse reject(@PathVariable String id, @RequestBody ReviewRequest body,
            Authentication authentication) {
        return service.reject(id, body.expectedVersion(), body.reason(), (Long) authentication.getPrincipal());
    }

    /** Revoca el enlace vigente y encola uno nuevo, auditado con el administrador. No devuelve enlace ni token. */
    @PostMapping("/{id}/resend-activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlatformRegistrationRequestResponse resendActivation(@PathVariable String id,
            Authentication authentication) {
        activationService.resend(id, (Long) authentication.getPrincipal());
        return service.detail(id);
    }
}
