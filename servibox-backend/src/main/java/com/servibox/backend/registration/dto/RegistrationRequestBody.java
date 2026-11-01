package com.servibox.backend.registration.dto;

/**
 * Cuerpo de POST /api/registration-requests. Sin anotaciones de validacion, igual que
 * RegisterRequest. password existe solo para rechazarla: la solicitud no lleva contrasena.
 */
public record RegistrationRequestBody(
        String businessName,
        String nit,
        String phone,
        String slug,
        String adminFullName,
        String adminEmail,
        Boolean acceptTerms,
        String policyVersion,
        String password
) {
}
