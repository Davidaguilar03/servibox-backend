package com.servibox.backend.treasury.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Mismo cuerpo para crear y para editar. Los obligatorios son los cuatro que valida el
 * formulario de Autollantas; `notes` es el campo "Observaciones", que alli no se valida.
 */
public record OperationalExpenseRequest(
        @NotNull Long accountId,
        @NotBlank String concept,
        @NotNull Double amount,
        /** Opcional: si no viene, se usa la fecha de hoy. */
        LocalDate date,
        /** Opcional. */
        String notes
) {
}
