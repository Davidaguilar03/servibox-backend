package com.servibox.backend.sales.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Un abono. Se llama abono, no pago ni cobro, ver 02-CONVENTIONS.md. */
public record CollectionRequest(
        @NotNull Long accountId,
        @NotNull Double amount,
        /** Opcional, yyyy-MM-dd: si no viene, hoy en la zona de negocio. No puede ser futura. */
        LocalDate date
) {
}
