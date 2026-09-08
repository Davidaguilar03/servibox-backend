package com.servibox.backend.treasury.dto;

import jakarta.validation.constraints.NotNull;

public record TransferRequest(
        @NotNull Long originAccountId,
        @NotNull Long destinationAccountId,
        String concept,
        @NotNull Double amount
) {
}
