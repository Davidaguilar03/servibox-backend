package com.servibox.backend.counterparties.service;

/**
 * Se intento editar el Consumidor final, que mantiene el sistema, o dar su documento
 * (222222222222) a otro tercero. 409.
 */
public class SystemCounterpartyException extends RuntimeException {

    public SystemCounterpartyException() {
        super("El tercero Consumidor final es del sistema y no se puede editar");
    }

    public SystemCounterpartyException(String mensaje) {
        super(mensaje);
    }
}
