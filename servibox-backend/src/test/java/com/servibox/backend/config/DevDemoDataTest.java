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
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Datos de demostracion del perfil dev. DevDataInitializer es @Profile("dev"), asi que este
 * contexto activa test (H2 propia) y dev (servibox.dev.demo-data=true); el runner siembra al
 * arrancar el contexto.
 *
 * El Clock de negocio queda fijo en el 2026-10-24 (agosto, septiembre y octubre) para que las
 * cifras de kpisDeLosTresMesesConLasCifrasDocumentadas no dependan del dia en que se corre. Las
 * demas pruebas comparan contra los propios datos, no contra cifras: valen con cualquier fecha.
 * DevDemoDataFirstDayTest siembra con el Clock en un primer dia de mes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class DevDemoDataTest {

    private static final double DELTA = 0.001;

    @TestConfiguration
    static class RelojFijo {

        @Bean
        @Primary
        Clock relojFijo(@Value("${servibox.business.time-zone}") ZoneId zona) {
            return Clock.fixed(Instant.parse("2026-10-24T17:00:00Z"), zona);
        }
    }

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

    private LocalDate hoy;
    private LocalDate inicio;

    @BeforeEach
    void usarTenantDemo() {
        hoy = LocalDate.now(clock);
        inicio = YearMonth.now(clock).minusMonths(2).atDay(1);
        fixture.usarTenant(tenantRepository.findBySlug("demo").orElseThrow().getId());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void siembraTresCategoriasConSusUmbralesYProductosEnCadaEstado() {
        Map<String, ProductCategory> categorias = inventoryService.findAllCategories().stream()
                .collect(Collectors.toMap(ProductCategory::getName, Function.identity()));
        assertThat(categorias).containsOnlyKeys("LLANTAS", "LUBRICANTES", "BATERIAS");
        categorias.values().forEach(c -> {
            assertThat(c.getTargetMargin()).isBetween(0.18, 0.35);
            assertThat(c.getRedStockMin()).isPositive().isLessThan(c.getYellowStockMin());
            assertThat(c.getTaxTypes()).extracting(t -> t.getRate()).containsExactly(0.19);
        });

        List<Product> productos = inventoryService.findAllProducts();
        assertThat(productos).hasSizeBetween(24, 30);
        assertThat(productos).extracting(Product::getCode).doesNotHaveDuplicates()
                .allMatch(codigo -> codigo.matches("(LL|LU|BA)-\\d{3}"));
        assertThat(productos).extracting(Product::getQuantity).allMatch(q -> q >= 0);
        // Con FinancialSettings en cero, precio sugerido = costo / (1 - margen), en pesos enteros.
        productos.forEach(p -> {
            assertThat(p.getSuggestedPrice()).as(p.getCode())
                    .isCloseTo(p.getPurchaseCost() / (1 - p.getCategory().getTargetMargin()), within(0.5));
            assertThat(p.getSuggestedPrice() % 1).as(p.getCode()).isZero();
            assertThat(p.getTaxAmount() % 1).as(p.getCode()).isZero();
        });

        // Regla del frontend (src/lib/stock.ts): critico si <= rojo, bajo si <= amarillo.
        Map<String, Long> porEstado = productos.stream()
                .collect(Collectors.groupingBy(DevDemoDataTest::estado, Collectors.counting()));
        assertThat(porEstado.get("SIN_STOCK")).isGreaterThanOrEqualTo(1);
        assertThat(porEstado.get("CRITICO")).isGreaterThanOrEqualTo(1);
        assertThat(porEstado.get("BAJO")).isBetween(2L, 3L);
        assertThat(porEstado.get("NORMAL")).isGreaterThan(productos.size() / 2L);
    }

    @Test
    void cadaCuentaCuadraConSuSaldoInicialYNuncaQuedaEnNegativo() {
        List<Account> cuentas = treasuryService.findAllAccounts();
        assertThat(cuentas).extracting(Account::getType)
                .containsExactlyInAnyOrder(AccountType.EFECTIVO, AccountType.BANCO, AccountType.DIGITAL);
        assertThat(cuentas).extracting(Account::getInitialBalance).allMatch(saldo -> saldo > 0);
        // Saldo inicial fijado en la cuenta: ningun ingreso ocasional hace de aporte.
        assertThat(treasuryService.buscarMovimientos(null, MovementCategory.INGRESO_OCASIONAL, null, null))
                .hasSize(1);

        for (Account cuenta : cuentas) {
            double neto = treasuryService.findMovementsByAccountId(cuenta.getId()).stream()
                    .mapToDouble(m -> m.getType() == MovementType.INGRESO ? m.getAmount() : -m.getAmount())
                    .sum();
            assertThat(cuenta.getCurrentBalance()).as(cuenta.getName())
                    .isCloseTo(cuenta.getInitialBalance() + neto, within(DELTA));
            for (LocalDate dia = inicio; !dia.isAfter(hoy); dia = dia.plusDays(1)) {
                assertThat(treasuryService.saldoAl(cuenta, dia)).as(cuenta.getName() + " al " + dia)
                        .isGreaterThanOrEqualTo(0.0);
            }
        }
        fixture.verificarInvariante();
    }

    @Test
    void siembraTresMesesDeVentasComprasYGastosSinFechasFuturas() {
        List<Counterparty> terceros = counterpartyService.findAll();
        assertThat(terceros).filteredOn(t -> t.getRole() == CounterpartyRole.CLIENTE).hasSize(9)
                .extracting(Counterparty::getName).contains(CounterpartyService.NOMBRE_CONSUMIDOR_FINAL);
        assertThat(terceros).filteredOn(t -> t.getRole() == CounterpartyRole.PROVEEDOR).hasSize(5);
        assertThat(terceros).extracting(Counterparty::getDocumentNumber).doesNotHaveDuplicates();

        List<Sale> ventas = salesService.findAllSales();
        assertThat(ventas).hasSizeBetween(35, 45);
        assertThat(ventas).filteredOn(v -> v.getStatus() == SaleStatus.ANULADA).hasSizeBetween(1, 2);
        assertThat(ventas).extracting(Sale::getInvoiceDate).allMatch(f -> !f.isBefore(inicio) && !f.isAfter(hoy));
        assertThat(ventas).extracting(v -> YearMonth.from(v.getInvoiceDate())).containsOnly(
                YearMonth.now(clock), YearMonth.now(clock).minusMonths(1), YearMonth.now(clock).minusMonths(2));
        assertThat(ventas).extracting(v -> v.getCustomer().getName())
                .contains(CounterpartyService.NOMBRE_CONSUMIDOR_FINAL);

        // Contado: los 4 medios de pago, efectivo solo en la cuenta EFECTIVO.
        List<Sale> contado = ventas.stream().filter(v -> v.getPaymentType() == PaymentType.CONTADO
                && v.getStatus() != SaleStatus.ANULADA).toList();
        assertThat(contado).extracting(Sale::getPaymentMethod).containsOnly(
                "EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO");
        contado.forEach(v -> {
            AccountType tipo = treasuryService.movimientosDeVenta(v).get(0).getAccount().getType();
            assertThat(tipo == AccountType.EFECTIVO).as(v.getInvoiceNumber())
                    .isEqualTo("EFECTIVO".equals(v.getPaymentMethod()));
        });

        // Credito: pagadas con abonos, pendientes con abono parcial y pendientes sin abono.
        List<Sale> credito = ventas.stream().filter(v -> v.getPaymentType() == PaymentType.CREDITO).toList();
        assertThat(credito).filteredOn(v -> v.getStatus() == SaleStatus.PAGADA).isNotEmpty();
        assertThat(credito).filteredOn(v -> v.getStatus() == SaleStatus.PENDIENTE)
                .anyMatch(v -> salesService.saldoPendiente(v) < v.getTotal() - DELTA)
                .anyMatch(v -> Math.abs(salesService.saldoPendiente(v) - v.getTotal()) < DELTA);

        List<Purchase> compras = purchasesService.findAllPurchases();
        assertThat(compras).hasSizeBetween(8, 10);
        assertThat(compras).filteredOn(c -> c.getStatus() == PurchaseStatus.ANULADA).hasSize(1);
        assertThat(compras).extracting(Purchase::getPaymentType).contains(
                com.servibox.backend.purchases.entity.PaymentType.CONTADO,
                com.servibox.backend.purchases.entity.PaymentType.CREDITO);
        assertThat(compras).filteredOn(c -> c.getStatus() == PurchaseStatus.PENDIENTE)
                .anyMatch(c -> purchasesService.saldoPendiente(c) < c.getTotal() - DELTA);
        assertThat(compras).extracting(Purchase::getInvoiceDate).allMatch(f -> !f.isBefore(inicio) && !f.isAfter(hoy));

        List<Movement> movimientos = treasuryService.buscarMovimientos(null, null, null, null);
        assertThat(movimientos).extracting(Movement::getDate).allMatch(f -> !f.isBefore(inicio) && !f.isAfter(hoy));
        assertThat(movimientos).filteredOn(m -> m.getCategory() == MovementCategory.GASTO_OPERATIVO)
                .extracting(Movement::getConcept)
                .contains("Arriendo del local", "Energia electrica", "Nomina primera quincena",
                        "Internet y telefonia", "Insumos de aseo y cafeteria");
        // Dos transferencias, cada una con sus dos patas.
        assertThat(movimientos).filteredOn(m -> m.getCategory() == MovementCategory.TRANSFERENCIA).hasSize(4);
    }

    @Test
    void kpisDeCadaMesCoincidenConLasFacturasActivasYNoTodoEstaEnRojo() throws Exception {
        String token = login();
        double[] netas = new double[3];
        for (int atras = 0; atras < 3; atras++) {
            YearMonth mes = YearMonth.now(clock).minusMonths(atras);
            LocalDate desde = mes.atDay(1);
            LocalDate hasta = mes.atEndOfMonth();
            List<Sale> delMes = salesService.findAllSales().stream()
                    .filter(v -> enRango(v.getInvoiceDate(), desde, hasta)).toList();
            // Las lineas de venta de la siembra van en pesos enteros, anuladas incluidas.
            delMes.forEach(v -> assertThat(salesService.detallesDe(v))
                    .allMatch(d -> d.getPrice() % 1 == 0, v.getInvoiceNumber()));
            List<Sale> activas = salesService.findAllSales().stream()
                    .filter(v -> v.getStatus() != SaleStatus.ANULADA && enRango(v.getInvoiceDate(), desde, hasta))
                    .toList();
            double ventas = activas.stream().mapToDouble(Sale::getTotal).sum();
            double ventasSinIva = activas.stream().mapToDouble(Sale::getSubtotal).sum();
            double costo = activas.stream().flatMap(v -> salesService.detallesDe(v).stream())
                    .mapToDouble(d -> d.getUnitCost() * d.getQuantity()).sum();
            double compras = purchasesService.findAllPurchases().stream()
                    .filter(c -> c.getStatus() != PurchaseStatus.ANULADA && enRango(c.getInvoiceDate(), desde, hasta))
                    .mapToDouble(Purchase::getTotal).sum();
            double gastos = suma(MovementCategory.GASTO_OPERATIVO, desde, hasta);
            double otros = suma(MovementCategory.INGRESO_OCASIONAL, desde, hasta);

            String kpis = kpis(token, desde, hasta);
            assertThat(numero(kpis, "$.totalSales")).as("ventas " + mes).isCloseTo(ventas, within(DELTA));
            assertThat(numero(kpis, "$.totalPurchases")).as("compras " + mes).isCloseTo(compras, within(DELTA));
            assertThat(numero(kpis, "$.operatingExpenses")).as("gastos " + mes).isCloseTo(gastos, within(DELTA));
            assertThat(numero(kpis, "$.otherIncome")).as("otros " + mes).isCloseTo(otros, within(DELTA));
            assertThat(numero(kpis, "$.netSales")).as("ventas sin IVA " + mes).isCloseTo(ventasSinIva, within(DELTA));
            assertThat(numero(kpis, "$.costOfSales")).as("costo " + mes).isCloseTo(costo, within(DELTA));
            double bruta = numero(kpis, "$.grossProfit");
            assertThat(bruta).as("bruta " + mes).isCloseTo(ventasSinIva - costo, within(DELTA)).isPositive();
            // Margen bruto plausible sobre ventas sin IVA.
            assertThat(bruta / ventasSinIva).as("margen " + mes).isBetween(0.28, 0.38);
            assertThat(ventas).isPositive();
            netas[atras] = numero(kpis, "$.netProfit");
            assertThat(netas[atras]).isCloseTo(bruta + otros - gastos, within(DELTA));
        }
        assertThat(netas[0]).as("neta del mes actual").isPositive();
        assertThat(netas[1]).as("neta del mes anterior").isPositive();
    }

    @Test
    void kpisDeLosTresMesesConLasCifrasDocumentadas() throws Exception {
        String token = login();
        // Agosto, septiembre y octubre de 2026: cifras de 02-CONVENTIONS.md.
        kpisEsperados(token, YearMonth.of(2026, 8), 23990595.16, 14518000.0, 20160164.0, 13572000.0, 7294000.0, 0.0);
        kpisEsperados(token, YearMonth.of(2026, 9), 27488878.62, 10595760.0, 23099898.0, 15636000.0, 7221800.0,
                310000.0);
        kpisEsperados(token, YearMonth.of(2026, 10), 24730469.33, 7097160.0, 20781907.0, 14055000.0, 5192200.0, 0.0);
        // Octubre: neta 20781907 - 14055000 - 5192200 = 1534707, el 7,4 por ciento de las ventas sin IVA.
        double neta = numero(kpis(token, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), "$.netProfit");
        assertThat(neta / 20781907.0).as("margen neto de octubre").isBetween(0.05, 0.20);
    }

    @Test
    void elTenantDemoTieneNitYTelefono() throws Exception {
        String perfil = mockMvc.perform(get("/api/tenant").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(perfil, "$.nit")).isEqualTo("900000000-0");
        assertThat(JsonPath.<String>read(perfil, "$.phone")).isEqualTo("3000000000");
    }

    @Test
    void ejecutarElInicializadorDosVecesNoDuplicaNada() throws Exception {
        int productos = inventoryService.findAllProducts().size();
        int terceros = counterpartyService.findAll().size();
        int ventas = salesService.findAllSales().size();
        int compras = purchasesService.findAllPurchases().size();
        int movimientos = treasuryService.buscarMovimientos(null, null, null, null).size();
        double balance = treasuryService.balanceGlobal();

        initializer.run();
        initializer.run();

        fixture.usarTenant(tenantRepository.findBySlug("demo").orElseThrow().getId());
        assertThat(tenantRepository.count()).isEqualTo(1);
        assertThat(inventoryService.findAllCategories()).hasSize(3);
        assertThat(treasuryService.findAllAccounts()).hasSize(3);
        assertThat(inventoryService.findAllProducts()).hasSize(productos);
        assertThat(counterpartyService.findAll()).hasSize(terceros);
        assertThat(salesService.findAllSales()).hasSize(ventas);
        assertThat(purchasesService.findAllPurchases()).hasSize(compras);
        assertThat(treasuryService.buscarMovimientos(null, null, null, null)).hasSize(movimientos);
        assertThat(treasuryService.balanceGlobal()).isCloseTo(balance, within(DELTA));
    }

    static String estado(Product producto) {
        ProductCategory categoria = producto.getCategory();
        int stock = producto.getQuantity();
        if (stock <= 0) {
            return "SIN_STOCK";
        }
        if (stock <= categoria.getRedStockMin()) {
            return "CRITICO";
        }
        return stock <= categoria.getYellowStockMin() ? "BAJO" : "NORMAL";
    }

    private static boolean enRango(LocalDate fecha, LocalDate desde, LocalDate hasta) {
        return !fecha.isBefore(desde) && !fecha.isAfter(hasta);
    }

    private double suma(MovementCategory categoria, LocalDate desde, LocalDate hasta) {
        return treasuryService.buscarMovimientos(null, categoria, desde, hasta).stream()
                .mapToDouble(Movement::getAmount).sum();
    }

    private void kpisEsperados(String token, YearMonth mes, double ventas, double compras, double ventasSinIva,
                               double costo, double gastos, double otros) throws Exception {
        String kpis = kpis(token, mes.atDay(1), mes.atEndOfMonth());
        assertThat(numero(kpis, "$.totalSales")).as("ventas " + mes).isCloseTo(ventas, within(DELTA));
        assertThat(numero(kpis, "$.totalPurchases")).as("compras " + mes).isCloseTo(compras, within(DELTA));
        assertThat(numero(kpis, "$.operatingExpenses")).as("gastos " + mes).isCloseTo(gastos, within(DELTA));
        assertThat(numero(kpis, "$.otherIncome")).as("otros " + mes).isCloseTo(otros, within(DELTA));
        assertThat(numero(kpis, "$.netSales")).as("ventas sin IVA " + mes).isCloseTo(ventasSinIva, within(DELTA));
        assertThat(numero(kpis, "$.costOfSales")).as("costo " + mes).isCloseTo(costo, within(DELTA));
        assertThat(numero(kpis, "$.grossProfit")).as("bruta " + mes).isCloseTo(ventasSinIva - costo, within(DELTA));
        assertThat(numero(kpis, "$.netProfit")).as("neta " + mes)
                .isCloseTo(ventasSinIva - costo + otros - gastos, within(DELTA));
    }

    private String kpis(String token, LocalDate desde, LocalDate hasta) throws Exception {
        return mockMvc.perform(get("/api/reporting/kpis")
                        .param("desde", desde.toString())
                        .param("hasta", hasta.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static double numero(String json, String ruta) {
        return JsonPath.<Number>read(json, ruta).doubleValue();
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
