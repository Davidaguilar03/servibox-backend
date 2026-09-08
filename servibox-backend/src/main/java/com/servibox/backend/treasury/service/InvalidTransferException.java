package com.servibox.backend.treasury.service;

/** Transferencia que no tiene sentido: misma cuenta en los dos extremos, o monto no positivo. */
public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String mensaje) {
        super(mensaje);
    }
}
