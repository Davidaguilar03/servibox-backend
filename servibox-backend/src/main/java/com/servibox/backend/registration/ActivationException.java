package com.servibox.backend.registration;

import org.springframework.http.HttpStatus;

/** Token de activacion no valido (400), vencido o reemplazado (410) o ya usado (409). Sin datos de la solicitud. */
public class ActivationException extends RuntimeException {

    static final String NO_VALIDO = "El enlace de activacion no es valido";
    static final String VENCIDO = "El enlace de activacion vencio o fue reemplazado";
    static final String USADO = "El enlace de activacion ya fue utilizado; inicia sesion";

    private final HttpStatus status;

    private ActivationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    static ActivationException noValido() {
        return new ActivationException(HttpStatus.BAD_REQUEST, NO_VALIDO);
    }

    static ActivationException vencido() {
        return new ActivationException(HttpStatus.GONE, VENCIDO);
    }

    static ActivationException usado() {
        return new ActivationException(HttpStatus.CONFLICT, USADO);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
