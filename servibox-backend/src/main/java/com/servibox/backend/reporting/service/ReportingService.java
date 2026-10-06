package com.servibox.backend.reporting.service;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.purchases.repository.PurchaseRepository;
import com.servibox.backend.reporting.dto.IvaCategoryResponse;
import com.servibox.backend.reporting.dto.IvaReportResponse;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.dto.MovementSummaryResponse;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.repository.SaleDetailRepository;
import com.servibox.backend.sales.repository.SaleRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.OccasionalIncome;
import com.servibox.backend.treasury.entity.OperationalExpense;
import com.servibox.backend.treasury.repository.OccasionalIncomeRepository;
import com.servibox.backend.treasury.repository.OperationalExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Solo lectura: agrega los datos de Sales, Purchases y Treasury, sin entidades propias.
 * Las facturas ANULADA de venta y de compra se excluyen de todo. El filtro es por estado,
 * asi que una factura que vuelva a PENDIENTE vuelve a contar sin hacer nada mas.
 *
 * El tenant lo aplica el filtro de Hibernate: todas las lecturas son consultas, ninguna
 * pasa por findById.
 */
@Service
@RequiredArgsConstructor
public class ReportingService {

    /** Maximo de renglones de getMovements, portado de DashboardService.MAX_MOVEMENTS. */
    public static final int MAX_MOVEMENTS = 100;

    private final SaleRepository saleRepository;
    private final SaleDetailRepository saleDetailRepository;
    private final PurchaseRepository purchaseRepository;
    private final OperationalExpenseRepository operationalExpenseRepository;
    private final OccasionalIncomeRepository occasionalIncomeRepository;

    /** Los 5 KPIs sobre todo el historico del tenant activo. */
    @Transactional(readOnly = true)
    public KpisResponse getGlobalKpis() {
        return KpisResponse.of(null, null,
                saleRepository.sumTotalByStatusNot(SaleStatus.ANULADA),
                purchaseRepository.sumTotalByStatusNot(PurchaseStatus.ANULADA),
                operationalExpenseRepository.sumAmount());
    }

    /** Los mismos 5 KPIs, por fecha de factura (o de gasto) dentro de [desde, hasta]. */
    @Transactional(readOnly = true)
    public KpisResponse getPeriodKpis(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);
        return KpisResponse.of(desde, hasta,
                saleRepository.sumTotalByStatusNotAndInvoiceDateBetween(SaleStatus.ANULADA, desde, hasta),
                purchaseRepository.sumTotalByStatusNotAndInvoiceDateBetween(PurchaseStatus.ANULADA, desde, hasta),
                operationalExpenseRepository.sumAmountByDateBetween(desde, hasta));
    }

    /**
     * Ventas, compras, gastos operativos e ingresos ocasionales del rango, del mas reciente
     * al mas antiguo, cortado en MAX_MOVEMENTS. Portado de DashboardService.getMovements.
     */
    @Transactional(readOnly = true)
    public List<MovementSummaryResponse> getMovements(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);
        List<MovementSummaryResponse> lista = new ArrayList<>();

        for (Sale venta : saleRepository.findByInvoiceDateBetweenAndStatusNot(desde, hasta, SaleStatus.ANULADA)) {
            String cliente = venta.getCustomer() != null ? venta.getCustomer().getName() : null;
            lista.add(renglon(venta.getInvoiceDate(), "VENTA", venta.getId(),
                    concepto(venta.getInvoiceNumber(), cliente), venta.getTotal(), venta.getAccount()));
        }
        for (Purchase compra : purchaseRepository.findByInvoiceDateBetweenAndStatusNot(desde, hasta, PurchaseStatus.ANULADA)) {
            String proveedor = compra.getSupplier() != null ? compra.getSupplier().getName() : null;
            lista.add(renglon(compra.getInvoiceDate(), "COMPRA", compra.getId(),
                    concepto(compra.getInvoiceNumber(), proveedor), compra.getTotal(), compra.getAccount()));
        }
        for (OperationalExpense gasto : operationalExpenseRepository.findByDateBetween(desde, hasta)) {
            lista.add(renglon(gasto.getDate(), "GASTO", gasto.getId(),
                    gasto.getConcept(), gasto.getAmount(), gasto.getAccount()));
        }
        for (OccasionalIncome ingreso : occasionalIncomeRepository.findByDateBetween(desde, hasta)) {
            lista.add(renglon(ingreso.getDate(), "INGRESO", ingreso.getId(),
                    ingreso.getConcept(), ingreso.getAmount(), ingreso.getAccount()));
        }

        lista.sort(Comparator.comparing(MovementSummaryResponse::date).reversed()
                .thenComparing(MovementSummaryResponse::sourceId, Comparator.nullsLast(Comparator.reverseOrder())));
        return lista.size() > MAX_MOVEMENTS ? new ArrayList<>(lista.subList(0, MAX_MOVEMENTS)) : lista;
    }

    /**
     * Reporte de IVA del rango, agrupado por categoria de producto. Sale de las lineas de
     * venta: ivaAmount es el IVA generado e ivaDifference el neto, los dos congelados al
     * facturar; el descontable es la resta. Portado de ReportService.buildReporteIva, sin
     * el fallback para lineas sin ivaAmount (ver 03-DECISIONS.md).
     */
    @Transactional(readOnly = true)
    public IvaReportResponse buildReporteIva(LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);
        // Por categoria: [generado, descontable, neto]
        Map<String, double[]> porCategoria = new LinkedHashMap<>();
        Set<Long> facturasConIva = new HashSet<>();

        for (SaleDetail linea : saleDetailRepository.findBySale_InvoiceDateBetweenAndSale_StatusNot(
                desde, hasta, SaleStatus.ANULADA)) {
            double generado = linea.getIvaAmount() != null ? linea.getIvaAmount() : 0.0;
            if (linea.getProduct() == null || generado <= 0) {
                continue;
            }
            double neto = linea.getIvaDifference() != null ? linea.getIvaDifference() : generado;
            String categoria = linea.getProduct().getCategory() != null
                    ? linea.getProduct().getCategory().getName() : "Sin categoria";

            double[] fila = porCategoria.computeIfAbsent(categoria, k -> new double[3]);
            fila[0] += generado;
            fila[1] += generado - neto;
            fila[2] += neto;
            facturasConIva.add(linea.getSale().getId());
        }

        List<IvaCategoryResponse> categorias = new ArrayList<>();
        porCategoria.forEach((nombre, f) -> categorias.add(new IvaCategoryResponse(nombre, f[0], f[1], f[2])));
        categorias.sort(Comparator.comparingDouble(IvaCategoryResponse::ivaGenerado).reversed());

        return new IvaReportResponse(desde, hasta, categorias, facturasConIva.size(),
                categorias.stream().mapToDouble(IvaCategoryResponse::ivaGenerado).sum(),
                categorias.stream().mapToDouble(IvaCategoryResponse::ivaDescontable).sum(),
                categorias.stream().mapToDouble(IvaCategoryResponse::ivaNeto).sum());
    }

    private void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) {
            throw new IllegalArgumentException("El rango necesita fecha desde y fecha hasta");
        }
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }
    }

    private static String concepto(String numero, String tercero) {
        return tercero == null || tercero.isBlank() ? numero : numero + " - " + tercero;
    }

    private static MovementSummaryResponse renglon(LocalDate fecha, String tipo, Long id, String concepto,
                                                   Double monto, Account cuenta) {
        return new MovementSummaryResponse(fecha, tipo, id, concepto, monto != null ? monto : 0.0,
                cuenta != null ? cuenta.getId() : null,
                cuenta != null ? cuenta.getName() : null);
    }
}
