package com.servibox.backend.inventory.service;

/**
 * Se intento cambiar el codigo de un producto que ya tiene lineas de venta o de compra. Las
 * lineas no guardan copia del codigo, asi que cambiarlo reescribiria las facturas emitidas.
 */
public class ProductCodeLockedException extends RuntimeException {

    public ProductCodeLockedException(String code) {
        super("El codigo " + code + " no se puede cambiar: el producto ya tiene facturas de venta o de compra");
    }
}
