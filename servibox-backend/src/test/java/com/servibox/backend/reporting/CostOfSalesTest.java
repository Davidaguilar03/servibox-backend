package com.servibox.backend.reporting;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.service.ReportingService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.repository.SaleDetailRepository;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Costo de lo vendido: el costo unitario de cada linea de venta se congela al facturar con
 * el costo del producto, y kpis lo usa para la utilidad bruta (ventas sin IVA menos costo de
 * lo vendido). IVA del 19 por ciento (SalesFixture).
 */
@SpringBootTest
@ActiveProfiles("test")
class CostOfSalesTest {

    private static final double DELTA = 0.001;
    private static final LocalDate HOY = LocalDate.of(2026, 9, 15);

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private SaleDetailRepository saleDetailRepository;

    private ProductCategory categoria;
    private Counterparty cliente;
    private Counterparty proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        categoria = fixture.crearCategoriaConIva();
        cliente = fixture.crearCliente("Cliente Costo", "100200300");
        proveedor = fixture.crearProveedor("Proveedor Costo", "900200300");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Sale vender(String numero, LocalDate fecha, SalesService.LineaFactura... lineas) {
        return salesService.crearFactura(numero, cliente, fecha, fecha.plusDays(30), PaymentType.CREDITO, null, null,
                List.of(lineas));
    }

    private static SalesService.LineaFactura linea(Product producto, int cantidad, double precio) {
        return new SalesService.LineaFactura(producto.getId(), cantidad, precio);
    }

    private void comprar(String numero, Product producto, int cantidad, double precio) {
        purchasesService.crearFactura(numero, proveedor, HOY, HOY.plusDays(30),
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(producto.getId(), cantidad, precio)));
    }

    private List<Double> costos(Sale venta) {
        return saleDetailRepository.findBySaleId(venta.getId()).stream().map(SaleDetail::getUnitCost).toList();
    }

    private double costo(Product producto) {
        return inventoryService.findProductById(producto.getId()).orElseThrow().getPurchaseCost();
    }

    @Test
    void elCostoSeCongelaConElDelProductoYUnaCompraPosteriorNoLoCambia() {
        Product llanta = fixture.crearProducto(categoria, "LLA-COSTO", 10000.0, 10);
        Sale venta = vender("FV-1", HOY, linea(llanta, 2, 15000.0));
        assertThat(costos(venta)).containsExactly(10000.0);

        // La compra sube el costo del producto; la linea ya vendida conserva el suyo.
        comprar("FC-1", llanta, 5, 12000.0);
        assertThat(costo(llanta)).isEqualTo(12000.0);
        assertThat(costos(venta)).containsExactly(10000.0);
        assertThat(reportingService.getPeriodKpis(HOY, HOY).costOfSales()).isCloseTo(20000.0, within(DELTA));

        // Editar el producto tampoco toca el costo congelado.
        inventoryService.actualizarProducto(llanta.getId(), "LLA-COSTO", "Llanta editada", 9000.0, categoria);
        assertThat(costos(venta)).containsExactly(10000.0);

        // Una venta nueva toma el costo vigente.
        Sale segunda = vender("FV-2", HOY, linea(llanta, 1, 15000.0));
        assertThat(costos(segunda)).containsExactly(9000.0);
    }

    @Test
    void anularYRestaurarUnaVentaNoCambiaSuCostoCongelado() {
        Product llanta = fixture.crearProducto(categoria, "LLA-ANU", 10000.0, 10);
        Sale venta = vender("FV-1", HOY, linea(llanta, 2, 15000.0));
        comprar("FC-1", llanta, 5, 12000.0);

        salesService.anularFactura(venta.getId());
        assertThat(costos(venta)).containsExactly(10000.0);
        assertThat(reportingService.getPeriodKpis(HOY, HOY).costOfSales()).isEqualTo(0.0);

        salesService.restaurarFactura(venta.getId());
        assertThat(costos(venta)).containsExactly(10000.0);
        assertThat(reportingService.getPeriodKpis(HOY, HOY).costOfSales()).isCloseTo(20000.0, within(DELTA));
    }

    @Test
    void costOfSalesExcluyeAnuladasYRespetaElRangoDeFechas() {
        Product llanta = fixture.crearProducto(categoria, "LLA-RANGO", 100.0, 50);
        LocalDate desde = LocalDate.of(2026, 9, 1);
        LocalDate hasta = LocalDate.of(2026, 9, 30);
        vender("FV-AGO", LocalDate.of(2026, 8, 31), linea(llanta, 1, 300.0));  // fuera
        vender("FV-INI", desde, linea(llanta, 2, 300.0));                      // dentro, borde
        vender("FV-FIN", hasta, linea(llanta, 3, 300.0));                      // dentro, borde
        vender("FV-OCT", LocalDate.of(2026, 10, 1), linea(llanta, 4, 300.0));  // fuera
        Sale anulada = vender("FV-ANU", HOY, linea(llanta, 5, 300.0));
        salesService.anularFactura(anulada.getId());

        KpisResponse periodo = reportingService.getPeriodKpis(desde, hasta);
        assertThat(periodo.costOfSales()).isCloseTo(500.0, within(DELTA));
        assertThat(periodo.netSales()).isCloseTo(1500.0, within(DELTA));
        assertThat(periodo.grossProfit()).isCloseTo(1000.0, within(DELTA));

        KpisResponse global = reportingService.getGlobalKpis();
        assertThat(global.costOfSales()).isCloseTo(1000.0, within(DELTA));
        assertThat(global.netSales()).isCloseTo(3000.0, within(DELTA));
    }

    @Test
    void ventaDeDosLineasConCostosDistintosDaLaBrutaYLaNeta() {
        Product llanta = fixture.crearProducto(categoria, "LLA-DOS", 10000.0, 10);
        Product aceite = fixture.crearProducto(categoria, "ACE-DOS", 2500.0, 10);
        vender("FV-1", HOY, linea(llanta, 2, 15000.0), linea(aceite, 3, 4000.0));
        // Una compra no entra en la utilidad bruta, solo en totalPurchases.
        comprar("FC-1", aceite, 1, 2500.0);
        Account caja = fixture.crearCuenta("Caja");
        treasuryService.registrarIngresoOcasional(caja, "Chatarra", 1000.0, HOY, null);
        treasuryService.registrarGastoOperativo(caja, "Arriendo", 6000.0, HOY, null);

        KpisResponse kpis = reportingService.getPeriodKpis(HOY, HOY);
        // Ventas sin IVA: 2 * 15000 + 3 * 4000 = 42000; con IVA, 49980.
        assertThat(kpis.netSales()).isCloseTo(42000.0, within(DELTA));
        assertThat(kpis.totalSales()).isCloseTo(49980.0, within(DELTA));
        // Costo: 2 * 10000 + 3 * 2500 = 27500.
        assertThat(kpis.costOfSales()).isCloseTo(27500.0, within(DELTA));
        assertThat(kpis.totalPurchases()).isCloseTo(2975.0, within(DELTA));
        assertThat(kpis.grossProfit()).isCloseTo(14500.0, within(DELTA))
                .isCloseTo(kpis.netSales() - kpis.costOfSales(), within(DELTA));
        // Neta: 14500 + 1000 - 6000.
        assertThat(kpis.netProfit()).isCloseTo(9500.0, within(DELTA))
                .isCloseTo(kpis.grossProfit() + kpis.otherIncome() - kpis.operatingExpenses(), within(DELTA));
    }

    /**
     * costo_compra es NOT NULL en PRODUCTOS y la API exige purchaseCost, asi que un producto
     * con costo nulo no se puede guardar; SalesService guarda 0 si llegara a leer null. Lo que
     * si existe es un servicio sin costo de compra: costo 0, que vende con costo 0.
     */
    @Test
    void unProductoSinCostoVendeConCostoCero() {
        Product servicio = fixture.crearProducto(categoria, "SRV-ALINEA", 0.0, 10);
        Sale venta = vender("FV-1", HOY, linea(servicio, 1, 50000.0));

        assertThat(costos(venta)).containsExactly(0.0);
        KpisResponse kpis = reportingService.getPeriodKpis(HOY, HOY);
        assertThat(kpis.costOfSales()).isEqualTo(0.0);
        assertThat(kpis.grossProfit()).isCloseTo(50000.0, within(DELTA));
    }
}
