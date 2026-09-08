package com.servibox.backend.sales.dto;

import jakarta.validation.constraints.NotNull;

public record SaleDetailRequest(
        @NotNull Long productId,
        @NotNull Integer quantity,
        /** Precio unitario sin IVA. */
        @NotNull Double price
) {
}
