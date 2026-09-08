package com.servibox.backend.sales.service;

/**
 * Operacion que no cuadra con el estado de la factura: abonar a una que no esta
 * PENDIENTE, abonar mas de lo que se debe, o anular una ya ANULADA.
 */
public class InvalidSaleOperationException extends RuntimeException {

    public InvalidSaleOperationException(String mensaje) {
        super(mensaje);
    }
}
