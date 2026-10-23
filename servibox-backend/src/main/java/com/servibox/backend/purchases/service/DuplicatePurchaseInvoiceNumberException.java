package com.servibox.backend.purchases.service;

/** Dos facturas de compra del mismo proveedor, en el mismo tenant, con el mismo numero. */
public class DuplicatePurchaseInvoiceNumberException extends RuntimeException {

    public DuplicatePurchaseInvoiceNumberException(String invoiceNumber, String proveedor) {
        super("Ya existe una factura de compra " + invoiceNumber + " del proveedor " + proveedor);
    }
}
