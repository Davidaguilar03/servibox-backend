package com.servibox.backend.treasury.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record OccasionalIncomeRequest(
        @NotNull Long accountId,
        @NotBlank String concept,
        @NotNull Double amount,
        /** Opcional: si no viene, se usa la fecha de hoy. */
        LocalDate date
) {
}
