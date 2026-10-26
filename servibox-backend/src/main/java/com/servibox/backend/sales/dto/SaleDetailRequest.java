package com.servibox.backend.sales.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SaleDetailRequest(
        @NotNull Long productId,
        @NotNull Integer quantity,
        /** Precio unitario sin IVA. */
        @NotNull BigDecimal price
) {
}
