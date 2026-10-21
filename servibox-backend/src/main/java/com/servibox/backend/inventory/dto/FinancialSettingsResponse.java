package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.FinancialSettings;

public record FinancialSettingsResponse(
        Double expensesRate,
        Double dianRate,
        Double icaRate,
        Double cardCommissionRate
) {

    public static FinancialSettingsResponse from(FinancialSettings settings) {
        return new FinancialSettingsResponse(
                settings.getExpensesRate(),
                settings.getDianRate(),
                settings.getIcaRate(),
                settings.getCardCommissionRate()
        );
    }
}
