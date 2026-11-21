package com.servibox.backend.reporting.service;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.StockStatus;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.reporting.dto.CashFlowReport;
import com.servibox.backend.reporting.dto.IncomeStatementReport;
import com.servibox.backend.reporting.dto.InventoryReport;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.dto.LiveReportResponse;
import com.servibox.backend.reporting.dto.MovementsReport;
import com.servibox.backend.reporting.dto.PortfolioReport;
import com.servibox.backend.reporting.dto.ProductsSoldReport;
import com.servibox.backend.reporting.dto.PurchasesReport;
import com.servibox.backend.reporting.dto.SalesReport;
import com.servibox.backend.reporting.dto.SeriesPoint;
import com.servibox.backend.reporting.dto.VatReport;
import com.servibox.backend.reporting.repository.LiveReportRepository;
import com.servibox.backend.reporting.repository.LiveReportRepository.FacturaFila;
import com.servibox.backend.reporting.repository.LiveReportRepository.ProductoFila;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.shared.DomainValidationException;
import com.servibox.backend.shared.Moneda;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.repository.MovementRepositoryCustom.FilaReporte;
import com.servibox.backend.treasury.service.TreasuryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Los reportes en vivo: Ventas, Productos y servicios vendidos, Compras, Cartera por cobrar,
 * Cartera por pagar, Inventario, Flujo de caja, Estado de resultados, IVA y Movimientos, con
 * indicadores, filas y serie. Los mismos datos alimentan el JSON y la
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
    private final TreasuryService treasuryService;
    private final Clock clock;

    /** Tope de filas de un reporte, el mismo de la exportacion de movimientos. */
    @Value("${servibox.reporting.max-export-rows}")
    private int maxFilas;

    public record Rango(LocalDate desde, LocalDate hasta) {
    }

    // ---- Ventas ----

    @Transactional(readOnly = true)
    public LiveReportResponse<SalesReport.Summary, SalesReport.Row, SeriesPoint> ventas(
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
    public LiveReportResponse<ProductsSoldReport.Summary, ProductsSoldReport.Row, SeriesPoint> productosVendidos(
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
    public LiveReportResponse<PurchasesReport.Summary, PurchasesReport.Row, SeriesPoint> compras(
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
    public LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row, SeriesPoint> carteraPorCobrar(
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
    public LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row, SeriesPoint> carteraPorPagar(
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
    private LiveReportResponse<PortfolioReport.Summary, PortfolioReport.Row, SeriesPoint> cartera(
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

    // ---- Inventario valorizado y rotacion ----

    /**
     * Stock y valor al dia de hoy de los items PRODUCTO (los servicios no tienen stock); unidades
     * vendidas en el rango, por defecto el mes en curso. Estado de stock con StockStatus, la regla
     * del frontend. Orden: valor al costo de mayor a menor.
     */
    @Transactional(readOnly = true)
    public LiveReportResponse<InventoryReport.Summary, InventoryReport.Row, SeriesPoint> inventario(
            LocalDate desde, LocalDate hasta, Long categoriaId, StockStatus estadoStock) {
        Rango rango = rango(desde, hasta);
        LocalDate corte = LocalDate.now(clock);
        List<String> filtros = new ArrayList<>();
        if (categoriaId != null) {
            filtros.add("Categoría: " + categoria(categoriaId).getName());
        }
        agregar(filtros, "Estado de stock", estadoStock);

        List<InventoryReport.Row> filas = new ArrayList<>();
        for (LiveReportRepository.InventarioFila p : repository.inventario(rango.desde(), rango.hasta(), categoriaId)) {
            StockStatus estado = StockStatus.of(p.stock(), p.yellowStockMin(), p.redStockMin());
            if (estadoStock != null && estado != estadoStock) {
                continue;
            }
            Long diasSinVender = p.lastSaleDate() == null ? null
                    : Math.max(0, ChronoUnit.DAYS.between(p.lastSaleDate(), corte));
            filas.add(new InventoryReport.Row(p.productId(), p.code(), p.description(), p.categoryName(), p.stock(),
                    estado, Moneda.aCentavos(p.unitCost()), multiplicar(p.unitCost(), p.stock()),
                    Moneda.aCentavos(p.salePrice()), p.unitsSold(), p.lastSaleDate(), diasSinVender));
        }
        filas.sort(Comparator.comparingDouble(InventoryReport.Row::costValue).reversed()
                .thenComparing(InventoryReport.Row::code));

        long unidades = 0;
        long vendidas = 0;
        double costo = 0.0;
        double venta = 0.0;
        Map<StockStatus, Long> porEstado = new EnumMap<>(StockStatus.class);
        for (InventoryReport.Row f : filas) {
            unidades += Math.max(0, f.stock());
            vendidas += f.unitsSold();
            costo = Moneda.sumar(costo, f.costValue());
            venta = Moneda.sumar(venta, multiplicar(f.salePrice(), f.stock()));
            porEstado.merge(f.stockStatus(), 1L, Long::sum);
        }
        List<SeriesPoint> serie = filas.stream().filter(f -> f.unitsSold() > 0)
                .sorted(Comparator.comparingLong(InventoryReport.Row::unitsSold).reversed()
                        .thenComparing(InventoryReport.Row::code))
                .limit(TOP_SERIE)
                .map(f -> new SeriesPoint(f.description(), f.unitsSold()))
                .toList();
        return new LiveReportResponse<>(rango.desde(), rango.hasta(), corte, filtros,
                new InventoryReport.Summary(filas.size(), unidades, costo, venta,
                        porEstado.getOrDefault(StockStatus.BAJO, 0L), porEstado.getOrDefault(StockStatus.CRITICO, 0L),
                        porEstado.getOrDefault(StockStatus.SIN_STOCK, 0L), vendidas),
                filas, serie);
    }

    // ---- Flujo de caja ----

    /** Los conceptos del flujo de caja, en el orden de la tabla. */
    private enum Concepto {
        VENTAS_CONTADO("Ventas de contado", true),
        ABONOS_CLIENTES("Abonos de clientes", true),
        INGRESOS_OCASIONALES("Ingresos ocasionales", true),
        TRANSFERENCIAS_RECIBIDAS("Transferencias recibidas", true),
        COMPRAS_CONTADO("Compras de contado", false),
        PAGOS_PROVEEDORES("Pagos a proveedores", false),
        GASTOS_OPERATIVOS("Gastos operativos", false),
        TRANSFERENCIAS_ENVIADAS("Transferencias enviadas", false);

        private final String label;
        private final boolean entrada;

        Concepto(String label, boolean entrada) {
            this.label = label;
            this.entrada = entrada;
        }

        static Concepto de(LiveReportRepository.FlujoFila f) {
            return switch (f.category()) {
                case RECAUDO_VENTA -> f.salePaymentType() == PaymentType.CONTADO ? VENTAS_CONTADO : ABONOS_CLIENTES;
                case PAGO_COMPRA -> f.purchasePaymentType() == com.servibox.backend.purchases.entity.PaymentType.CONTADO
                        ? COMPRAS_CONTADO : PAGOS_PROVEEDORES;
                case INGRESO_OCASIONAL -> INGRESOS_OCASIONALES;
                case GASTO_OPERATIVO -> GASTOS_OPERATIVOS;
                case TRANSFERENCIA -> f.type() == MovementType.INGRESO ? TRANSFERENCIAS_RECIBIDAS : TRANSFERENCIAS_ENVIADAS;
            };
        }

        boolean esTransferencia() {
            return this == TRANSFERENCIAS_RECIBIDAS || this == TRANSFERENCIAS_ENVIADAS;
        }
    }

    /**
     * Entradas y salidas de caja del rango por concepto, con los movimientos activos (los de
     * facturas anuladas no cuentan). Saldo inicial al cierre del dia anterior a desde y final al
     * cierre de hasta, con TreasuryService.saldoAl. Sin cuenta, las transferencias entre cuentas
     * propias se excluyen: suman lo mismo en las dos patas y solo inflarian entradas y salidas;
     * el saldo de todas las cuentas no cambia con ellas. Con cuenta, son entradas y salidas reales.
     */
    @Transactional(readOnly = true)
    public LiveReportResponse<CashFlowReport.Summary, CashFlowReport.Row, SeriesPoint> flujoDeCaja(
            LocalDate desde, LocalDate hasta, Long cuentaId) {
        Rango rango = rango(desde, hasta);
        List<String> filtros = new ArrayList<>();
        List<Account> cuentas;
        if (cuentaId != null) {
            Account cuenta = cuenta(cuentaId);
            filtros.add("Cuenta: " + cuenta.getName());
            cuentas = List.of(cuenta);
        } else {
            cuentas = treasuryService.findAllAccounts();
        }
        boolean conTransferencias = cuentaId != null;

        Map<Concepto, long[]> conteos = new EnumMap<>(Concepto.class);
        Map<Concepto, Double> montos = new EnumMap<>(Concepto.class);
        for (Concepto c : Concepto.values()) {
            if (conTransferencias || !c.esTransferencia()) {
                conteos.put(c, new long[1]);
                montos.put(c, 0.0);
            }
        }
        for (LiveReportRepository.FlujoFila f : repository.flujoPorConcepto(rango.desde(), rango.hasta(), cuentaId)) {
            Concepto c = Concepto.de(f);
            if (!conteos.containsKey(c)) {
                continue;
            }
            conteos.get(c)[0] += f.count();
            montos.merge(c, f.amount(), Moneda::sumar);
        }
        List<CashFlowReport.Row> filas = new ArrayList<>();
        double entradas = 0.0;
        double salidas = 0.0;
        for (Concepto c : conteos.keySet()) {
            double monto = montos.get(c);
            filas.add(new CashFlowReport.Row(c.name(), c.label, c.entrada ? "ENTRADA" : "SALIDA", conteos.get(c)[0], monto));
            if (c.entrada) {
                entradas = Moneda.sumar(entradas, monto);
            } else {
                salidas = Moneda.sumar(salidas, monto);
            }
        }

        double inicial = 0.0;
        double fin = 0.0;
        for (Account cuenta : cuentas) {
            inicial = Moneda.sumar(inicial, treasuryService.saldoAl(cuenta, rango.desde().minusDays(1)));
            fin = Moneda.sumar(fin, treasuryService.saldoAl(cuenta, rango.hasta()));
        }

        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, filtros,
                new CashFlowReport.Summary(inicial, entradas, salidas, Moneda.restar(entradas, salidas), fin),
                filas, netoPorDia(rango, repository.movimientosPorDia(rango.desde(), rango.hasta(), cuentaId, null,
                        conTransferencias)));
    }

    // ---- Estado de resultados ----

    /**
     * Las cifras de getPeriodKpis, las mismas de /kpis y de la exportacion ESTADO_RESULTADOS,
     * con el porcentaje de cada linea sobre las ventas sin IVA. Serie por mes si el rango cruza
     * meses, si no por dia.
     */
    @Transactional(readOnly = true)
    public LiveReportResponse<IncomeStatementReport.Summary, IncomeStatementReport.Row, IncomeStatementReport.Point>
    estadoDeResultados(LocalDate desde, LocalDate hasta) {
        Rango rango = rango(desde, hasta);
        KpisResponse k = reportingService.getPeriodKpis(rango.desde(), rango.hasta());
        double ventas = k.netSales();
        List<IncomeStatementReport.Row> filas = List.of(
                linea("VENTAS", "Ventas sin IVA", ventas, ventas),
                linea("COSTO_VENTAS", "Costo de lo vendido", negativo(k.costOfSales()), ventas),
                linea("UTILIDAD_BRUTA", "Utilidad bruta", k.grossProfit(), ventas),
                linea("OTROS_INGRESOS", "Otros ingresos", k.otherIncome(), ventas),
                linea("GASTOS_OPERATIVOS", "Gastos operativos", negativo(k.operatingExpenses()), ventas),
                linea("UTILIDAD_NETA", "Utilidad neta", k.netProfit(), ventas));

        boolean porMes = !YearMonth.from(rango.desde()).equals(YearMonth.from(rango.hasta()));
        Map<String, double[]> puntos = new LinkedHashMap<>();
        for (LocalDate dia = rango.desde(); !dia.isAfter(rango.hasta()); dia = dia.plusDays(1)) {
            puntos.putIfAbsent(clave(dia, porMes), new double[3]);
        }
        for (Object[] f : repository.ventasYCostoPorDia(rango.desde(), rango.hasta())) {
            double[] p = puntos.get(clave((LocalDate) f[0], porMes));
            p[0] = Moneda.sumar(p[0], ((Number) f[1]).doubleValue());
            p[1] = Moneda.sumar(p[1], ((Number) f[2]).doubleValue());
        }
        for (Object[] f : repository.gastosEIngresosPorDia(rango.desde(), rango.hasta())) {
            double[] p = puntos.get(clave((LocalDate) f[0], porMes));
            double monto = ((Number) f[2]).doubleValue();
            p[2] = f[1] == MovementCategory.INGRESO_OCASIONAL ? Moneda.sumar(p[2], monto) : Moneda.restar(p[2], monto);
        }
        List<IncomeStatementReport.Point> serie = new ArrayList<>();
        puntos.forEach((etiqueta, p) -> serie.add(new IncomeStatementReport.Point(etiqueta, p[0], p[1],
                Moneda.sumar(Moneda.restar(p[0], p[1]), p[2]))));

        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, List.of(),
                new IncomeStatementReport.Summary(ventas, k.costOfSales(), k.grossProfit(),
                        porcentaje(k.grossProfit(), ventas), k.operatingExpenses(), k.otherIncome(), k.netProfit(),
                        porcentaje(k.netProfit(), ventas)),
                filas, serie);
    }

    private static IncomeStatementReport.Row linea(String clave, String etiqueta, double monto, double ventas) {
        return new IncomeStatementReport.Row(clave, etiqueta, monto, porcentaje(monto, ventas));
    }

    private static String clave(LocalDate dia, boolean porMes) {
        return porMes ? YearMonth.from(dia).toString() : dia.toString();
    }

    // ---- IVA ----

    /**
     * Las lineas de buildReporteIva (/iva), agrupadas por categoria y tarifa: la tarifa es el IVA
     * generado de la linea sobre su base, congelado al facturar, en por ciento a un decimal. Los
     * totales son los de /iva.
     */
    @Transactional(readOnly = true)
    public LiveReportResponse<VatReport.Summary, VatReport.Row, SeriesPoint> iva(LocalDate desde, LocalDate hasta) {
        Rango rango = rango(desde, hasta);
        // Por categoria y tarifa: [base, generado, descontable, neto]
        Map<String, double[]> grupos = new LinkedHashMap<>();
        Map<String, Object[]> claves = new LinkedHashMap<>();
        Set<Long> facturas = new HashSet<>();
        double generado = 0.0;
        double descontable = 0.0;
        double neto = 0.0;
        for (LiveReportRepository.IvaFila l : repository.lineasConIva(rango.desde(), rango.hasta())) {
            double tarifa = l.base() == 0 ? 0.0 : BigDecimal.valueOf(l.ivaAmount()).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(l.base()), 1, RoundingMode.HALF_UP).doubleValue();
            String clave = l.categoryName() + "|" + tarifa;
            claves.putIfAbsent(clave, new Object[]{l.categoryName(), tarifa});
            double[] g = grupos.computeIfAbsent(clave, k -> new double[4]);
            double desc = Moneda.restar(l.ivaAmount(), l.ivaDifference());
            g[0] = Moneda.sumar(g[0], l.base());
            g[1] = Moneda.sumar(g[1], l.ivaAmount());
            g[2] = Moneda.sumar(g[2], desc);
            g[3] = Moneda.sumar(g[3], l.ivaDifference());
            generado = Moneda.sumar(generado, l.ivaAmount());
            descontable = Moneda.sumar(descontable, desc);
            neto = Moneda.sumar(neto, l.ivaDifference());
            facturas.add(l.saleId());
        }
        List<VatReport.Row> filas = new ArrayList<>();
        grupos.forEach((clave, g) -> filas.add(new VatReport.Row((String) claves.get(clave)[0],
                (Double) claves.get(clave)[1], g[0], g[1], g[2], g[3])));
        filas.sort(Comparator.comparingDouble(VatReport.Row::ivaGenerado).reversed()
                .thenComparing(VatReport.Row::categoryName).thenComparingDouble(VatReport.Row::ratePercent));
        List<SeriesPoint> serie = filas.stream()
                .map(f -> new SeriesPoint(f.categoryName() + " " + tarifa(f.ratePercent()), f.ivaNeto())).toList();
        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, List.of(),
                new VatReport.Summary(generado, descontable, neto, facturas.size()), filas, serie);
    }

    /** 19.0 es "19 %"; 2.5 es "2,5 %". */
    public static String tarifa(double porcentaje) {
        return BigDecimal.valueOf(porcentaje).stripTrailingZeros().toPlainString().replace('.', ',') + " %";
    }

    // ---- Movimientos ----

    /**
     * Los movimientos activos del rango, del mas antiguo al mas reciente, de una cuenta o de todas
     * y de un tipo o de los dos: la misma consulta de la exportacion MOVIMIENTOS. Las
     * transferencias aparecen en sus dos patas, como en la exportacion.
     */
    @Transactional(readOnly = true)
    public LiveReportResponse<MovementsReport.Summary, MovementsReport.Row, SeriesPoint> movimientos(
            LocalDate desde, LocalDate hasta, Long cuentaId, MovementType tipo) {
        Rango rango = rango(desde, hasta);
        List<String> filtros = new ArrayList<>();
        if (cuentaId != null) {
            filtros.add("Cuenta: " + cuenta(cuentaId).getName());
        }
        agregar(filtros, "Tipo", tipo);
        List<FilaReporte> movimientos = treasuryService.listarParaReporte(cuentaId, tipo, rango.desde(), rango.hasta(),
                maxFilas + 1);
        if (movimientos.size() > maxFilas) {
            throw new DomainValidationException("El reporte tiene mas de " + maxFilas
                    + " movimientos: acota el rango de fechas");
        }
        List<MovementsReport.Row> filas = new ArrayList<>();
        List<LiveReportRepository.DiaFila> porDia = new ArrayList<>();
        double entradas = 0.0;
        double salidas = 0.0;
        for (FilaReporte m : movimientos) {
            filas.add(new MovementsReport.Row(m.id(), m.date(), m.accountName(), m.concept(), referencia(m), m.type(),
                    m.category(), m.amount()));
            porDia.add(new LiveReportRepository.DiaFila(m.date(), m.type(), m.amount()));
            if (m.type() == MovementType.INGRESO) {
                entradas = Moneda.sumar(entradas, m.amount());
            } else {
                salidas = Moneda.sumar(salidas, m.amount());
            }
        }
        return new LiveReportResponse<>(rango.desde(), rango.hasta(), null, filtros,
                new MovementsReport.Summary(entradas, salidas, Moneda.restar(entradas, salidas), filas.size()),
                filas, netoPorDia(rango, porDia));
    }

    /** No hay codigos propios de gasto, ingreso ni transferencia: solo la factura tiene numero. */
    public static String referencia(FilaReporte m) {
        return switch (m.category()) {
            case RECAUDO_VENTA -> "VENTA " + m.saleInvoiceNumber();
            case PAGO_COMPRA -> "COMPRA " + m.purchaseInvoiceNumber();
            case GASTO_OPERATIVO -> "GASTO OPERATIVO";
            case INGRESO_OCASIONAL -> "INGRESO OCASIONAL";
            case TRANSFERENCIA -> "TRANSFERENCIA";
        };
    }

    /** Un punto por dia del rango: ingresos menos egresos de ese dia. */
    private static List<SeriesPoint> netoPorDia(Rango rango, List<LiveReportRepository.DiaFila> filas) {
        Map<LocalDate, Double> neto = new LinkedHashMap<>();
        for (LocalDate dia = rango.desde(); !dia.isAfter(rango.hasta()); dia = dia.plusDays(1)) {
            neto.put(dia, 0.0);
        }
        for (LiveReportRepository.DiaFila f : filas) {
            neto.merge(f.date(), f.type() == MovementType.INGRESO ? f.amount() : -f.amount(), Moneda::sumar);
        }
        List<SeriesPoint> serie = new ArrayList<>();
        neto.forEach((dia, valor) -> serie.add(new SeriesPoint(dia.toString(), valor)));
        return serie;
    }

    // ---- Apoyo ----

    private Account cuenta(Long id) {
        return treasuryService.findAccountById(id)
                .orElseThrow(() -> new DomainValidationException("Cuenta no encontrada: " + id));
    }

    private static double multiplicar(double unitario, int cantidad) {
        return Moneda.aCentavos(BigDecimal.valueOf(unitario).multiply(BigDecimal.valueOf(Math.max(0, cantidad))));
    }

    /** Sin -0.0: un cero restado se muestra como $ 0. */
    private static double negativo(double valor) {
        return valor == 0.0 ? 0.0 : -valor;
    }

    /** Sin desde ni hasta, el mes en curso; si no, las reglas de los demas reportes y 366 dias de tope. */
    public Rango rango(LocalDate desde, LocalDate hasta) {
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
            case "CRITICO" -> "Crítico";
            case "SIN_STOCK" -> "Sin stock";
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
