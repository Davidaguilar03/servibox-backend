package com.servibox.backend.config;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Datos de demostracion del perfil dev. DevDataInitializer es @Profile("dev"), asi que este
 * contexto activa test (H2 propia) y dev (servibox.dev.demo-data=true); el runner siembra al
 * arrancar el contexto.
 *
 * Cifras a mano, IVA 19 por ciento (M-1 es el mes anterior, M el actual):
 * compra M-1 contado Caja: 10 aceites a 30000 -> 300000 + 57000 = 357000.
 * venta FV-0001 M-1 contado Caja: 5 aceites a 45000 + 1 bateria a 360000 -> 585000 + 111150 = 696150.
 * venta FV-0002 M-1 credito: 2 filtros a 22000 -> 44000 + 8360 = 52360; abono M de 20000 a Banco.
 * venta FV-0003 M contado Banco: 3 aceites a 45000 -> 135000 + 25650 = 160650.
 * venta FV-0004 M contado Caja: 1 bateria a 360000 -> 428400, ANULADA.
 * venta FV-0005 M contado Caja: 2 aceites a 45000 -> 90000 + 17100 = 107100.
 * gasto M Banco 450000; transferencia M-1 Caja -> Banco 500000; ingreso 2000000 en cada cuenta.
 *
 * KPIs M-1: ventas 748510, compras 357000, gastos 0, utilidad bruta y neta 391510.
 * KPIs M: ventas 267750, compras 0, gastos 450000, utilidad bruta 267750, neta -182250.
 * Caja: 2000000 - 357000 + 696150 - 500000 + 107100 = 1946250.
 * Banco: 2000000 + 500000 + 20000 + 160650 - 450000 = 2230650.
 * Stock: aceite 50 - 5 + 10 - 3 - 2 = 50, filtro 12 - 2 = 10, bateria 4 - 1 = 3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class DevDemoDataTest {

    private static final double DELTA = 0.001;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevDataInitializer initializer;

    @Autowired
    private Clock clock;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private CounterpartyService counterpartyService;

    @Autowired
    private SalesFixture fixture;

    private YearMonth mesActual;
    private YearMonth mesAnterior;

    @BeforeEach
    void usarTenantDemo() {
        mesActual = YearMonth.now(clock);
        mesAnterior = mesActual.minusMonths(1);
        fixture.usarTenant(tenantRepository.findBySlug("demo").orElseThrow().getId());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void siembraCategoriaProductosEnCadaEstadoYSaldos() {
        ProductCategory categoria = inventoryService.findAllCategories().stream()
                .filter(c -> c.getName().equals("REPUESTOS DEMO"))
                .findFirst().orElseThrow();
        assertThat(categoria.getYellowStockMin()).isEqualTo(10);
        assertThat(categoria.getRedStockMin()).isEqualTo(3);
        assertThat(categoria.getTargetMargin()).isCloseTo(0.30, within(DELTA));
        assertThat(categoria.getTaxTypes()).extracting(t -> t.getRate()).containsExactly(0.19);

        List<Product> productos = inventoryService.findAllProducts().stream()
                .sorted(Comparator.comparing(Product::getCode)).toList();
        assertThat(productos).extracting(Product::getQuantity).containsExactly(50, 10, 3);
        assertThat(productos).extracting(p -> p.getCategory().getId()).containsOnly(categoria.getId());
        // Regla del frontend (src/lib/stock.ts): critico si <= rojo, bajo si <= amarillo.
        assertThat(productos).extracting(p -> estado(p.getQuantity(), categoria))
                .containsExactly("NORMAL", "BAJO", "CRITICO");
        // Costos distintos; la compra repite el del aceite.
        assertThat(productos).extracting(Product::getPurchaseCost).containsExactly(30000.0, 15000.0, 250000.0);

        Map<String, Double> saldos = treasuryService.findAllAccounts().stream()
                .collect(Collectors.toMap(Account::getName, Account::getCurrentBalance));
        assertThat(saldos.get("Caja General")).isCloseTo(1946250.0, within(DELTA));
        assertThat(saldos.get("Bancolombia")).isCloseTo(2230650.0, within(DELTA));
        fixture.verificarInvariante();
    }

    @Test
    void siembraTercerosVentasEnLosDosPeriodosYUnaCompra() {
        List<Counterparty> terceros = counterpartyService.findAll();
        assertThat(terceros).extracting(Counterparty::getRole).containsExactlyInAnyOrder(
                CounterpartyRole.CLIENTE, CounterpartyRole.CLIENTE, CounterpartyRole.PROVEEDOR);

        List<Sale> ventas = salesService.findAllSales().stream()
                .sorted(Comparator.comparing(Sale::getInvoiceNumber)).toList();
        assertThat(ventas).extracting(Sale::getInvoiceNumber)
                .containsExactly("FV-0001", "FV-0002", "FV-0003", "FV-0004", "FV-0005");
        assertThat(ventas).extracting(Sale::getStatus).containsExactly(SaleStatus.PAGADA, SaleStatus.PENDIENTE,
                SaleStatus.PAGADA, SaleStatus.ANULADA, SaleStatus.PAGADA);
        assertThat(ventas).extracting(v -> YearMonth.from(v.getInvoiceDate()))
                .containsExactly(mesAnterior, mesAnterior, mesActual, mesActual, mesActual);

        Sale credito = ventas.get(1);
        assertThat(credito.getPaymentType()).isEqualTo(PaymentType.CREDITO);
        assertThat(salesService.saldoPendiente(credito)).isCloseTo(52360.0 - 20000.0, within(DELTA));

        List<Purchase> compras = purchasesService.findAllPurchases();
        assertThat(compras).hasSize(1);
        assertThat(compras.get(0).getStatus()).isEqualTo(PurchaseStatus.PAGADA);
        assertThat(compras.get(0).getTotal()).isCloseTo(357000.0, within(DELTA));
        assertThat(YearMonth.from(compras.get(0).getInvoiceDate())).isEqualTo(mesAnterior);
    }

    @Test
    void kpisDelMesActualYDelAnteriorCuadranConLaSiembra() throws Exception {
        String token = login();
        kpis(token, mesAnterior, 748510.0, 357000.0, 0.0);
        kpis(token, mesActual, 267750.0, 0.0, 450000.0);
    }

    @Test
    void ejecutarElInicializadorDosVecesNoDuplicaNada() throws Exception {
        initializer.run();
        initializer.run();

        fixture.usarTenant(tenantRepository.findBySlug("demo").orElseThrow().getId());
        assertThat(tenantRepository.count()).isEqualTo(1);
        assertThat(inventoryService.findAllProducts()).hasSize(3);
        assertThat(counterpartyService.findAll()).hasSize(3);
        assertThat(salesService.findAllSales()).hasSize(5);
        assertThat(purchasesService.findAllPurchases()).hasSize(1);
        // Activos: 2 ingresos, compra, 3 ventas de contado, 2 patas de transferencia, abono y gasto.
        assertThat(treasuryService.buscarMovimientos(null, null, null, null)).hasSize(10);
        assertThat(treasuryService.balanceGlobal()).isCloseTo(1946250.0 + 2230650.0, within(DELTA));
    }

    private static String estado(int stock, ProductCategory categoria) {
        if (stock <= 0) {
            return "SIN_STOCK";
        }
        if (stock <= categoria.getRedStockMin()) {
            return "CRITICO";
        }
        return stock <= categoria.getYellowStockMin() ? "BAJO" : "NORMAL";
    }

    private void kpis(String token, YearMonth mes, double ventas, double compras, double gastos) throws Exception {
        LocalDate desde = mes.atDay(1);
        LocalDate hasta = mes.atEndOfMonth();
        mockMvc.perform(get("/api/reporting/kpis")
                        .param("desde", desde.toString())
                        .param("hasta", hasta.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales", closeTo(ventas, DELTA)))
                .andExpect(jsonPath("$.totalPurchases", closeTo(compras, DELTA)))
                .andExpect(jsonPath("$.grossProfit", closeTo(ventas - compras, DELTA)))
                .andExpect(jsonPath("$.operatingExpenses", closeTo(gastos, DELTA)))
                .andExpect(jsonPath("$.netProfit", closeTo(ventas - compras - gastos, DELTA)));
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"demo","username":"admin","password":"admin123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
