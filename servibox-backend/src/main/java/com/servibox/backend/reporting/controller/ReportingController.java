package com.servibox.backend.reporting.controller;

import com.servibox.backend.reporting.dto.IvaReportResponse;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.dto.MovementSummaryResponse;
import com.servibox.backend.reporting.export.ExcelReportGenerator;
import com.servibox.backend.reporting.export.PdfReportGenerator;
import com.servibox.backend.reporting.export.ReportFormat;
import com.servibox.backend.reporting.export.ReportModel;
import com.servibox.backend.reporting.export.ReportType;
import com.servibox.backend.reporting.service.ReportExportService;
import com.servibox.backend.reporting.service.ReportingService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * KPIs, movimientos e IVA en JSON, y la exportacion de reportes en PDF y Excel
 * (GET /export). Ver 03-DECISIONS.md, "Exportacion de reportes en PDF y Excel".
 *
 * desde / hasta son fechas ISO (2026-09-01). Van opcionales en la firma para que, si
 * faltan, respondan 400 con el mensaje del service y no el 500 generico.
 */
@RestController
@RequestMapping("/api/reporting")
@RequiredArgsConstructor
public class ReportingController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportingService reportingService;
    private final ReportExportService reportExportService;
    private final PdfReportGenerator pdfReportGenerator;
    private final ExcelReportGenerator excelReportGenerator;

    /** Sin desde ni hasta: KPIs globales. Con los dos: KPIs del periodo. */
    @GetMapping("/kpis")
    public KpisResponse kpis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        if (desde == null && hasta == null) {
            return reportingService.getGlobalKpis();
        }
        return reportingService.getPeriodKpis(desde, hasta);
    }

    @GetMapping("/movements")
    public List<MovementSummaryResponse> movements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportingService.getMovements(desde, hasta);
    }

    @GetMapping("/iva")
    public IvaReportResponse iva(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportingService.buildReporteIva(desde, hasta);
    }

    /**
     * El reporte como archivo descargable. Sin `produces`: el cliente manda
     * Accept: application/json y con produces la respuesta seria un 406. type y format van
     * opcionales en la firma para que su falta sea 400 con mensaje, no el 500 generico.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportar(
            @RequestParam(required = false) ReportType type,
            @RequestParam(required = false) ReportFormat format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long accountId) {
        if (type == null) {
            throw new IllegalArgumentException("Falta el parametro type: ESTADO_RESULTADOS o MOVIMIENTOS");
        }
        if (format == null) {
            throw new IllegalArgumentException("Falta el parametro format: PDF o XLSX");
        }
        if (type == ReportType.ESTADO_RESULTADOS && accountId != null) {
            throw new IllegalArgumentException("accountId solo aplica al reporte MOVIMIENTOS");
        }

        ReportModel reporte = type == ReportType.ESTADO_RESULTADOS
                ? reportExportService.estadoDeResultados(desde, hasta)
                : reportExportService.movimientos(desde, hasta, accountId);
        byte[] archivo = format == ReportFormat.PDF
                ? pdfReportGenerator.generar(reporte)
                : excelReportGenerator.generar(reporte);

        String nombre = (type == ReportType.ESTADO_RESULTADOS ? "estado-resultados" : "movimientos-cuentas")
                + "_" + desde + "_" + hasta + (format == ReportFormat.PDF ? ".pdf" : ".xlsx");
        return ResponseEntity.ok()
                .contentType(format == ReportFormat.PDF ? MediaType.APPLICATION_PDF : XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(archivo);
    }
}
