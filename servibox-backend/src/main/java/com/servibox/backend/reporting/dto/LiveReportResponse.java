package com.servibox.backend.reporting.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Forma comun de los reportes en vivo (ventas, productos vendidos, compras y carteras):
 * indicadores, filas de la tabla y puntos de la grafica. Ver "Reportes en vivo" en
 * 01-ARCHITECTURE.md.
 *
 * Un reporte de rango trae desde y hasta, y corte null; uno de corte (las carteras) trae
 * corte, y desde y hasta null.
 */
public record LiveReportResponse<S, R>(
        LocalDate desde,
        LocalDate hasta,
        LocalDate corte,
        /** Los filtros aplicados en texto para mostrar, por ejemplo "Cliente: Transportes Andina". */
        List<String> appliedFilters,
        S summary,
        List<R> rows,
        List<SeriesPoint> series
) {
}
