package com.servibox.backend.reporting.dto;

import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;

import java.time.LocalDate;

/** Indicadores y filas de GET /api/reporting/movements-report. */
public final class MovementsReport {

    private MovementsReport() {
    }

    public record Summary(
            double inflows,
            double outflows,
            double net,
            long movementCount
    ) {
    }

    /**
     * reference: "VENTA <numero>", "COMPRA <numero>", "GASTO OPERATIVO", "INGRESO OCASIONAL" o
     * "TRANSFERENCIA", igual que la exportacion. amount siempre positivo; el signo lo da type.
     */
    public record Row(
            Long movementId,
            LocalDate date,
            String accountName,
            String concept,
            String reference,
            MovementType type,
            MovementCategory category,
            double amount
    ) {
    }
}
