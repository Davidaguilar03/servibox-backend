package com.servibox.backend.reporting.service;

import com.servibox.backend.reporting.dto.KpisResponse;
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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    private final TreasuryService treasuryService;
    private final TenantRepository tenantRepository;

    /** Tope de filas del reporte de movimientos; mas que eso es 400. */
    @Value("${servibox.reporting.max-export-rows}")
    private int maxFilas;

    /**
     * Las cinco cifras de GET /api/reporting/kpis para el mismo rango, del mismo servicio:
     * el archivo cuadra con el panel. Los ingresos ocasionales no entran, igual que en kpis.
     */
    @Transactional(readOnly = true)
    public ReportModel estadoDeResultados(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);
        KpisResponse kpis = reportingService.getPeriodKpis(desde, hasta);
        List<List<Object>> filas = List.of(
                List.of("Ventas totales", kpis.totalSales()),
                List.of("Compras totales", negativo(kpis.totalPurchases())),
                List.of("Utilidad bruta", kpis.grossProfit()),
                List.of("Gastos operativos", negativo(kpis.operatingExpenses())));
        return new ReportModel(nombreTenant(), "Estado de resultados", desde, hasta, null,
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
        Account cuenta = null;
        if (accountId != null) {
            cuenta = treasuryService.findAccountById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada: " + accountId));
        }

        List<FilaReporte> movimientos = treasuryService.listarParaReporte(accountId, desde, hasta, maxFilas + 1);
        if (movimientos.size() > maxFilas) {
            throw new IllegalArgumentException("El reporte tiene mas de " + maxFilas
                    + " movimientos: acota el rango de fechas");
        }

        double ingresos = 0.0;
        double egresos = 0.0;
        List<List<Object>> filas = new ArrayList<>();
        for (FilaReporte m : movimientos) {
            boolean ingreso = m.type() == MovementType.INGRESO;
            if (ingreso) {
                ingresos += m.amount();
            } else {
                egresos += m.amount();
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
        totales.add(new Total("Neto", ingresos - egresos));
        if (cuenta != null) {
            totales.add(new Total("Saldo final", treasuryService.saldoAl(cuenta, hasta)));
        }

        return new ReportModel(nombreTenant(), "Movimientos de cuentas", desde, hasta,
                cuenta != null ? "Cuenta: " + cuenta.getName() : "Todas las cuentas",
                List.of(new Column("Fecha", ColumnType.DATE, 12),
                        new Column("Cuenta", ColumnType.TEXT, 18),
                        new Column("Tipo", ColumnType.TEXT, 10),
                        new Column("Concepto", ColumnType.TEXT, 30),
                        new Column("Origen", ColumnType.TEXT, 24),
                        new Column("Monto", ColumnType.MONEY, 16)),
                filas, SIN_MOVIMIENTOS, totales, null, null, true);
    }

    /** El rango de los reportes JSON, mas el tope de 366 dias. */
    private void validarRango(LocalDate desde, LocalDate hasta) {
        reportingService.validarRango(desde, hasta);
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS) {
            throw new IllegalArgumentException("El rango de un reporte no puede pasar de " + MAX_DIAS + " dias");
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
    private String nombreTenant() {
        return tenantRepository.findById(TenantContext.getTenantId())
                .map(Tenant::getName)
                .orElseThrow(() -> new IllegalStateException("No hay un tenant activo"));
    }

    /** Sin -0.0: un cero restado se muestra como $ 0. */
    private static double negativo(double valor) {
        return valor == 0.0 ? 0.0 : -valor;
    }
}
