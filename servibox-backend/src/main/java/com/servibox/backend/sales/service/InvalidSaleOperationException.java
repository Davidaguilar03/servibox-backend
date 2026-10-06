package com.servibox.backend.sales.service;

/**
 * Operacion que no cuadra con el estado de la factura: abonar a una que no esta
 * PENDIENTE, abonar mas de lo que se debe, anular una ya ANULADA, restaurar una que no
 * esta ANULADA, o restaurar una ANULADA que conserva movimientos.
 */
public class InvalidSaleOperationException extends RuntimeException {

    public InvalidSaleOperationException(String mensaje) {
        super(mensaje);
    }
}
