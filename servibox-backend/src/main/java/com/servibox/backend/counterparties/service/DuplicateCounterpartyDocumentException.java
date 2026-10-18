package com.servibox.backend.counterparties.service;

import com.servibox.backend.counterparties.entity.CounterpartyRole;

/**
 * Se intento crear un tercero con un numero de documento que ya tiene otro tercero del
 * mismo tenant. Mensaje legible antes de que salte uk_tercero_tenant_document. Mismo patron
 * que DuplicateAccountNameException.
 */
public class DuplicateCounterpartyDocumentException extends RuntimeException {

    public DuplicateCounterpartyDocumentException(String documentNumber) {
        super("Ya existe un tercero con el documento " + documentNumber);
    }

    /** Alta por la API de un documento que ya tiene ese rol (o AMBOS). */
    public DuplicateCounterpartyDocumentException(String documentNumber, CounterpartyRole role) {
        super("Ya existe un tercero con el documento " + documentNumber + " como " + role);
    }
}
