package com.servibox.backend.reporting.export;

import com.servibox.backend.reporting.export.ReportModel.Column;
import com.servibox.backend.reporting.export.ReportModel.ColumnType;
import com.servibox.backend.reporting.export.ReportModel.Total;
import lombok.RequiredArgsConstructor;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfStamper;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.draw.LineSeparator;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Un ReportModel en PDF con OpenPDF: A4 vertical, margenes de 36 pt y Helvetica estandar
 * (Cp1252 cubre tildes y enie sin embeber fuentes). El encabezado de la tabla se repite en
 * cada pagina. El pie "Pag. N de M" necesita el total de paginas, asi que se estampa en una
 * segunda pasada sobre el documento ya cerrado.
 */
@Component
@RequiredArgsConstructor
public class PdfReportGenerator {

    static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Color ROJO_MARCA = new Color(0xFF, 0x3B, 0x30);
    private static final Color CARBON = new Color(0x1F, 0x29, 0x37);
    private static final Color FILA_ALTERNA = new Color(0xFA, 0xFB, 0xFC);
    private static final Color FONDO_TOTAL = new Color(0xF2, 0xF3, 0xF5);
    private static final Color ROJO_NEGATIVO = new Color(0xDC, 0x26, 0x26);
    private static final Color FONDO_POSITIVO = new Color(0xDC, 0xFC, 0xE7);
    private static final Color TEXTO_POSITIVO = new Color(0x16, 0xA3, 0x4A);
    private static final Color FONDO_NEGATIVO = new Color(0xFE, 0xE2, 0xE2);
    private static final Color GRIS = new Color(0x6B, 0x72, 0x80);
    private static final float MARGEN = 36f;

    /** El reloj de negocio: la hora del pie es la del taller, no la del servidor. */
    private final Clock clock;

    public byte[] generar(ReportModel reporte) {
        byte[] cuerpo = cuerpo(reporte);
        String generado = "Generado el " + LocalDateTime.now(clock).format(FECHA_HORA) + " · " + reporte.tenantName();
        return conPie(cuerpo, generado);
    }

    private byte[] cuerpo(ReportModel r) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document documento = new Document(PageSize.A4, MARGEN, MARGEN, MARGEN, MARGEN);
        PdfWriter.getInstance(documento, salida);
        documento.open();

        documento.add(new Paragraph(r.tenantName(), fuente(14, Font.BOLD, Color.BLACK)));
        documento.add(new Paragraph(r.title(), fuente(12, Font.BOLD, Color.BLACK)));
        documento.add(new Paragraph("Período: " + r.desde().format(FECHA) + " a " + r.hasta().format(FECHA),
                fuente(10, Font.NORMAL, GRIS)));
        if (r.subtitle() != null) {
            documento.add(new Paragraph(r.subtitle(), fuente(10, Font.NORMAL, GRIS)));
        }
        documento.add(new Paragraph(" ", fuente(6, Font.NORMAL, Color.BLACK)));
        documento.add(new LineSeparator(3f, 100f, ROJO_MARCA, Element.ALIGN_CENTER, 0f));
        documento.add(new Paragraph(" ", fuente(8, Font.NORMAL, Color.BLACK)));

        documento.add(tabla(r));
        if (r.note() != null) {
            Paragraph nota = new Paragraph(r.note(), fuente(9, Font.ITALIC, GRIS));
            nota.setSpacingBefore(6f);
            documento.add(nota);
        }
        documento.close();
        return salida.toByteArray();
    }

    private PdfPTable tabla(ReportModel r) {
        List<Column> columnas = r.columns();
        int n = columnas.size();
        PdfPTable tabla = new PdfPTable(n);
        tabla.setWidthPercentage(100f);
        tabla.setWidths(columnas.stream().mapToInt(Column::width).toArray());
        tabla.setHeaderRows(1);

        for (Column columna : columnas) {
            PdfPCell celda = celda(columna.title(), fuente(9, Font.BOLD, Color.WHITE), CARBON,
                    columna.type() == ColumnType.MONEY ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            tabla.addCell(celda);
        }

        if (r.rows().isEmpty()) {
            PdfPCell vacia = celda(r.emptyMessage(), fuente(9, Font.ITALIC, GRIS), null, Element.ALIGN_CENTER);
            vacia.setColspan(n);
            tabla.addCell(vacia);
        }
        for (int i = 0; i < r.rows().size(); i++) {
            Color fondo = i % 2 == 1 ? FILA_ALTERNA : null;
            List<Object> fila = r.rows().get(i);
            for (int c = 0; c < n; c++) {
                tabla.addCell(valor(fila.get(c), columnas.get(c).type(), false, fondo, null));
            }
        }

        for (Total total : r.totals()) {
            filaTotal(tabla, n, total, FONDO_TOTAL, null);
        }
        if (r.result() != null) {
            boolean positivo = r.result().amount() >= 0;
            filaTotal(tabla, n, r.result(), positivo ? FONDO_POSITIVO : FONDO_NEGATIVO,
                    positivo ? TEXTO_POSITIVO : ROJO_NEGATIVO);
        }
        return tabla;
    }

    /** Etiqueta en todas las columnas menos la ultima, y el monto en la ultima. */
    private void filaTotal(PdfPTable tabla, int n, Total total, Color fondo, Color colorTexto) {
        PdfPCell etiqueta = celda(total.label(), fuente(9, Font.BOLD, colorTexto != null ? colorTexto : Color.BLACK),
                fondo, Element.ALIGN_LEFT);
        etiqueta.setColspan(n - 1);
        tabla.addCell(etiqueta);
        tabla.addCell(valor(total.amount(), ColumnType.MONEY, true, fondo, colorTexto));
    }

    private PdfPCell valor(Object valor, ColumnType tipo, boolean negrita, Color fondo, Color colorForzado) {
        int estilo = negrita ? Font.BOLD : Font.NORMAL;
        return switch (tipo) {
            case DATE -> celda(((LocalDate) valor).format(FECHA), fuente(9, estilo, Color.BLACK), fondo,
                    Element.ALIGN_LEFT);
            case MONEY -> {
                double monto = (Double) valor;
                Color color = colorForzado != null ? colorForzado : monto < 0 ? ROJO_NEGATIVO : Color.BLACK;
                yield celda(moneda(monto), fuente(9, estilo, color), fondo, Element.ALIGN_RIGHT);
            }
            case TEXT -> celda(String.valueOf(valor), fuente(9, estilo, Color.BLACK), fondo, Element.ALIGN_LEFT);
        };
    }

    private static PdfPCell celda(String texto, Font fuente, Color fondo, int alineacion) {
        PdfPCell celda = new PdfPCell(new Phrase(texto, fuente));
        celda.setPadding(5f);
        celda.setHorizontalAlignment(alineacion);
        celda.setBorderColor(new Color(0xE5, 0xE7, 0xEB));
        if (fondo != null) {
            celda.setBackgroundColor(fondo);
        }
        return celda;
    }

    /** Pesos sin decimales, punto de miles y el signo menos adelante: -$ 29.310.000. */
    static String moneda(double monto) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols();
        simbolos.setGroupingSeparator('.');
        long redondeado = Math.round(Math.abs(monto));
        String cifra = new DecimalFormat("#,##0", simbolos).format(redondeado);
        return (monto < 0 && redondeado != 0 ? "-" : "") + "$ " + cifra;
    }

    private static Font fuente(float tamano, int estilo, Color color) {
        return new Font(Font.HELVETICA, tamano, estilo, color);
    }

    /** Segunda pasada: "<generado> · Pag. N de M" centrado al pie de cada pagina. */
    private static byte[] conPie(byte[] pdf, String generado) {
        try {
            PdfReader lector = new PdfReader(pdf);
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            PdfStamper estampador = new PdfStamper(lector, salida);
            int paginas = lector.getNumberOfPages();
            Font fuente = fuente(8, Font.NORMAL, GRIS);
            for (int i = 1; i <= paginas; i++) {
                Rectangle pagina = lector.getPageSize(i);
                ColumnText.showTextAligned(estampador.getOverContent(i), Element.ALIGN_CENTER,
                        new Phrase(generado + " · Pág. " + i + " de " + paginas, fuente),
                        pagina.getWidth() / 2, MARGEN / 2, 0);
            }
            estampador.close();
            lector.close();
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF", e);
        }
    }
}
