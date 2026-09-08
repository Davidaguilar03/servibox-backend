package com.servibox.backend.purchases.dto;

import jakarta.validation.constraints.NotNull;

/** Un pago a un proveedor. Se llama pago, no abono. Ver 02-CONVENTIONS.md. */
public record PaymentRequest(
        @NotNull Long accountId,
        @NotNull Double amount
) {
}
