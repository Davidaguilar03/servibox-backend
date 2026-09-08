package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AccountRequest(
        @NotBlank String name,
        Double initialBalance,
        @NotNull AccountType type
) {
}
