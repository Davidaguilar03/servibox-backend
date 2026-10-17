package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.MovementCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Movimiento manual, para crear y para editar. `category` solo puede ser GASTO_OPERATIVO o
 * INGRESO_OCASIONAL; el tipo (INGRESO / EGRESO) sale de ella. Al editar, la categoria
 * tiene que ser la que ya tenia el movimiento.
 */
public record MovementRequest(
        @NotNull Long accountId,
        @NotNull MovementCategory category,
        @NotBlank String concept,
        @NotNull Double amount,
        /** Opcional: si no viene, se usa la fecha de hoy. */
        LocalDate date,
        /** Opcional. */
        String notes
) {
}
