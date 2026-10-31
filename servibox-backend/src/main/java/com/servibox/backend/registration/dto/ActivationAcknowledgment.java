package com.servibox.backend.registration.dto;

/** Acuse de activacion: sin JWT ni sesion. El cliente sigue al login con tenantSlug. */
public record ActivationAcknowledgment(String status, String tenantSlug, String message) {

    public static ActivationAcknowledgment of(String tenantSlug) {
        return new ActivationAcknowledgment("ACTIVATED", tenantSlug, "Cuenta activada; inicia sesion");
    }
}
