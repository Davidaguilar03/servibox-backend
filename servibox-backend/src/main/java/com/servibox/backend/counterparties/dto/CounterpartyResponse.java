package com.servibox.backend.counterparties.dto;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;

/** El cliente de una venta o el proveedor de una compra, como datos de un tercero. */
public record CounterpartyResponse(
        Long id,
        CounterpartyRole role,
        String name,
        DocumentType documentType,
        String documentNumber,
        String email,
        String phone
) {

    /** Null si la factura no tiene tercero. */
    public static CounterpartyResponse from(Counterparty tercero) {
        if (tercero == null) {
            return null;
        }
        return new CounterpartyResponse(tercero.getId(), tercero.getRole(), tercero.getName(),
                tercero.getDocumentType(), tercero.getDocumentNumber(), tercero.getEmail(),
                tercero.getPhone());
    }
}
