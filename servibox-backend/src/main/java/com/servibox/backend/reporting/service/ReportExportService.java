package com.servibox.backend.reporting.service;

import com.servibox.backend.shared.DomainValidationException;
import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.dto.LiveReportResponse;
import com.servibox.backend.reporting.dto.PortfolioReport;
import com.servibox.backend.reporting.dto.ProductsSoldReport;
import com.servibox.backend.reporting.dto.PurchasesReport;
import com.servibox.backend.reporting.dto.SalesReport;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.reporting.export.ReportModel;
import com.servibox.backend.reporting.export.ReportModel.Column;
import com.servibox.backend.reporting.export.ReportModel.ColumnType;
import com.servibox.backend.reporting.export.ReportModel.Total;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.repository.MovementRepositoryCustom.FilaReporte;
import com.servibox.backend.treasury.service.TreasuryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.servibox.backend.shared.Moneda;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Los datos de los reportes exportables, ya calculados. No genera archivos: eso lo hacen
 * PdfReportGenerator y ExcelReportGenerator con el ReportModel que devuelve este servicio.
 */
@Service
@RequiredArgsConstructor
public class ReportExportService {

    /** Rango maximo de un reporte, en dias contando los dos extremos. */
    public static final long MAX_DIAS = 366;

    public static final String SIN_MOVIMIENTOS = "Sin movimientos en el período";

    private final ReportingService reportingService;
    private final LiveReportService liveReportService;
    private final TreasuryService treasuryService;
    private final TenantRepository tenantRepository;

    /** Tope de filas del reporte de movimientos; mas que eso es 400. */
    @Value("${servibox.reporting.max-export-rows}")
    private int maxFilas;

    /**
     * Seis filas con las cifras de GET /api/reporting/kpis para el mismo rango, del mismo servicio:
     * ventas sin IVA, costo de lo vendido, utilidad bruta, otros ingresos, gastos operativos y
     * utilidad neta. El archivo cuadra con el panel. Las compras no son fila: el costo de lo
     * vendido las reemplaza. Otros ingresos son los ingresos ocasionales, igual que en kpis.
     */
    @Transactional(readOnly = true)
    public ReportModel estadoDeResultados(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);
        Tenant tenant = tenantActual();
        KpisResponse kpis = reportingService.getPeriodKpis(desde, hasta);
        List<List<Object>> filas = List.of(
                List.of("Ventas sin IVA", kpis.netSales()),
                List.of("Costo de lo vendido", negativo(kpis.costOfSales())),
                List.of("Utilidad bruta", kpis.grossProfit()),
                List.of("Otros ingresos", kpis.otherIncome()),
                List.of("Gastos operativos", negativo(kpis.operatingExpenses())));
        return new ReportModel(tenant.getName(), tenant.getNit(), "Estado de resultados", desde, hasta, null,
                List.of(new Column("Concepto", ColumnType.TEXT, 60), new Column("Valor", ColumnType.MONEY, 22)),
                filas, SIN_MOVIMIENTOS, List.of(), new Total("Utilidad neta", kpis.netProfit()),
                "Las facturas anuladas no se incluyen", false);
    }

    /**
     * Movimientos activos del rango, del mas antiguo al mas reciente, con la misma regla que
     * GET /api/treasury/movements. Con una cuenta, ademas su saldo al inicio y al final.
     */
    @Transactional(readOnly = true)
    public ReportModel movimientos(LocalDate desde, LocalDate hasta, Long accountId) {
        validarRango(desde, hasta);
        Tenant tenant = tenantActual();
        Account cuenta = null;
        if (accountId != null) {
            cuenta = treasuryService.findAccountById(accountId)
                    .orElseThrow(() -> new DomainValidationException("Cuenta no encontrada: " + accountId));
        }

        List<FilaReporte> movimientos = treasuryService.listarParaReporte(accountId, desde, hasta, maxFilas + 1);
        if (movimientos.size() > maxFilas) {
            throw new DomainValidationException("El reporte tiene mas de " + maxFilas
                    + " movimientos: acota el rango de fechas");
        }

        double ingresos = 0.0;
        double egresos = 0.0;
        List<List<Object>> filas = new ArrayList<>();
        for (FilaReporte m : movimientos) {
            boolean ingreso = m.type() == MovementType.INGRESO;
            if (ingreso) {
                ingresos = Moneda.sumar(ingresos, m.amount());
            } else {
                egresos = Moneda.sumar(egresos, m.amount());
            }
            filas.add(List.of(m.date(), m.accountName(), ingreso ? "Ingreso" : "Egreso", m.concept(),
                    origen(m), ingreso ? m.amount() : -m.amount()));
        }

        List<Total> totales = new ArrayList<>();
        if (cuenta != null) {
            totales.add(new Total("Saldo inicial del período", treasuryService.saldoAl(cuenta, desde.minusDays(1))));
        }
        totales.add(new Total("Ingresos", ingresos));
        totales.add(new Total("Egresos", negativo(egresos)));
        totales.add(new Total("Neto", Moneda.restar(ingresos, egresos)));
        if (cuenta != null) {
            totales.add(new Total("Saldo final", treasuryService.saldoAl(cuenta, hasta)));
        }

        return new ReportModel(tenant.getName(), tenant.getNit(), "Movimientos de cuentas", desde, hasta,
                cuenta != null ? "Cuenta: " + cuenta.getName() : "Todas las cuentas",
                List.of(new Column("Fecha", ColumnType.DATE, 12),
                        new Column("Cuenta", ColumnType.TEXT, 18),
                        new Column("Tipo", ColumnType.TEXT, 10),
                        new Column("Concepto", ColumnType.TEXT, 30),
                        new Column("Origen", ColumnType.TEXT, 24),
                        new Column("Monto", ColumnType.MONEY, 16)),
                filas, SIN_MOVIMIENTOS, totales, null, null, true);
    }

    // ---- Reportes en vivo: las mismas cifras del JSON de LiveReportService ----

    public static final String SIN_FACTURAS = "Sin facturas en el período";
    private static final String NOTA_ANULADAS =
            "Las facturas anuladas aparecen en la tabla pero no suman en los totales";

    @Transactional(readOnly = true)
    public ReportModel ventas(LocalDate desde, LocalDate hasta, Long clienteId, PaymentType formaPago,
                              SaleStatus estado) {
        LiveReportResponse<SalesReport.Summary, SalesReport.Row> r =
                liveReportService.ventas(desde, hasta, clienteId, formaPago, estado);
        Tenant tenant = tenantActual();
        List<List<Object>> filas = new ArrayList<>();
        double subtotal = 0.0;
        double iva = 0.0;
        double saldo = 0.0;
        for (SalesReport.Row f : r.rows()) {
            filas.add(List.of(f.invoiceNumber(), f.invoiceDate(), f.customerName(),
                    LiveReportService.etiqueta(f.paymentType()), LiveReportService.etiqueta(f.status()),
                    f.subtotal(), f.iva(), f.total(), f.pendingBalance()));
            if (f.status() != SaleStatus.ANULADA) {
                subtotal = Moneda.sumar(subtotal, f.subtotal());
                iva = Moneda.sumar(iva, f.iva());
                saldo = Moneda.sumar(saldo, f.pendingBalance());
            }
        }
        SalesReport.Summary s = r.summary();
        return new ReportModel(tenant.getName(), tenant.getNit(), "Ventas", r.desde(), r.hasta(), filtros(r),
                List.of(new Column("Factura", ColumnType.TEXT, 12),
                        new Column("Fecha", ColumnType.DATE, 11),
                        new Column("Cliente", ColumnType.TEXT, 24),
                        new Column("Forma de pago", ColumnType.TEXT, 12),
                        new Column("Estado", ColumnType.TEXT, 11),
                        new Column("Subtotal", ColumnType.MONEY, 15),
                        new Column("IVA", ColumnType.MONEY, 13),
                        new Column("Total", ColumnType.MONEY, 15),
                        new Column("Saldo pendiente", ColumnType.MONEY, 15)),
                filas, SIN_FACTURAS,
                List.of(new Total("Total vendido", s.totalSold()),
                        new Total("Número de facturas", s.invoiceCount(), ColumnType.INTEGER),
                        new Total("Ticket promedio", s.averageTicket()),
                        new Total("Total de contado", s.cashTotal()),
                        new Total("Total a crédito", s.creditTotal()),
                        new Total("Facturas anuladas", s.annulledCount(), ColumnType.INTEGER)),
                null, NOTA_ANULADAS, true,
                Arrays.asList("Totales", null, null, null, null, subtotal, iva, s.totalSold(), saldo));
    }

    @Transactional(readOnly = true)
    public ReportModel productosVendidos(LocalDate desde, LocalDate hasta, ItemType itemType, Long categoriaId,
                                         Long productoId) {
        LiveReportResponse<ProductsSoldReport.Summary, ProductsSoldReport.Row> r =
                liveReportService.productosVendidos(desde, hasta, itemType, categoriaId, productoId);
        Tenant tenant = tenantActual();
        List<List<Object>> filas = new ArrayList<>();
        for (ProductsSoldReport.Row f : r.rows()) {
            filas.add(List.of(f.code(), f.description(), LiveReportService.etiqueta(f.itemType()),
                    f.categoryName(), f.units(), f.revenue(), f.cost(), f.margin(), f.marginPercent()));
        }
        ProductsSoldReport.Summary s = r.summary();
        return new ReportModel(tenant.getName(), tenant.getNit(), "Productos y servicios vendidos", r.desde(),
                r.hasta(), filtros(r),
                List.of(new Column("Código", ColumnType.TEXT, 11),
                        new Column("Descripción", ColumnType.TEXT, 26),
                        new Column("Tipo", ColumnType.TEXT, 10),
                        new Column("Categoría", ColumnType.TEXT, 14),
                        new Column("Unidades", ColumnType.INTEGER, 10),
                        new Column("Ingresos sin IVA", ColumnType.MONEY, 15),
                        new Column("Costo", ColumnType.MONEY, 15),
                        new Column("Margen", ColumnType.MONEY, 15),
                        new Column("Margen %", ColumnType.PERCENT, 10)),
                filas, "Sin ventas en el período", List.of(), null,
                "Sin facturas anuladas. El costo es el congelado en cada línea de venta; los servicios tienen costo 0",
                true, Arrays.asList("Totales", null, null, null, s.unitsSold(), s.revenue(), s.cost(),
                        s.grossMargin(), s.marginPercent()));
    }

    @Transactional(readOnly = true)
    public ReportModel compras(LocalDate desde, LocalDate hasta, Long proveedorId, Long categoriaId,
                               PurchaseStatus estado) {
        LiveReportResponse<PurchasesReport.Summary, PurchasesReport.Row> r =
                liveReportService.compras(desde, hasta, proveedorId, categoriaId, estado);
        Tenant tenant = tenantActual();
        List<List<Object>> filas = new ArrayList<>();
        double subtotal = 0.0;
        for (PurchasesReport.Row f : r.rows()) {
            filas.add(List.of(f.invoiceNumber(), f.invoiceDate(), f.supplierName(),
                    LiveReportService.etiqueta(f.status()), f.subtotal(), f.iva(), f.total(), f.pendingBalance()));
            if (f.status() != PurchaseStatus.ANULADA) {
                subtotal = Moneda.sumar(subtotal, f.subtotal());
            }
        }
        PurchasesReport.Summary s = r.summary();
        return new ReportModel(tenant.getName(), tenant.getNit(), "Compras", r.desde(), r.hasta(), filtros(r),
                List.of(new Column("Factura", ColumnType.TEXT, 12),
                        new Column("Fecha", ColumnType.DATE, 11),
                        new Column("Proveedor", ColumnType.TEXT, 26),
                        new Column("Estado", ColumnType.TEXT, 11),
                        new Column("Subtotal", ColumnType.MONEY, 15),
                        new Column("IVA", ColumnType.MONEY, 13),
                        new Column("Total", ColumnType.MONEY, 15),
                        new Column("Saldo pendiente", ColumnType.MONEY, 15)),
                filas, SIN_FACTURAS,
                List.of(new Total("Total comprado", s.totalPurchased()),
                        new Total("Número de facturas", s.invoiceCount(), ColumnType.INTEGER),
                        new Total("IVA descontable", s.deductibleVat()),
                        new Total("Total pendiente de pago", s.pendingTotal())),
                null, NOTA_ANULADAS, true,
                Arrays.asList("Totales", null, null, null, subtotal, s.deductibleVat(), s.totalPurchased(),
                        s.pendingTotal()));
    }

    @Transactional(readOnly = true)
    public ReportModel carteraPorCobrar(Long clienteId, AgingRange antiguedad) {
        return cartera("Cartera por cobrar", "Cliente", "Abonado",
                liveReportService.carteraPorCobrar(clienteId, antiguedad));
    }

    @Transactional(readOnly = true)
    public ReportModel carteraPorPagar(Long proveedorId, AgingRange antiguedad) {
        return cartera("Cartera por pagar", "Proveedor", "Pagado",
                liveReportService.carteraPorPagar(proveedorId, antiguedad));
    }

    private ReportModel cartera(String titulo, String tercero, String abonado,
                                LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row> r) {
        Tenant tenant = tenantActual();
        List<List<Object>> filas = new ArrayList<>();
        double total = 0.0;
        double pagado = 0.0;
        for (PortfolioReport.Row f : r.rows()) {
            filas.add(Arrays.asList(f.counterpartyName(), f.invoiceNumber(), f.invoiceDate(), f.dueDate(),
                    f.daysOld(), f.total(), f.paid(), f.balance()));
            total = Moneda.sumar(total, f.total());
            pagado = Moneda.sumar(pagado, f.paid());
        }
        PortfolioReport.Summary s = r.summary();
        return new ReportModel(tenant.getName(), tenant.getNit(), titulo, null, r.corte(), filtros(r),
                List.of(new Column(tercero, ColumnType.TEXT, 24),
                        new Column("Factura", ColumnType.TEXT, 12),
                        new Column("Fecha", ColumnType.DATE, 11),
                        new Column("Vence", ColumnType.DATE, 11),
                        new Column("Días", ColumnType.INTEGER, 7),
                        new Column("Total", ColumnType.MONEY, 15),
                        new Column(abonado, ColumnType.MONEY, 15),
                        new Column("Saldo", ColumnType.MONEY, 15)),
                filas, "Sin facturas pendientes",
                List.of(new Total("Facturas pendientes", s.invoiceCount(), ColumnType.INTEGER),
                        new Total("Saldo de 0 a 30 días", s.balance0To30()),
                        new Total("Saldo de 31 a 60 días", s.balance31To60()),
                        new Total("Saldo de 61 a 90 días", s.balance61To90()),
                        new Total("Saldo de más de 90 días", s.balanceOver90())),
                new Total("Saldo total", s.totalBalance()),
                "Días desde el vencimiento, o desde la fecha de la factura si no tiene; las que aún no vencen tienen 0",
                true, Arrays.asList("Totales", null, null, null, null, total, pagado, s.totalBalance()));
    }

    /** "Filtros: Cliente: X · Estado: Pendiente", o "Sin filtros". */
    private static String filtros(LiveReportResponse<?, ?> r) {
        return r.appliedFilters().isEmpty() ? "Sin filtros"
                : "Filtros: " + String.join(" · ", r.appliedFilters());
    }

    /** El rango de los reportes JSON, mas el tope de 366 dias. */
    private void validarRango(LocalDate desde, LocalDate hasta) {
        reportingService.validarRango(desde, hasta);
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS) {
            throw new DomainValidationException("El rango de un reporte no puede pasar de " + MAX_DIAS + " dias");
        }
    }

    /** No hay codigos propios de gasto, ingreso ni transferencia: solo la factura tiene numero. */
    private static String origen(FilaReporte m) {
        return switch (m.category()) {
            case RECAUDO_VENTA -> "VENTA " + m.saleInvoiceNumber();
            case PAGO_COMPRA -> "COMPRA " + m.purchaseInvoiceNumber();
            case GASTO_OPERATIVO -> "GASTO OPERATIVO";
            case INGRESO_OCASIONAL -> "INGRESO OCASIONAL";
            case TRANSFERENCIA -> "TRANSFERENCIA";
        };
    }

    /** El tenant es el del token; TENANTS no es una entidad por tenant, asi que va por id. */
    private Tenant tenantActual() {
        return tenantRepository.findById(TenantContext.getTenantId())
                .orElseThrow(() -> new IllegalStateException("No hay un tenant activo"));
    }

    /** Sin -0.0: un cero restado se muestra como $ 0. */
    private static double negativo(double valor) {
        return valor == 0.0 ? 0.0 : -valor;
    }
}
