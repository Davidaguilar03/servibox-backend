package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.MovementType;
import jakarta.validation.constraints.NotNull;

public record MovementRequest(
        @NotNull Long accountId,
        @NotNull MovementType type,
        String concept,
        @NotNull Double amount
) {
}
