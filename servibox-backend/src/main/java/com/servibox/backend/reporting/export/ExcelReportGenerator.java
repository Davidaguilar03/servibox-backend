package com.servibox.backend.reporting.export;

import com.servibox.backend.reporting.export.ReportModel.Column;
import com.servibox.backend.reporting.export.ReportModel.ColumnType;
import com.servibox.backend.reporting.export.ReportModel.Total;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Un ReportModel en xlsx con Apache POI: una hoja, encabezado del tenant en filas
 * combinadas y la tabla con su fila de titulos inmovilizada. Los montos son celdas
 * numericas reales (#,##0), no texto. Anchos fijos: autoSizeColumn necesita fuentes del
 * sistema y falla en un contenedor sin ellas. Mismos colores que el PDF; la ultima fila dice
 * la version del producto.
 */
@Component
public class ExcelReportGenerator {

    private static final String CARBON = "1F2937";
    private static final String FILA_ALTERNA = "FAFBFC";
    private static final String FONDO_TOTAL = "F2F3F5";
    private static final String ROJO_NEGATIVO = "DC2626";
    private static final String FONDO_POSITIVO = "DCFCE7";
    private static final String TEXTO_POSITIVO = "16A34A";
    private static final String FONDO_NEGATIVO = "FEE2E2";
    private static final String GRIS = "6B7280";
    private static final String BLANCO = "FFFFFF";
    private static final String NEGRO = "000000";

    private final ObjectProvider<BuildProperties> buildProperties;

    public ExcelReportGenerator(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    public byte[] generar(ReportModel r) {
        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            new Hoja(libro, r).escribir("ServiBox " + PdfReportGenerator.version(buildProperties));
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel", e);
        }
    }

    /** Estado de una hoja en construccion: la fila actual y los estilos ya creados. */
    private static final class Hoja {

        private final XSSFWorkbook libro;
        private final XSSFSheet hoja;
        private final ReportModel r;
        private final int n;
        private final Map<String, XSSFCellStyle> estilos = new HashMap<>();
        private int fila = 0;

        Hoja(XSSFWorkbook libro, ReportModel r) {
            this.libro = libro;
            this.hoja = libro.createSheet(r.title());
            this.r = r;
            this.n = r.columns().size();
        }

        void escribir(String version) {
            encabezado(r.tenantName(), estilo(null, true, NEGRO, 14, null));
            if (r.tenantNit() != null) {
                encabezado("NIT " + r.tenantNit(), estilo(null, false, GRIS, 10, null));
            }
            encabezado(r.title(), estilo(null, true, NEGRO, 12, null));
            encabezado(PdfReportGenerator.periodo(r), estilo(null, false, GRIS, 10, null));
            if (r.subtitle() != null) {
                encabezado(r.subtitle(), estilo(null, false, GRIS, 10, null));
            }
            fila++;

            int filaTitulos = fila;
            Row titulos = hoja.createRow(fila++);
            for (int c = 0; c < n; c++) {
                Column columna = r.columns().get(c);
                Cell celda = titulos.createCell(c);
                celda.setCellValue(columna.title());
                celda.setCellStyle(estilo(CARBON, true, BLANCO, 0, null));
                hoja.setColumnWidth(c, columna.width() * 256);
            }
            hoja.createFreezePane(0, filaTitulos + 1);

            if (r.rows().isEmpty()) {
                Row vacia = hoja.createRow(fila);
                Cell celda = vacia.createCell(0);
                celda.setCellValue(r.emptyMessage());
                celda.setCellStyle(estilo(null, false, GRIS, 0, null));
                combinar(fila++, 0, n - 1);
            }
            for (int i = 0; i < r.rows().size(); i++) {
                String fondo = i % 2 == 1 ? FILA_ALTERNA : null;
                Row xr = hoja.createRow(fila++);
                List<Object> valores = r.rows().get(i);
                for (int c = 0; c < n; c++) {
                    valor(xr.createCell(c), valores.get(c), r.columns().get(c).type(), fondo, false, null);
                }
            }
            if (r.filter() && !r.rows().isEmpty()) {
                hoja.setAutoFilter(new CellRangeAddress(filaTitulos, fila - 1, 0, n - 1));
            }
            if (r.footer() != null) {
                Row xr = hoja.createRow(fila++);
                for (int c = 0; c < n; c++) {
                    Object celda = r.footer().get(c);
                    if (c == 0 || celda == null) {
                        valor(xr.createCell(c), celda, ColumnType.TEXT, FONDO_TOTAL, true, null);
                    } else {
                        valor(xr.createCell(c), celda, r.columns().get(c).type(), FONDO_TOTAL, true, null);
                    }
                }
            }

            for (Total total : r.totals()) {
                filaTotal(total, FONDO_TOTAL, null);
            }
            if (r.result() != null) {
                boolean positivo = r.result().amount() >= 0;
                filaTotal(r.result(), positivo ? FONDO_POSITIVO : FONDO_NEGATIVO,
                        positivo ? TEXTO_POSITIVO : ROJO_NEGATIVO);
            }
            if (r.note() != null) {
                fila++;
                Cell nota = hoja.createRow(fila).createCell(0);
                nota.setCellValue(r.note());
                nota.setCellStyle(estilo(null, false, GRIS, 0, null));
            }
            fila += 2;
            Cell pie = hoja.createRow(fila).createCell(0);
            pie.setCellValue(version);
            pie.setCellStyle(estilo(null, false, GRIS, 0, null));
        }

        private void encabezado(String texto, XSSFCellStyle estilo) {
            Cell celda = hoja.createRow(fila).createCell(0);
            celda.setCellValue(texto);
            celda.setCellStyle(estilo);
            combinar(fila++, 0, n - 1);
        }

        private void filaTotal(Total total, String fondo, String colorTexto) {
            Row xr = hoja.createRow(fila);
            Cell etiqueta = xr.createCell(0);
            etiqueta.setCellValue(total.label());
            etiqueta.setCellStyle(estilo(fondo, true, colorTexto != null ? colorTexto : NEGRO, 0, null));
            for (int c = 1; c < n - 1; c++) {
                xr.createCell(c).setCellStyle(estilo(fondo, true, NEGRO, 0, null));
            }
            combinar(fila, 0, n - 2);
            valor(xr.createCell(n - 1), total.amount(), total.type(), fondo, true, colorTexto);
            fila++;
        }

        private void valor(Cell celda, Object valor, ColumnType tipo, String fondo, boolean negrita,
                           String colorForzado) {
            if (valor == null) {
                celda.setCellValue("");
                celda.setCellStyle(estilo(fondo, negrita, NEGRO, 0, null));
                return;
            }
            switch (tipo) {
                case DATE -> {
                    celda.setCellValue((LocalDate) valor);
                    celda.setCellStyle(estilo(fondo, negrita, NEGRO, 0, "dd/mm/yyyy"));
                }
                case MONEY -> {
                    double monto = (Double) valor;
                    String color = colorForzado != null ? colorForzado : monto < 0 ? ROJO_NEGATIVO : NEGRO;
                    celda.setCellValue(monto);
                    String formato = java.math.BigDecimal.valueOf(monto).stripTrailingZeros().scale() > 0
                            ? "#,##0.00" : "#,##0";
                    celda.setCellStyle(estilo(fondo, negrita, color, 0, formato));
                }
                case INTEGER -> {
                    celda.setCellValue(((Number) valor).doubleValue());
                    celda.setCellStyle(estilo(fondo, negrita, NEGRO, 0, "#,##0"));
                }
                case PERCENT -> {
                    // Excel guarda la fraccion: 12,5 % es 0,125 con formato de porcentaje.
                    double porcentaje = (Double) valor;
                    celda.setCellValue(java.math.BigDecimal.valueOf(porcentaje)
                            .divide(java.math.BigDecimal.valueOf(100)).doubleValue());
                    String color = colorForzado != null ? colorForzado : porcentaje < 0 ? ROJO_NEGATIVO : NEGRO;
                    celda.setCellStyle(estilo(fondo, negrita, color, 0, "0.00%"));
                }
                case TEXT -> {
                    celda.setCellValue(String.valueOf(valor));
                    celda.setCellStyle(estilo(fondo, negrita, NEGRO, 0, null));
                }
            }
        }

        private void combinar(int filaCombinada, int desde, int hasta) {
            if (hasta > desde) {
                hoja.addMergedRegion(new CellRangeAddress(filaCombinada, filaCombinada, desde, hasta));
            }
        }

        /** Un estilo por combinacion: POI tiene un tope de estilos por libro. */
        private XSSFCellStyle estilo(String fondo, boolean negrita, String colorTexto, int tamano, String formato) {
            String clave = fondo + "|" + negrita + "|" + colorTexto + "|" + tamano + "|" + formato;
            return estilos.computeIfAbsent(clave, k -> {
                XSSFCellStyle estilo = libro.createCellStyle();
                if (fondo != null) {
                    estilo.setFillForegroundColor(color(fondo));
                    estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                XSSFFont fuente = libro.createFont();
                fuente.setBold(negrita);
                fuente.setColor(color(colorTexto));
                if (tamano > 0) {
                    fuente.setFontHeightInPoints((short) tamano);
                }
                estilo.setFont(fuente);
                if (formato != null) {
                    estilo.setDataFormat(libro.createDataFormat().getFormat(formato));
                }
                if ("#,##0".equals(formato) || "#,##0.00".equals(formato) || "0.00%".equals(formato)) {
                    estilo.setAlignment(HorizontalAlignment.RIGHT);
                }
                if (fondo != null || formato != null) {
                    estilo.setBorderBottom(BorderStyle.THIN);
                    estilo.setBottomBorderColor(color("E5E7EB"));
                }
                return estilo;
            });
        }

        private static XSSFColor color(String hex) {
            return new XSSFColor(new byte[]{
                    (byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16),
                    (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
        }
    }
}
