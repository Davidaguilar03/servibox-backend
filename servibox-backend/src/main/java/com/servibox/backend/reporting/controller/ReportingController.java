package com.servibox.backend.reporting.controller;

import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.reporting.dto.LiveReportResponse;
import com.servibox.backend.reporting.dto.PortfolioReport;
import com.servibox.backend.reporting.dto.ProductsSoldReport;
import com.servibox.backend.reporting.dto.PurchasesReport;
import com.servibox.backend.reporting.dto.SalesReport;
import com.servibox.backend.reporting.service.AgingRange;
import com.servibox.backend.reporting.service.LiveReportService;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.shared.DomainValidationException;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final LiveReportService liveReportService;
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

    // ---- Reportes en vivo. Sin desde ni hasta: el mes en curso. ----

    @GetMapping("/sales")
    public LiveReportResponse<SalesReport.Summary, SalesReport.Row> sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) PaymentType formaPago,
            @RequestParam(required = false) SaleStatus estado) {
        return liveReportService.ventas(desde, hasta, clienteId, formaPago, estado);
    }

    @GetMapping("/products-sold")
    public LiveReportResponse<ProductsSoldReport.Summary, ProductsSoldReport.Row> productsSold(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) ItemType itemType,
            @RequestParam(required = false) Long categoriaId,
            @RequestParam(required = false) Long productoId) {
        return liveReportService.productosVendidos(desde, hasta, itemType, categoriaId, productoId);
    }

    @GetMapping("/purchases")
    public LiveReportResponse<PurchasesReport.Summary, PurchasesReport.Row> purchases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long proveedorId,
            @RequestParam(required = false) Long categoriaId,
            @RequestParam(required = false) PurchaseStatus estado) {
        return liveReportService.compras(desde, hasta, proveedorId, categoriaId, estado);
    }

    /** Foto al dia de hoy: no usa desde ni hasta. antiguedad: 0-30, 31-60, 61-90 o 90+. */
    @GetMapping("/receivables")
    public LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row> receivables(
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) String antiguedad) {
        return liveReportService.carteraPorCobrar(clienteId, AgingRange.parse(antiguedad));
    }

    @GetMapping("/payables")
    public LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row> payables(
            @RequestParam(required = false) Long proveedorId,
            @RequestParam(required = false) String antiguedad) {
        return liveReportService.carteraPorPagar(proveedorId, AgingRange.parse(antiguedad));
    }

    /** Los filtros que acepta cada reporte en /export, ademas de desde y hasta. */
    private static final Map<ReportType, Set<String>> FILTROS = Map.of(
            ReportType.ESTADO_RESULTADOS, Set.of(),
            ReportType.MOVIMIENTOS, Set.of("accountId"),
            ReportType.VENTAS, Set.of("clienteId", "formaPago", "estado"),
            ReportType.PRODUCTOS_VENDIDOS, Set.of("itemType", "categoriaId", "productoId"),
            ReportType.COMPRAS, Set.of("proveedorId", "categoriaId", "estado"),
            ReportType.CARTERA_COBRAR, Set.of("clienteId", "antiguedad"),
            ReportType.CARTERA_PAGAR, Set.of("proveedorId", "antiguedad"));

    /**
     * El reporte como archivo descargable, con los mismos filtros que su ruta JSON. Sin
     * `produces`: el cliente manda Accept: application/json y con produces la respuesta seria un
     * 406. type y format van opcionales en la firma para que su falta sea 400 con mensaje, no el
     * 500 generico. Un filtro que no es del reporte es 400.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportar(
            @RequestParam(required = false) ReportType type,
            @RequestParam(required = false) ReportFormat format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Long proveedorId,
            @RequestParam(required = false) PaymentType formaPago,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) ItemType itemType,
            @RequestParam(required = false) Long categoriaId,
            @RequestParam(required = false) Long productoId,
            @RequestParam(required = false) String antiguedad) {
        if (type == null) {
            throw new DomainValidationException("Falta el parametro type: " + Arrays.stream(ReportType.values())
                    .map(Enum::name).collect(Collectors.joining(", ")));
        }
        if (format == null) {
            throw new DomainValidationException("Falta el parametro format: PDF o XLSX");
        }
        if (type != ReportType.MOVIMIENTOS && accountId != null) {
            throw new DomainValidationException("accountId solo aplica al reporte MOVIMIENTOS");
        }
        Map<String, Object> filtros = new LinkedHashMap<>();
        filtros.put("clienteId", clienteId);
        filtros.put("proveedorId", proveedorId);
        filtros.put("formaPago", formaPago);
        filtros.put("estado", estado);
        filtros.put("itemType", itemType);
        filtros.put("categoriaId", categoriaId);
        filtros.put("productoId", productoId);
        filtros.put("antiguedad", antiguedad);
        filtros.forEach((nombre, valor) -> {
            if (valor != null && !FILTROS.get(type).contains(nombre)) {
                throw new DomainValidationException("El parametro " + nombre + " no aplica al reporte " + type);
            }
        });

        ReportModel reporte = switch (type) {
            case ESTADO_RESULTADOS -> reportExportService.estadoDeResultados(desde, hasta);
            case MOVIMIENTOS -> reportExportService.movimientos(desde, hasta, accountId);
            case VENTAS -> reportExportService.ventas(desde, hasta, clienteId, formaPago,
                    estado(SaleStatus.class, estado));
            case PRODUCTOS_VENDIDOS -> reportExportService.productosVendidos(desde, hasta, itemType, categoriaId,
                    productoId);
            case COMPRAS -> reportExportService.compras(desde, hasta, proveedorId, categoriaId,
                    estado(PurchaseStatus.class, estado));
            case CARTERA_COBRAR -> reportExportService.carteraPorCobrar(clienteId, AgingRange.parse(antiguedad));
            case CARTERA_PAGAR -> reportExportService.carteraPorPagar(proveedorId, AgingRange.parse(antiguedad));
        };
        byte[] archivo = format == ReportFormat.PDF
                ? pdfReportGenerator.generar(reporte)
                : excelReportGenerator.generar(reporte);

        String fechas = type.esCorte() ? String.valueOf(reporte.hasta()) : reporte.desde() + "_" + reporte.hasta();
        String nombre = type.archivo() + "_" + fechas + (format == ReportFormat.PDF ? ".pdf" : ".xlsx");
        return ResponseEntity.ok()
                .contentType(format == ReportFormat.PDF ? MediaType.APPLICATION_PDF : XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(archivo);
    }

    /** estado llega como texto porque ventas y compras tienen su propio enum; mismo 400 que un enum. */
    private static <E extends Enum<E>> E estado(Class<E> tipo, String valor) {
        if (valor == null) {
            return null;
        }
        return Arrays.stream(tipo.getEnumConstants()).filter(e -> e.name().equals(valor)).findFirst()
                .orElseThrow(() -> new DomainValidationException("Valor invalido '" + valor
                        + "' en el parametro estado. Valores permitidos: " + Arrays.stream(tipo.getEnumConstants())
                        .map(Enum::name).collect(Collectors.joining(", "))));
    }
}
