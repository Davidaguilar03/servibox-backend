package com.servibox.backend.reporting.dto;

/** Indicadores, filas y serie de GET /api/reporting/income-statement. Las cifras son las de /kpis. */
public final class IncomeStatementReport {

    private IncomeStatementReport() {
    }

    /** Los margenes en por ciento sobre revenue (ventas sin IVA), 0 si no hubo ventas. */
    public record Summary(
            double revenue,
            double costOfSales,
            double grossProfit,
            double grossMarginPercent,
            double operatingExpenses,
            double otherIncome,
            double netProfit,
            double netMarginPercent
    ) {
    }

    /** amount con su signo (costo y gastos negativos); percentOfRevenue en por ciento. */
    public record Row(
            String line,
            String label,
            double amount,
            double percentOfRevenue
    ) {
    }

    /**
     * Un mes (label "2026-09") si el rango cruza meses, o un dia (label "2026-09-05") si no.
     * revenue sin IVA, cost el costo de lo vendido y profit la utilidad neta del punto.
     */
    public record Point(
            String label,
            double revenue,
            double cost,
            double profit
    ) {
    }
}
