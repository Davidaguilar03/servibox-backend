package com.servibox.backend.auth;

/** Un dato del registro que no cumple su regla. 400 con el mensaje de la regla. */
public class InvalidRegistrationException extends RuntimeException {

    public InvalidRegistrationException(String message) {
        super(message);
    }
}
