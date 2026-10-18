package com.servibox.backend.counterparties.dto;

import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de un tercero. role es CLIENTE o PROVEEDOR; AMBOS no se pide, se alcanza. */
public record CounterpartyRequest(
        @NotNull CounterpartyRole role,
        @NotBlank @Size(max = 150) String name,
        @NotNull DocumentType documentType,
        @NotBlank @Size(max = 25) String documentNumber,
        @Size(max = 150) String email,
        @Size(max = 25) String phone
) {
}
