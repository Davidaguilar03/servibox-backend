package com.servibox.backend.purchases.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Un pago a un proveedor. Se llama pago, no abono. Ver 02-CONVENTIONS.md. */
public record PaymentRequest(
        @NotNull Long accountId,
        @NotNull Double amount,
        /** Opcional, yyyy-MM-dd: si no viene, hoy en la zona de negocio. No puede ser futura. */
        LocalDate date
) {
}
