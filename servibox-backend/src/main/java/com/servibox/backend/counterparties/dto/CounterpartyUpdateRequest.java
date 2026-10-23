package com.servibox.backend.counterparties.dto;

import com.servibox.backend.counterparties.entity.DocumentType;

/**
 * Cuerpo de PUT /api/counterparties/{id}. Sin role: no se edita, y si llega en el JSON se
 * ignora. Sin anotaciones de validacion, igual que TenantProfileRequest: las reglas y sus
 * mensajes estan en CounterpartyService.actualizar.
 */
public record CounterpartyUpdateRequest(
        String name,
        DocumentType documentType,
        String documentNumber,
        String email,
        String phone
) {
}
