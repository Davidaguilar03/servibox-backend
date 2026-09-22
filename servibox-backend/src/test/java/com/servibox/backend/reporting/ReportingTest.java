package com.servibox.backend.reporting;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.Supplier;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.reporting.dto.IvaCategoryResponse;
import com.servibox.backend.reporting.dto.IvaReportResponse;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.dto.MovementSummaryResponse;
import com.servibox.backend.reporting.service.ReportingService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.repository.SaleRepository;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.Tenant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reporting: KPIs globales y del periodo, listado de movimientos y Reporte de IVA.
 *
 * Cifras de referencia, con IVA del 19 por ciento (SalesFixture):
 * producto de costo 100 -> taxAmount 19 por unidad.
 * venta de 2 unidades a 200 -> subtotal 400, IVA 76, total 476, descontable 38, neto 38.
 * venta de 1 unidad a 100   -> subtotal 100, IVA 19, total 119, descontable 19, neto 0.
 * compra de 1 unidad a 100  -> subtotal 100, IVA 19, total 119.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportingTest {

    private static final String PASSWORD = "clave-correcta";
    private static final double DELTA = 0.001;
    private static final LocalDate HOY = LocalDate.of(2026, 9, 15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long tenantUno;
    private Long tenantDos;
    private String tokenUno;
    private String tokenDos;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenantConUsuario(slugUno, "admin-uno");
        tenantDos = crearTenantConUsuario(slugDos, "admin-dos");
        tokenUno = login(slugUno, "admin-uno");
        tokenDos = login(slugDos, "admin-dos");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- Semilla ----

    private Long crearTenantConUsuario(String slug, String username) {
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        TenantContext.setTenantId(tenantId);
        try {
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setActive(true);
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
        return tenantId;
    }

    private String login(String slug, String username) throws Exception {
        String body = """
                {"tenantSlug":"%s","username":"%s","password":"%s"}
                """.formatted(slug, username, PASSWORD);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Datos basicos de un tenant: categoria LLANTAS con IVA, un producto de costo 100, cliente, proveedor y cuenta. */
    private class Taller {
        final ProductCategory llantas;
        final Product llanta;
        final Customer cliente;
        final Supplier proveedor;
        final Account caja;

        Taller(Long tenantId) {
            fixture.usarTenant(tenantId);
            llantas = fixture.crearCategoriaConIva();
            llanta = fixture.crearProducto(llantas, "LLA-001", 100.0, 50);
            cliente = fixture.crearCliente("Cliente", "900" + System.nanoTime());
            Supplier s = new Supplier();
            s.setName("Proveedor");
            s.setDocument("800" + System.nanoTime());
            proveedor = purchasesService.saveSupplier(s);
            caja = fixture.crearCuenta("Caja General");
        }

        Sale vender(String numero, LocalDate fecha, Product producto, int cantidad, double precio) {
            return salesService.crearFactura(numero, cliente, fecha, null, PaymentType.CREDITO, null, null,
                    List.of(new SalesService.LineaFactura(producto.getId(), cantidad, precio)));
        }

        Purchase comprar(String numero, LocalDate fecha, Product producto, int cantidad, double precio) {
            return purchasesService.crearFactura(numero, proveedor, fecha, null,
                    com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                    List.of(new PurchasesService.LineaCompra(producto.getId(), cantidad, precio)));
        }

        void gastar(String concepto, LocalDate fecha, double monto) {
            treasuryService.registrarGastoOperativo(caja, concepto, monto, fecha, null);
        }
    }

    private IvaCategoryResponse categoria(IvaReportResponse reporte, String nombre) {
        return reporte.categories().stream()
                .filter(c -> c.category().equals(nombre))
                .findFirst()
                .orElseThrow();
    }

    // ---- Utilidades ----

    @Test
    void grossProfitYNetProfitCombinanVentasComprasYGastos() {
        Taller t = new Taller(tenantUno);
        t.vender("FV-1", HOY, t.llanta, 2, 200.0);   // 476
        t.vender("FV-2", HOY, t.llanta, 1, 100.0);   // 119
        t.comprar("FC-1", HOY, t.llanta, 1, 100.0);  // 119
        t.gastar("Arriendo", HOY, 50.0);
        t.gastar("Servicios", HOY, 30.0);
        // Un ingreso ocasional no es venta: no entra en ningun KPI.
        treasuryService.registrarIngresoOcasional(t.caja, "Chatarra", 1000.0, HOY);

        KpisResponse kpis = reportingService.getGlobalKpis();

        assertThat(kpis.totalSales()).isCloseTo(595.0, within(DELTA));
        assertThat(kpis.totalPurchases()).isCloseTo(119.0, within(DELTA));
        assertThat(kpis.grossProfit()).isCloseTo(476.0, within(DELTA));
        assertThat(kpis.operatingExpenses()).isCloseTo(80.0, within(DELTA));
        assertThat(kpis.netProfit()).isCloseTo(396.0, within(DELTA));
        assertThat(kpis.desde()).isNull();
        assertThat(kpis.hasta()).isNull();

        // Con perdida el neto sale negativo, sin recortes.
        t.gastar("Nomina", HOY, 1000.0);
        assertThat(reportingService.getGlobalKpis().netProfit()).isCloseTo(-604.0, within(DELTA));
    }

    // ---- Anuladas ----

    @Test
    void ventaAnuladaNoCuentaYAlVolverAPendienteVuelveAContar() {
        Taller t = new Taller(tenantUno);
        t.vender("FV-1", HOY, t.llanta, 2, 200.0);                 // 476, IVA 76
        Sale anulada = t.vender("FV-2", HOY, t.llanta, 1, 100.0);  // 119, IVA 19
        salesService.anularFactura(anulada.getId());

        LocalDate desde = HOY.withDayOfMonth(1);
        LocalDate hasta = HOY.withDayOfMonth(30);

        assertThat(reportingService.getGlobalKpis().totalSales()).isCloseTo(476.0, within(DELTA));
        assertThat(reportingService.getPeriodKpis(desde, hasta).totalSales()).isCloseTo(476.0, within(DELTA));
        IvaReportResponse iva = reportingService.buildReporteIva(desde, hasta);
        assertThat(iva.totalIvaGenerado()).isCloseTo(76.0, within(DELTA));
        assertThat(iva.invoicesWithIva()).isEqualTo(1);
        assertThat(reportingService.getMovements(desde, hasta))
                .extracting(MovementSummaryResponse::sourceId)
                .doesNotContain(anulada.getId());

        // Restaurar: ServiBox todavia no tiene la operacion (ver 02-CONVENTIONS.md), asi que
        // se simula el estado que dejaria. El reporte filtra por estado, no por historia.
        Sale restaurada = saleRepository.findByIdAndTenantId(anulada.getId(), tenantUno).orElseThrow();
        restaurada.setStatus(SaleStatus.PENDIENTE);
        saleRepository.save(restaurada);

        assertThat(reportingService.getGlobalKpis().totalSales()).isCloseTo(595.0, within(DELTA));
        assertThat(reportingService.getPeriodKpis(desde, hasta).totalSales()).isCloseTo(595.0, within(DELTA));
        iva = reportingService.buildReporteIva(desde, hasta);
        assertThat(iva.totalIvaGenerado()).isCloseTo(95.0, within(DELTA));
        assertThat(iva.invoicesWithIva()).isEqualTo(2);
        assertThat(reportingService.getMovements(desde, hasta))
                .extracting(MovementSummaryResponse::sourceId)
                .contains(anulada.getId());
    }

    @Test
    void compraAnuladaNoCuentaEnTotalPurchases() {
        Taller t = new Taller(tenantUno);
        Purchase vigente = t.comprar("FC-1", HOY, t.llanta, 1, 100.0);
        Purchase anulada = t.comprar("FC-2", HOY, t.llanta, 2, 100.0);
        purchasesService.anularFactura(anulada.getId());

        assertThat(reportingService.getGlobalKpis().totalPurchases()).isCloseTo(119.0, within(DELTA));
        assertThat(reportingService.getPeriodKpis(HOY, HOY).totalPurchases()).isCloseTo(119.0, within(DELTA));
        assertThat(reportingService.getMovements(HOY, HOY))
                .filteredOn(m -> m.type().equals("COMPRA"))
                .extracting(MovementSummaryResponse::sourceId)
                .containsExactly(vigente.getId());
    }

    // ---- Periodo ----

    @Test
    void periodKpisExcluyeLoQueCaeFueraDelRango() {
        Taller t = new Taller(tenantUno);
        LocalDate desde = LocalDate.of(2026, 9, 1);
        LocalDate hasta = LocalDate.of(2026, 9, 30);

        t.vender("FV-AGO", LocalDate.of(2026, 8, 31), t.llanta, 1, 100.0);  // fuera
        t.vender("FV-SEP", LocalDate.of(2026, 9, 30), t.llanta, 2, 200.0);  // dentro, borde
        t.vender("FV-OCT", LocalDate.of(2026, 10, 1), t.llanta, 1, 100.0);  // fuera
        t.comprar("FC-JUL", LocalDate.of(2026, 7, 10), t.llanta, 3, 100.0); // fuera
        t.comprar("FC-SEP", LocalDate.of(2026, 9, 1), t.llanta, 1, 100.0);  // dentro, borde
        t.gastar("Arriendo agosto", LocalDate.of(2026, 8, 5), 500.0);       // fuera
        t.gastar("Arriendo septiembre", LocalDate.of(2026, 9, 5), 50.0);    // dentro

        KpisResponse periodo = reportingService.getPeriodKpis(desde, hasta);
        assertThat(periodo.desde()).isEqualTo(desde);
        assertThat(periodo.hasta()).isEqualTo(hasta);
        assertThat(periodo.totalSales()).isCloseTo(476.0, within(DELTA));
        assertThat(periodo.totalPurchases()).isCloseTo(119.0, within(DELTA));
        assertThat(periodo.operatingExpenses()).isCloseTo(50.0, within(DELTA));
        assertThat(periodo.netProfit()).isCloseTo(307.0, within(DELTA));

        // El global si lo ve todo.
        KpisResponse global = reportingService.getGlobalKpis();
        assertThat(global.totalSales()).isCloseTo(476.0 + 119.0 + 119.0, within(DELTA));
        assertThat(global.totalPurchases()).isCloseTo(119.0 * 4, within(DELTA));
        assertThat(global.operatingExpenses()).isCloseTo(550.0, within(DELTA));

        assertThat(reportingService.getMovements(desde, hasta))
                .extracting(MovementSummaryResponse::concept)
                .allMatch(c -> c.startsWith("FV-SEP") || c.startsWith("FC-SEP") || c.equals("Arriendo septiembre"))
                .hasSize(3);
    }

    // ---- Movimientos ----

    @Test
    void movimientosCombinanLasCuatroFuentesDelMasRecienteAlMasAntiguo() {
        Taller t = new Taller(tenantUno);
        Sale venta = t.vender("FV-1", LocalDate.of(2026, 9, 10), t.llanta, 1, 100.0);
        Purchase compra = t.comprar("FC-1", LocalDate.of(2026, 9, 12), t.llanta, 1, 100.0);
        t.gastar("Arriendo", LocalDate.of(2026, 9, 5), 50.0);
        treasuryService.registrarIngresoOcasional(t.caja, "Chatarra", 20.0, LocalDate.of(2026, 9, 14));

        List<MovementSummaryResponse> movimientos =
                reportingService.getMovements(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(movimientos).extracting(MovementSummaryResponse::type)
                .containsExactly("INGRESO", "COMPRA", "VENTA", "GASTO");
        assertThat(movimientos.get(1).sourceId()).isEqualTo(compra.getId());
        assertThat(movimientos.get(1).concept()).isEqualTo("FC-1 - Proveedor");
        assertThat(movimientos.get(2).sourceId()).isEqualTo(venta.getId());
        assertThat(movimientos.get(2).amount()).isCloseTo(119.0, within(DELTA));
        assertThat(movimientos.get(3).accountName()).isEqualTo("Caja General");
    }

    // ---- Reporte de IVA ----

    @Test
    void reporteIvaAgrupaPorCategoriaYNetoEsGeneradoMenosDescontable() {
        Taller t = new Taller(tenantUno);
        ProductCategory baterias = new ProductCategory();
        baterias.setName("BATERIAS");
        baterias.setColor("#3498db");
        baterias.setTargetMargin(0.0);
        baterias.setTaxTypes(new ArrayList<>(t.llantas.getTaxTypes()));
        baterias = inventoryService.saveCategory(baterias);
        Product bateria = fixture.crearProducto(baterias, "BAT-001", 200.0, 10);  // taxAmount 38

        // LLANTAS: generado 76, descontable 38, neto 38.
        t.vender("FV-1", HOY, t.llanta, 2, 200.0);
        // BATERIAS: generado 57, descontable 38, neto 19.
        salesService.crearFactura("FV-2", t.cliente, HOY, null, PaymentType.CREDITO, null, null, List.of(
                new SalesService.LineaFactura(bateria.getId(), 1, 300.0),
                // LLANTAS: generado 28.5, descontable 19, neto 9.5.
                new SalesService.LineaFactura(t.llanta.getId(), 1, 150.0)));

        IvaReportResponse iva = reportingService.buildReporteIva(HOY, HOY);

        assertThat(iva.categories()).extracting(IvaCategoryResponse::category)
                .containsExactly("LLANTAS", "BATERIAS");
        IvaCategoryResponse llantas = categoria(iva, "LLANTAS");
        assertThat(llantas.ivaGenerado()).isCloseTo(104.5, within(DELTA));
        assertThat(llantas.ivaDescontable()).isCloseTo(57.0, within(DELTA));
        assertThat(llantas.ivaNeto()).isCloseTo(47.5, within(DELTA));
        IvaCategoryResponse bats = categoria(iva, "BATERIAS");
        assertThat(bats.ivaGenerado()).isCloseTo(57.0, within(DELTA));
        assertThat(bats.ivaDescontable()).isCloseTo(38.0, within(DELTA));
        assertThat(bats.ivaNeto()).isCloseTo(19.0, within(DELTA));
        for (IvaCategoryResponse c : iva.categories()) {
            assertThat(c.ivaNeto()).isCloseTo(c.ivaGenerado() - c.ivaDescontable(), within(DELTA));
        }

        assertThat(iva.invoicesWithIva()).isEqualTo(2);
        assertThat(iva.totalIvaGenerado()).isCloseTo(161.5, within(DELTA));
        assertThat(iva.totalIvaDescontable()).isCloseTo(95.0, within(DELTA));
        assertThat(iva.totalIvaNeto()).isCloseTo(66.5, within(DELTA));

        // Comprar la llanta a otro costo le cambia el taxAmount al producto, pero no mueve
        // el IVA de facturas ya emitidas: generado y descontable estan congelados por linea.
        t.comprar("FC-1", HOY.plusDays(1), t.llanta, 1, 300.0);
        IvaReportResponse despues = reportingService.buildReporteIva(HOY, HOY);
        assertThat(categoria(despues, "LLANTAS").ivaDescontable()).isCloseTo(57.0, within(DELTA));
        assertThat(despues.totalIvaNeto()).isCloseTo(66.5, within(DELTA));
    }

    // ---- Aislamiento por tenant ----

    @Test
    void kpisMovimientosYReporteIvaNoVenDatosDeOtroTenant() throws Exception {
        Taller uno = new Taller(tenantUno);
        uno.vender("FV-1", HOY, uno.llanta, 2, 200.0);  // 476, IVA 76
        uno.comprar("FC-1", HOY, uno.llanta, 1, 100.0); // 119
        uno.gastar("Arriendo", HOY, 50.0);

        // El otro tenant, con los mismos numeros de factura y cifras mucho mayores.
        Taller dos = new Taller(tenantDos);
        dos.vender("FV-1", HOY, dos.llanta, 10, 1000.0);
        dos.comprar("FC-1", HOY, dos.llanta, 5, 100.0);
        dos.gastar("Arriendo", HOY, 9000.0);
        TenantContext.clear();

        mockMvc.perform(get("/api/reporting/kpis").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales", closeTo(476.0, DELTA)))
                .andExpect(jsonPath("$.totalPurchases", closeTo(119.0, DELTA)))
                .andExpect(jsonPath("$.grossProfit", closeTo(357.0, DELTA)))
                .andExpect(jsonPath("$.operatingExpenses", closeTo(50.0, DELTA)))
                .andExpect(jsonPath("$.netProfit", closeTo(307.0, DELTA)));

        mockMvc.perform(get("/api/reporting/kpis").param("desde", HOY.toString()).param("hasta", HOY.toString())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(HOY.toString()))
                .andExpect(jsonPath("$.totalSales", closeTo(476.0, DELTA)))
                .andExpect(jsonPath("$.netProfit", closeTo(307.0, DELTA)));

        mockMvc.perform(get("/api/reporting/kpis").header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales", closeTo(11900.0, DELTA)))
                .andExpect(jsonPath("$.totalPurchases", closeTo(595.0, DELTA)))
                .andExpect(jsonPath("$.operatingExpenses", closeTo(9000.0, DELTA)));

        mockMvc.perform(get("/api/reporting/movements").param("desde", HOY.toString()).param("hasta", HOY.toString())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/reporting/iva").param("desde", HOY.toString()).param("hasta", HOY.toString())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicesWithIva").value(1))
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.totalIvaGenerado", closeTo(76.0, DELTA)))
                .andExpect(jsonPath("$.totalIvaNeto", closeTo(38.0, DELTA)));

        mockMvc.perform(get("/api/reporting/iva").param("desde", HOY.toString()).param("hasta", HOY.toString())
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIvaGenerado", closeTo(1900.0, DELTA)));
    }

    // ---- HTTP ----

    @Test
    void losEndpointsExigenAutenticacion() throws Exception {
        String rango = "?desde=2026-09-01&hasta=2026-09-30";
        for (String ruta : List.of("/api/reporting/kpis", "/api/reporting/kpis" + rango,
                "/api/reporting/movements" + rango, "/api/reporting/iva" + rango)) {
            mockMvc.perform(get(ruta)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(ruta).header("Authorization", "Bearer token-invalido"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unRangoIncompletoOInvertidoResponde400() throws Exception {
        mockMvc.perform(get("/api/reporting/iva").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reporting/kpis").param("desde", "2026-09-01")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reporting/movements").param("desde", "2026-09-30").param("hasta", "2026-09-01")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
