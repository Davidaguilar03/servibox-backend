package com.servibox.backend.reporting.export;

import java.time.LocalDate;
import java.util.List;

/**
 * Un reporte ya calculado, listo para PdfReportGenerator o ExcelReportGenerator. Los
 * generadores solo dan formato: las cifras salen de ReportExportService, y se pueden probar
 * sin leer archivos.
 *
 * Cada celda de `rows` es un String, un LocalDate o un Double (moneda, con su signo).
 */
public record ReportModel(
        String tenantName,
        String title,
        LocalDate desde,
        LocalDate hasta,
        /** Linea bajo el periodo, por ejemplo la cuenta. Null si no hay. */
        String subtitle,
        List<Column> columns,
        List<List<Object>> rows,
        /** Lo que se muestra en la tabla cuando no hay filas. */
        String emptyMessage,
        /** Filas en negrita bajo la tabla: etiqueta y monto. */
        List<Total> totals,
        /** Fila final verde si es positiva y roja si es negativa. Null si no aplica. */
        Total result,
        /** Texto bajo la tabla. Null si no hay. */
        String note,
        /** Excel: filtro automatico sobre la tabla. */
        boolean filter
) {

    public enum ColumnType { TEXT, DATE, MONEY }

    /** width: ancho relativo en el PDF y en caracteres en el Excel. */
    public record Column(String title, ColumnType type, int width) {
    }

    public record Total(String label, double amount) {
    }
}
