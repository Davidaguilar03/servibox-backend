package com.servibox.backend.counterparties.service;

/**
 * Se intento crear un tercero con un numero de documento que ya tiene otro tercero del
 * mismo tenant. Mensaje legible antes de que salte uk_tercero_tenant_document. Mismo patron
 * que DuplicateAccountNameException.
 */
public class DuplicateCounterpartyDocumentException extends RuntimeException {

    public DuplicateCounterpartyDocumentException(String documentNumber) {
        super("Ya existe un tercero con el documento " + documentNumber);
    }
}
