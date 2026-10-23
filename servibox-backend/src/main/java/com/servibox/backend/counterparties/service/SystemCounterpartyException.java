package com.servibox.backend.counterparties.service;

/** Se intento editar el Consumidor final, que mantiene el sistema. */
public class SystemCounterpartyException extends RuntimeException {

    public SystemCounterpartyException() {
        super("El tercero Consumidor final es del sistema y no se puede editar");
    }
}
