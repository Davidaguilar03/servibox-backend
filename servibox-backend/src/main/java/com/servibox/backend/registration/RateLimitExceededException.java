package com.servibox.backend.registration;

import lombok.Getter;

/** Limite de tasa por IP de una ruta publica superado. 429 con Retry-After en segundos. */
@Getter
public class RateLimitExceededException extends RuntimeException {

    static final String SOLICITUDES = "Demasiadas solicitudes desde esta conexion, intenta de nuevo mas tarde";
    static final String ACTIVACION = "Demasiados intentos de activacion, espera unos minutos e intenta de nuevo";

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
