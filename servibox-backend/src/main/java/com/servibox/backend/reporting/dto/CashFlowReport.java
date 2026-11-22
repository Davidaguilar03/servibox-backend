package com.servibox.backend.reporting.dto;

/** Indicadores y filas de GET /api/reporting/cash-flow. */
public final class CashFlowReport {

    private CashFlowReport() {
    }

    /**
     * openingBalance al cierre del dia anterior a desde y closingBalance al cierre de hasta, de la
     * cuenta o de todas. Siempre openingBalance + inflows - outflows = closingBalance.
     */
    public record Summary(
            double openingBalance,
            double inflows,
            double outflows,
            double netFlow,
            double closingBalance
    ) {
    }

    /**
     * concept: VENTAS_CONTADO, ABONOS_CLIENTES, INGRESOS_OCASIONALES, TRANSFERENCIAS_RECIBIDAS,
     * COMPRAS_CONTADO, PAGOS_PROVEEDORES, GASTOS_OPERATIVOS o TRANSFERENCIAS_ENVIADAS; label es su
     * texto de pantalla. direction: ENTRADA o SALIDA.
     */
    public record Row(
            String concept,
            String label,
            String direction,
            long movementCount,
            double amount
    ) {
    }
}
