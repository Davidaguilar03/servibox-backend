package com.servibox.backend.purchases.service;

/**
 * Operacion que no cuadra con el estado de la compra: pagar una que no esta PENDIENTE,
 * pagar de mas, anular una ya ANULADA, anular una cuyo stock ya se vendio, restaurar
 * una que no esta ANULADA, o restaurar una ANULADA que conserva movimientos.
 */
public class InvalidPurchaseOperationException extends RuntimeException {

    public InvalidPurchaseOperationException(String mensaje) {
        super(mensaje);
    }
}
