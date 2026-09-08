package com.servibox.backend.sales.service;

/**
 * Se intento facturar con un numero que ya usa otra factura del mismo tenant. Existe para
 * que el usuario vea un mensaje legible en vez del error crudo de constraint violation.
 * Mismo patron que DuplicateProductCodeException y DuplicateAccountNameException.
 */
public class DuplicateInvoiceNumberException extends RuntimeException {

    public DuplicateInvoiceNumberException(String invoiceNumber) {
        super("Ya existe una factura con el numero " + invoiceNumber);
    }
}
