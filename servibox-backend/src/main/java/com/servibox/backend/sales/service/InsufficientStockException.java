package com.servibox.backend.sales.service;

/** No hay unidades suficientes de un producto para facturar la cantidad pedida. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String descripcion, int disponible, int pedido) {
        super("Stock insuficiente para " + descripcion + ": hay " + disponible
                + " y se piden " + pedido);
    }
}
