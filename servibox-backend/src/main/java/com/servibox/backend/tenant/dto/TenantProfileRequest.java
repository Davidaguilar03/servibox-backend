package com.servibox.backend.tenant.dto;

/**
 * Cuerpo de PUT /api/tenant. Sin slug: es inmutable, y si llega en el JSON se ignora. Sin
 * anotaciones de validacion, igual que RegisterRequest: las reglas y sus mensajes son las
 * del registro, en TenantRegistrationService.
 */
public record TenantProfileRequest(String name, String nit, String phone) {
}
