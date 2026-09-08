package com.servibox.backend.purchases.service;

/** Dos facturas de compra del mismo tenant con el mismo numero. */
public class DuplicatePurchaseInvoiceNumberException extends RuntimeException {

    public DuplicatePurchaseInvoiceNumberException(String invoiceNumber) {
        super("Ya existe una factura de compra con el numero " + invoiceNumber);
    }
}
