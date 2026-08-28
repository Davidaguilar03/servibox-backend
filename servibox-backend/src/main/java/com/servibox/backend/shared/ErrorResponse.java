package com.servibox.backend.shared;

/**
 * Formato unico de error de toda la API.
 */
public record ErrorResponse(String error, int status) {
}
