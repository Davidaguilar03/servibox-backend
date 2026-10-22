package com.servibox.backend.auth.dto;

/**
 * Cuerpo de POST /api/auth/register. Sin anotaciones de validacion a proposito: las reglas
 * viven en TenantRegistrationService, que responde un 400 con un mensaje por regla. Con
 * @Valid todas saldrian como el "Peticion invalida" generico de GlobalExceptionHandler.
 */
public record RegisterRequest(
        String businessName,
        String nit,
        String phone,
        String slug,
        String adminFullName,
        String adminEmail,
        String adminUsername,
        String password,
        Boolean acceptTerms
) {
}
