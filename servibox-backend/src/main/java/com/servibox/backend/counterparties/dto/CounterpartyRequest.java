package com.servibox.backend.counterparties.dto;

import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;

/**
 * Alta de un tercero. role es CLIENTE o PROVEEDOR; AMBOS no se pide, se alcanza. Sin
 * anotaciones de validacion, igual que CounterpartyUpdateRequest: las reglas y sus mensajes
 * estan en CounterpartyService.validarDatos.
 */
public record CounterpartyRequest(
        CounterpartyRole role,
        String name,
        DocumentType documentType,
        String documentNumber,
        String email,
        String phone
) {
}
