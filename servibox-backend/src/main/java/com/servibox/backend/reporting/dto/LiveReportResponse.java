package com.servibox.backend.reporting.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Forma comun de los reportes en vivo: indicadores, filas de la tabla y puntos de la grafica.
 * Ver "Reportes en vivo" en 01-ARCHITECTURE.md.
 *
 * Un reporte de rango trae desde y hasta, y corte null; uno de corte (las carteras) trae
 * corte, y desde y hasta null. Inventario trae los tres: corte para el stock y el rango para la
 * rotacion. P es el punto de la grafica: SeriesPoint (etiqueta y valor) salvo en el Estado de
 * resultados, que trae ingresos, costo y utilidad por punto.
 */
public record LiveReportResponse<S, R, P>(
        LocalDate desde,
        LocalDate hasta,
        LocalDate corte,
        /** Los filtros aplicados en texto para mostrar, por ejemplo "Cliente: Transportes Andina". */
        List<String> appliedFilters,
        S summary,
        List<R> rows,
        List<P> series
) {
}
