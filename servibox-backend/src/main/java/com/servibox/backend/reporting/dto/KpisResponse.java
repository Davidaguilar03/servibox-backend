package com.servibox.backend.reporting.dto;

import java.time.LocalDate;

/**
 * Los KPIs del dashboard. Un solo DTO para el calculo global y el del periodo: son
 * las mismas cifras, y desde / hasta van null cuando el calculo cubre todo el historico.
 *
 * grossProfit = totalSales - totalPurchases
 * otherIncome = movimientos INGRESO_OCASIONAL activos, con el mismo criterio que operatingExpenses
 * netProfit   = grossProfit + otherIncome - operatingExpenses
 */
public record KpisResponse(
        LocalDate desde,
        LocalDate hasta,
        double totalSales,
        double totalPurchases,
        double grossProfit,
        double operatingExpenses,
        double otherIncome,
        double netProfit
) {

    public static KpisResponse of(LocalDate desde, LocalDate hasta,
                                  double totalSales, double totalPurchases, double operatingExpenses,
                                  double otherIncome) {
        double grossProfit = totalSales - totalPurchases;
        return new KpisResponse(desde, hasta, totalSales, totalPurchases, grossProfit,
                operatingExpenses, otherIncome, grossProfit + otherIncome - operatingExpenses);
    }
}
