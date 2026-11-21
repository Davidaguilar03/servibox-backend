package com.servibox.backend.reporting.service;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.reporting.dto.LiveReportResponse;
import com.servibox.backend.reporting.dto.PortfolioReport;
import com.servibox.backend.reporting.dto.ProductsSoldReport;
import com.servibox.backend.reporting.dto.PurchasesReport;
import com.servibox.backend.reporting.dto.SalesReport;
import com.servibox.backend.reporting.dto.SeriesPoint;
import com.servibox.backend.reporting.repository.LiveReportRepository;
import com.servibox.backend.reporting.repository.LiveReportRepository.FacturaFila;
import com.servibox.backend.reporting.repository.LiveReportRepository.ProductoFila;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.shared.DomainValidationException;
import com.servibox.backend.shared.Moneda;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los reportes en vivo: Ventas, Productos y servicios vendidos, Compras, Cartera por cobrar y
 * Cartera por pagar, con indicadores, filas y serie. Los mismos datos alimentan el JSON y la
 * exportacion (ReportExportService), asi que pantalla y archivo cuadran.
 *
 * Rango: sin desde ni hasta, el mes en curso del reloj de negocio; con uno solo, 400. Las
 * carteras son una foto al dia de hoy y no usan rango. Ver "Reportes en vivo" en
 * 03-DECISIONS.md.
 */
@Service
@RequiredArgsConstructor
public class LiveReportService {

    /** La tolerancia de saldo pendiente de Sales y Purchases: menos de un peso no se debe. */
    static final double TOLERANCIA_PESOS = 1.0;

    private static final int TOP_SERIE = 10;

    private final LiveReportRepository repository;
    private final ReportingService reportingService;
    private final CounterpartyService counterpartyService;
    private final InventoryService inventoryService;
    private final Clock clock;

    /** Tope de filas de un reporte, el mismo de la exportacion de movimientos. */
    @Value("${servibox.reporting.max-export-rows}")
    private int maxFilas;

    public record Rango(LocalDate desde, LocalDate hasta) {
    }

    // ---- Ventas ----

    @Transactional(readOnly = true)
    public LiveReportResponse<SalesReport.Summary, SalesReport.Row> ventas(
            LocalDate desde, LocalDate hasta, Long clienteId, PaymentType formaPago, SaleStatus estado) {
        Rango rango = rango(desde, hasta);
        List<String> filtros = new ArrayList<>();
        if (clienteId != null) {
            filtros.add("Cliente: " + cliente(clienteId).getName());
        }
        agregar(filtros, "Forma de pago", formaPago);
        agregar(filtros, "Estado", estado);

        List<FacturaFila> facturas = topeFilas(repository.ventas(rango.desde(), rango.hasta(), clienteId, formaPago,
                estado, maxFilas + 1), "facturas", "acota el rango de fechas");

        List<SalesReport.Row> filas = new ArrayList<>();
        Map<LocalDate, Double> porDia = new LinkedHashMap<>();
        for (LocalDate dia = rango.desde(); !dia.isAfter(rango.hasta()); dia = dia.plusDays(1)) {
            porDia.put(dia, 0.0);
        }
        double total = 0.0;
        double contado = 0.0;
        double credito = 0.0;
        long cuenta = 0;
        long anuladas = 0;
        for (FacturaFila f : facturas) {
            boolean anulada = f.status() == SaleStatus.ANULADA;
            filas.add(new SalesReport.Row(f.id(), f.invoiceNumber(), f.invoiceDate(), f.counterpartyId(),
                    f.counterpartyName(), (PaymentType) f.paymentType(), (SaleStatus) f.status(),
                    Moneda.aCentavos(f.subtotal()), Moneda.aCentavos(f.iva()), Moneda.aCentavos(f.total()),
                    anulada ? 0.0 : saldo(f)));
            if (anulada) {
                anuladas++;
                continue;
            }
            cuenta++;
            total = Moneda.sumar(total, f.total());
            if (f.paymentType() == PaymentType.CONTADO) {
                contado = Moneda.sumar(contado, f.total());
            } else {
                credito = Moneda.sumar(credito, f.total());
            }
            porDia.merge(f.invoiceDate(), f.total(), Moneda::sumar);
        }

        List<SeriesPoint> serie = new ArrayList<>();
        porDia.forEach((dia, valor) -> serie.add(new SeriesPoint(dia.toString(), valor)));
        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, filtros,
                new SalesReport.Summary(total, cuenta, promedio(total, cuenta), contado, credito, anuladas),
                filas, serie);
    }

    // ---- Productos y servicios vendidos ----

    @Transactional(readOnly = true)
    public LiveReportResponse<ProductsSoldReport.Summary, ProductsSoldReport.Row> productosVendidos(
            LocalDate desde, LocalDate hasta, ItemType itemType, Long categoriaId, Long productoId) {
        Rango rango = rango(desde, hasta);
        List<String> filtros = new ArrayList<>();
        agregar(filtros, "Tipo", itemType);
        if (categoriaId != null) {
            filtros.add("Categoría: " + categoria(categoriaId).getName());
        }
        if (productoId != null) {
            Product producto = inventoryService.findProductById(productoId)
                    .orElseThrow(() -> new DomainValidationException("Producto no encontrado: " + productoId));
            filtros.add("Ítem: " + producto.getCode() + " " + producto.getDescription());
        }

        List<ProductsSoldReport.Row> filas = new ArrayList<>();
        long unidades = 0;
        double ingresos = 0.0;
        double costo = 0.0;
        for (ProductoFila p : repository.productosVendidos(rango.desde(), rango.hasta(), itemType, categoriaId,
                productoId)) {
            double ingreso = Moneda.aCentavos(p.revenue());
            double costoFila = Moneda.aCentavos(p.cost());
            double margen = Moneda.restar(ingreso, costoFila);
            filas.add(new ProductsSoldReport.Row(p.productId(), p.code(), p.description(), p.itemType(),
                    p.categoryName(), p.units(), ingreso, costoFila, margen, porcentaje(margen, ingreso)));
            unidades += p.units();
            ingresos = Moneda.sumar(ingresos, ingreso);
            costo = Moneda.sumar(costo, costoFila);
        }
        double margenTotal = Moneda.restar(ingresos, costo);

        List<SeriesPoint> serie = filas.stream().limit(TOP_SERIE)
                .map(f -> new SeriesPoint(f.description(), f.revenue())).toList();
        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, filtros,
                new ProductsSoldReport.Summary(unidades, ingresos, costo, margenTotal,
                        porcentaje(margenTotal, ingresos)),
                filas, serie);
    }

    // ---- Compras ----

    @Transactional(readOnly = true)
    public LiveReportResponse<PurchasesReport.Summary, PurchasesReport.Row> compras(
            LocalDate desde, LocalDate hasta, Long proveedorId, Long categoriaId, PurchaseStatus estado) {
        Rango rango = rango(desde, hasta);
        List<String> filtros = new ArrayList<>();
        if (proveedorId != null) {
            filtros.add("Proveedor: " + tercero(proveedorId, "Proveedor").getName());
        }
        if (categoriaId != null) {
            filtros.add("Categoría: " + categoria(categoriaId).getName());
        }
        agregar(filtros, "Estado", estado);

        List<FacturaFila> facturas = topeFilas(repository.compras(rango.desde(), rango.hasta(), proveedorId,
                categoriaId, estado, maxFilas + 1), "facturas", "acota el rango de fechas");

        List<PurchasesReport.Row> filas = new ArrayList<>();
        Map<String, Double> porProveedor = new LinkedHashMap<>();
        double total = 0.0;
        double iva = 0.0;
        double pendiente = 0.0;
        long cuenta = 0;
        for (FacturaFila f : facturas) {
            boolean anulada = f.status() == PurchaseStatus.ANULADA;
            double saldo = anulada ? 0.0 : saldo(f);
            filas.add(new PurchasesReport.Row(f.id(), f.invoiceNumber(), f.invoiceDate(), f.counterpartyId(),
                    f.counterpartyName(), (PurchaseStatus) f.status(), Moneda.aCentavos(f.subtotal()),
                    Moneda.aCentavos(f.iva()), Moneda.aCentavos(f.total()), saldo));
            if (anulada) {
                continue;
            }
            cuenta++;
            total = Moneda.sumar(total, f.total());
            iva = Moneda.sumar(iva, f.iva());
            pendiente = Moneda.sumar(pendiente, saldo);
            porProveedor.merge(f.counterpartyName(), f.total(), Moneda::sumar);
        }

        List<SeriesPoint> serie = porProveedor.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_SERIE)
                .map(e -> new SeriesPoint(e.getKey(), e.getValue()))
                .toList();
        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, filtros,
                new PurchasesReport.Summary(total, cuenta, iva, pendiente), filas, serie);
    }

    // ---- Carteras ----

    /** Facturas de venta PENDIENTE con saldo, al dia de hoy. */
    @Transactional(readOnly = true)
    public LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row> carteraPorCobrar(
            Long clienteId, AgingRange antiguedad) {
        List<String> filtros = new ArrayList<>();
        if (clienteId != null) {
            filtros.add("Cliente: " + cliente(clienteId).getName());
        }
        return cartera(topeFilas(repository.ventasPendientes(clienteId, maxFilas + 1), "facturas pendientes",
                "filtra por cliente"), antiguedad, filtros);
    }

    /** Facturas de compra PENDIENTE con saldo, al dia de hoy. */
    @Transactional(readOnly = true)
    public LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row> carteraPorPagar(
            Long proveedorId, AgingRange antiguedad) {
        List<String> filtros = new ArrayList<>();
        if (proveedorId != null) {
            filtros.add("Proveedor: " + tercero(proveedorId, "Proveedor").getName());
        }
        return cartera(topeFilas(repository.comprasPendientes(proveedorId, maxFilas + 1), "facturas pendientes",
                "filtra por proveedor"), antiguedad, filtros);
    }

    /**
     * La antiguedad son los dias desde la fecha de vencimiento hasta hoy; sin vencimiento,
     * desde la fecha de la factura. Una factura que aun no vence tiene 0 dias (rango 0-30).
     */
    private LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row> cartera(
            List<FacturaFila> facturas, AgingRange antiguedad, List<String> filtros) {
        LocalDate corte = LocalDate.now(clock);
        if (antiguedad != null) {
            filtros.add("Antigüedad: " + antiguedad.label() + " días");
        }
        Map<AgingRange, Double> porRango = new EnumMap<>(AgingRange.class);
        for (AgingRange r : AgingRange.values()) {
            porRango.put(r, 0.0);
        }
        List<PortfolioReport.Row> filas = new ArrayList<>();
        double total = 0.0;
        for (FacturaFila f : facturas) {
            double saldo = saldo(f);
            if (saldo <= 0) {
                continue;
            }
            LocalDate base = f.dueDate() != null ? f.dueDate() : f.invoiceDate();
            long dias = Math.max(0, ChronoUnit.DAYS.between(base, corte));
            AgingRange rango = AgingRange.of(dias);
            if (antiguedad != null && rango != antiguedad) {
                continue;
            }
            filas.add(new PortfolioReport.Row(f.id(), f.counterpartyId(), f.counterpartyName(), f.invoiceNumber(),
                    f.invoiceDate(), f.dueDate(), dias, rango.label(), Moneda.aCentavos(f.total()),
                    Moneda.aCentavos(f.paid()), saldo));
            total = Moneda.sumar(total, saldo);
            porRango.merge(rango, saldo, Moneda::sumar);
        }
        List<SeriesPoint> serie = new ArrayList<>();
        porRango.forEach((r, valor) -> serie.add(new SeriesPoint(r.label(), valor)));
        return new LiveReportResponse<>(null, null, corte, filtros,
                new PortfolioReport.Summary(total, filas.size(), porRango.get(AgingRange.D0_30),
                        porRango.get(AgingRange.D31_60), porRango.get(AgingRange.D61_90),
                        porRango.get(AgingRange.D90_MAS)),
                filas, serie);
    }

    // ---- Apoyo ----

    /** Sin desde ni hasta, el mes en curso; si no, las reglas de los demas reportes y 366 dias de tope. */
    Rango rango(LocalDate desde, LocalDate hasta) {
        if (desde == null && hasta == null) {
            LocalDate hoy = LocalDate.now(clock);
            return new Rango(hoy.withDayOfMonth(1), hoy.withDayOfMonth(hoy.lengthOfMonth()));
        }
        reportingService.validarRango(desde, hasta);
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > ReportExportService.MAX_DIAS) {
            throw new DomainValidationException("El rango de un reporte no puede pasar de "
                    + ReportExportService.MAX_DIAS + " dias");
        }
        return new Rango(desde, hasta);
    }

    private List<FacturaFila> topeFilas(List<FacturaFila> facturas, String que, String como) {
        if (facturas.size() > maxFilas) {
            throw new DomainValidationException("El reporte tiene mas de " + maxFilas + " " + que + ": " + como);
        }
        return facturas;
    }

    /** total menos lo abonado o pagado, con la tolerancia de Sales y Purchases. */
    private static double saldo(FacturaFila f) {
        double pendiente = Moneda.restar(f.total(), f.paid());
        return pendiente < TOLERANCIA_PESOS ? 0.0 : pendiente;
    }

    private Counterparty cliente(Long id) {
        return tercero(id, "Cliente");
    }

    private Counterparty tercero(Long id, String rol) {
        return counterpartyService.findById(id)
                .orElseThrow(() -> new DomainValidationException(rol + " no encontrado: " + id));
    }

    private ProductCategory categoria(Long id) {
        return inventoryService.findCategoryById(id)
                .orElseThrow(() -> new DomainValidationException("Categoria no encontrada: " + id));
    }

    private static void agregar(List<String> filtros, String nombre, Enum<?> valor) {
        if (valor != null) {
            filtros.add(nombre + ": " + etiqueta(valor));
        }
    }

    /** El valor de un enum como texto de pantalla: CREDITO es "Crédito". */
    public static String etiqueta(Enum<?> valor) {
        return switch (valor.name()) {
            case "CREDITO" -> "Crédito";
            default -> valor.name().charAt(0) + valor.name().substring(1).toLowerCase();
        };
    }

    private static double promedio(double total, long cuenta) {
        return cuenta == 0 ? 0.0 : Moneda.aCentavos(BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(cuenta), 2, RoundingMode.HALF_UP));
    }

    /** parte sobre total en por ciento, a 2 decimales; 0 si el total es 0. */
    private static double porcentaje(double parte, double total) {
        return total == 0 ? 0.0 : BigDecimal.valueOf(parte).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).doubleValue();
    }
}
