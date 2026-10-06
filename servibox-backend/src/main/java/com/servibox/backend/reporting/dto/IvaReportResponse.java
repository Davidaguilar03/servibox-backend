package com.servibox.backend.reporting.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Reporte de IVA del rango: desglose por categoria de producto, ordenado por IVA
 * generado de mayor a menor, mas el resumen general.
 */
public record IvaReportResponse(
        LocalDate desde,
        LocalDate hasta,
        List<IvaCategoryResponse> categories,
        int invoicesWithIva,
        double totalIvaGenerado,
        double totalIvaDescontable,
        double totalIvaNeto
) {
}
