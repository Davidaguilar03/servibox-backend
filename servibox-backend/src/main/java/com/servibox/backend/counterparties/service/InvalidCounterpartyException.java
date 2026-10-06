package com.servibox.backend.counterparties.service;

/**
 * Tercero que no sirve para la operacion pedida: un CLIENTE en una compra, un PROVEEDOR en
 * una venta, o uno al que le faltan datos obligatorios.
 */
public class InvalidCounterpartyException extends RuntimeException {

    public InvalidCounterpartyException(String mensaje) {
        super(mensaje);
    }
}
