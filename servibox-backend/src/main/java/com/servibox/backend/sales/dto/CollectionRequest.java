package com.servibox.backend.sales.dto;

import jakarta.validation.constraints.NotNull;

/** Un abono. Se llama abono, no pago ni cobro, ver 02-CONVENTIONS.md. */
public record CollectionRequest(
        @NotNull Long accountId,
        @NotNull Double amount
) {
}
