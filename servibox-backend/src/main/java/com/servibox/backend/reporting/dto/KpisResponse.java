package com.servibox.backend.reporting.dto;

import java.time.LocalDate;
import com.servibox.backend.shared.Moneda;

/**
 * Los KPIs del dashboard. Un solo DTO para el calculo global y el del periodo: son
 * las mismas cifras, y desde / hasta van null cuando el calculo cubre todo el historico.
 *
 * totalSales     = total de las ventas, con IVA
 * totalPurchases = total de las compras, con IVA
 * netSales       = subtotal de las ventas, sin IVA
 * costOfSales    = costo unitario congelado por cantidad de las lineas de esas ventas
 * grossProfit    = netSales - costOfSales
 * otherIncome    = movimientos INGRESO_OCASIONAL activos, con el mismo criterio que operatingExpenses
 * netProfit      = grossProfit + otherIncome - operatingExpenses
 *
 * netSales, costOfSales, grossProfit y netProfit salen redondeados a 2 decimales.
 */
public record KpisResponse(
        LocalDate desde,
        LocalDate hasta,
        double totalSales,
        double totalPurchases,
        double netSales,
        double costOfSales,
        double grossProfit,
        double operatingExpenses,
        double otherIncome,
        double netProfit
) {

    public static KpisResponse of(LocalDate desde, LocalDate hasta,
                                  double totalSales, double totalPurchases, double netSales, double costOfSales,
                                  double operatingExpenses, double otherIncome) {
        double ventasNetas = redondear(netSales);
        double costo = redondear(costOfSales);
        double grossProfit = Moneda.restar(ventasNetas, costo);
        return new KpisResponse(desde, hasta, redondear(totalSales), redondear(totalPurchases), ventasNetas,
                costo, grossProfit, redondear(operatingExpenses), redondear(otherIncome),
                Moneda.restar(Moneda.sumar(grossProfit, otherIncome), operatingExpenses));
    }

    private static double redondear(double valor) {
        return Moneda.aCentavos(valor);
    }
}
