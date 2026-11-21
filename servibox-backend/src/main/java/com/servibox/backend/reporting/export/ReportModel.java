package com.servibox.backend.reporting.export;

import java.time.LocalDate;
import java.util.List;

/**
 * Un reporte ya calculado, listo para PdfReportGenerator o ExcelReportGenerator. Los
 * generadores solo dan formato: las cifras salen de ReportExportService, y se pueden probar
 * sin leer archivos.
 *
 * Cada celda de `rows` es un String, un LocalDate, un Double (moneda o porcentaje, con su
 * signo), un Number (INTEGER) o null (celda vacia).
 */
public record ReportModel(
        String tenantName,
        /** Linea "NIT <valor>" bajo el nombre. Null si el tenant no tiene NIT. */
        String tenantNit,
        String title,
        /** Null en un reporte de corte: el encabezado dice "Corte al <hasta>". */
        LocalDate desde,
        LocalDate hasta,
        /** Linea bajo el periodo, por ejemplo la cuenta o los filtros. Null si no hay. */
        String subtitle,
        List<Column> columns,
        List<List<Object>> rows,
        /** Lo que se muestra en la tabla cuando no hay filas. */
        String emptyMessage,
        /** Filas en negrita bajo la tabla: etiqueta y valor. */
        List<Total> totals,
        /** Fila final verde si es positiva y roja si es negativa. Null si no aplica. */
        Total result,
        /** Texto bajo la tabla. Null si no hay. */
        String note,
        /** Excel: filtro automatico sobre la tabla. */
        boolean filter,
        /**
         * Fila de totales de la tabla, con una celda por columna (la primera es la etiqueta,
         * null deja la celda vacia). Va justo despues de las filas. Null si no hay.
         */
        List<Object> footer
) {

    /** Sin fila de totales por columna: Estado de resultados y Movimientos. */
    public ReportModel(String tenantName, String tenantNit, String title, LocalDate desde, LocalDate hasta,
                       String subtitle, List<Column> columns, List<List<Object>> rows, String emptyMessage,
                       List<Total> totals, Total result, String note, boolean filter) {
        this(tenantName, tenantNit, title, desde, hasta, subtitle, columns, rows, emptyMessage, totals, result,
                note, filter, null);
    }

    /** INTEGER: cantidad sin signo de pesos. PERCENT: el numero en por ciento (12.5 es 12,5 %). */
    public enum ColumnType { TEXT, DATE, MONEY, INTEGER, PERCENT }

    /** width: ancho relativo en el PDF y en caracteres en el Excel. */
    public record Column(String title, ColumnType type, int width) {
    }

    public record Total(String label, double amount, ColumnType type) {

        public Total(String label, double amount) {
            this(label, amount, ColumnType.MONEY);
        }
    }
}
