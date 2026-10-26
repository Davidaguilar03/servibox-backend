package com.servibox.backend.purchases.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PurchaseDetailRequest(
        @NotNull Long productId,
        @NotNull Integer quantity,
        /** Costo de compra unitario, sin IVA. */
        @NotNull BigDecimal price
) {
}
