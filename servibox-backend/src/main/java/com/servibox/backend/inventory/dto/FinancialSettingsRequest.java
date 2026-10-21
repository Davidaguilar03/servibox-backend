package com.servibox.backend.inventory.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de PUT /api/inventory/financial-settings. Fracciones entre 0 y 1, el mismo rango
 * que los CHECK ck_configuracion_* de la base (icaRate es por mil: 0.0082 es 8.2 por mil).
 * Sin comision de tarjeta queda en 0.0, como en saveFinancialSettings.
 */
public record FinancialSettingsRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double expensesRate,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double dianRate,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double icaRate,
        @DecimalMin("0.0") @DecimalMax("1.0") Double cardCommissionRate
) {
}
