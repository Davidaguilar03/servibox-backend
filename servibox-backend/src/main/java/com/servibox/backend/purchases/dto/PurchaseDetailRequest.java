package com.servibox.backend.purchases.dto;

import jakarta.validation.constraints.NotNull;

public record PurchaseDetailRequest(
        @NotNull Long productId,
        @NotNull Integer quantity,
        /** Costo de compra unitario, sin IVA. */
        @NotNull Double price
) {
}
