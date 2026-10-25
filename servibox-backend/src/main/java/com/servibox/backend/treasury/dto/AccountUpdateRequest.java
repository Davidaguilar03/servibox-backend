package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AccountUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @PositiveOrZero Double initialBalance,
        AccountType type
) {
}
