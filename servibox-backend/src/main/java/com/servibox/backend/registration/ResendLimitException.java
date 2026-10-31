package com.servibox.backend.registration;

/** Reenvio de activacion antes del intervalo minimo o por encima del cupo diario. 429. */
public class ResendLimitException extends RuntimeException {

    static final String ESPERA = "Espera un momento antes de reenviar la activacion";
    static final String CUPO = "Se alcanzo el limite diario de reenvios de esta solicitud";

    public ResendLimitException(String message) {
        super(message);
    }
}
