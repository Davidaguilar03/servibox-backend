package com.servibox.backend.inventory;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.service.InvalidPurchaseOperationException;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.reporting.dto.IvaReportResponse;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.service.ReportingService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Servicios (tarea 59): items de PRODUCTOS con item_type SERVICIO, sin stock ni costo, con
 * precio fijo y la categoria SERVICIOS automatica por tenant. Dos tenants reales por HTTP para
 * el catalogo; ventas, compras y reportes por sus services.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceItemsTest {

    private static final String PASSWORD = "clave-correcta";
    private static final double DELTA = 0.001;
    private static final LocalDate FECHA = LocalDate.of(2026, 1, 15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private ReportingService reportingService;

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

    /** Del tenant uno: LLANTAS (IVA 19, margen 0) con una llanta de costo 100 y stock 7. */
    private ProductCategory llantas;
    private Product llanta;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenantConUsuario(slugUno, "admin-uno");
        tenantDos = crearTenantConUsuario(slugDos, "admin-dos");
        tokenUno = login(slugUno, "admin-uno");
        tokenDos = login(slugDos, "admin-dos");

        fixture.usarTenant(tenantUno);
        llantas = fixture.crearCategoriaConIva();
        llanta = fixture.crearProducto(llantas, "LLA-001", 100.0, 7);
        TenantContext.clear();
        fixture.usarTenant(tenantDos);
        fixture.crearCategoriaConIva();
        TenantContext.clear();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- Catalogo ----

    @Test
    void crearServicioDejaStockYCostoEnCeroYUsaLaCategoriaServicios() throws Exception {
        crearServicio(tokenUno, """
                {"code":"SRV-ALI","description":"Alineacion","itemType":"SERVICIO","salePrice":35000,
                 "purchaseCost":9999,"quantity":50,"categoryId":%d}
                """.formatted(llantas.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemType").value("SERVICIO"))
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.purchaseCost", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.suggestedPrice", closeTo(35000.0, DELTA)))
                .andExpect(jsonPath("$.taxAmount", closeTo(6650.0, DELTA)))
                .andExpect(jsonPath("$.categoryName").value("SERVICIOS"));

        // Un producto sigue saliendo como PRODUCTO.
        mockMvc.perform(get("/api/inventory/products/" + llanta.getId())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.itemType").value("PRODUCTO"));
    }

    @Test
    void laCategoriaServiciosSeCreaUnaSolaVezPorTenantYNoLaVeOtroTenant() throws Exception {
        Long idUno = idDe(crearServicio(tokenUno, servicio("SRV-1", 20000)).andExpect(status().isCreated()));
        Long idDos = idDe(crearServicio(tokenUno, servicio("SRV-2", 15000)).andExpect(status().isCreated()));

        fixture.usarTenant(tenantUno);
        List<ProductCategory> serviciosUno = inventoryService.findAllCategories().stream()
                .filter(c -> c.getName().equals(InventoryService.CATEGORIA_SERVICIOS)).toList();
        assertThat(serviciosUno).hasSize(1);
        assertThat(inventoryService.findProductById(idUno).orElseThrow().getCategory().getId())
                .isEqualTo(serviciosUno.get(0).getId());
        assertThat(inventoryService.findProductById(idDos).orElseThrow().getCategory().getId())
                .isEqualTo(serviciosUno.get(0).getId());
        TenantContext.clear();

        // El otro tenant no la ve; su primer servicio crea la suya.
        mockMvc.perform(get("/api/inventory/categories").header("Authorization", "Bearer " + tokenDos))
                .andExpect(jsonPath("$[?(@.name == 'SERVICIOS')]", hasSize(0)));
        crearServicio(tokenDos, servicio("SRV-1", 20000)).andExpect(status().isCreated());
        fixture.usarTenant(tenantDos);
        List<ProductCategory> serviciosDos = inventoryService.findAllCategories().stream()
                .filter(c -> c.getName().equals(InventoryService.CATEGORIA_SERVICIOS)).toList();
        assertThat(serviciosDos).hasSize(1);
        assertThat(serviciosDos.get(0).getId()).isNotEqualTo(serviciosUno.get(0).getId());
    }

    @Test
    void elPrecioDelServicioEsObligatorioYMayorQueCero() throws Exception {
        crearServicio(tokenUno, """
                {"code":"SRV-X","description":"Balanceo","itemType":"SERVICIO"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El precio de venta del servicio debe ser mayor a cero"));
        crearServicio(tokenUno, servicio("SRV-X", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El precio de venta del servicio debe ser mayor a cero"));
    }

    @Test
    void unServicioSinIvaTieneTaxAmountCeroYUnaVentaSinIva() throws Exception {
        Long id = idDe(crearServicio(tokenUno, """
                {"code":"SRV-LAV","description":"Lavado","itemType":"SERVICIO","salePrice":10000,"vatApplies":false}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.taxAmount", closeTo(0.0, DELTA))));

        fixture.usarTenant(tenantUno);
        Sale venta = vender(PaymentType.CONTADO, new SalesService.LineaFactura(id, 1, 10000.0));
        assertThat(venta.getTotal()).isCloseTo(10000.0, within(DELTA));
    }

    @Test
    void elCodigoDeUnServicioNoPuedeRepetirElDeUnProducto() throws Exception {
        crearServicio(tokenUno, servicio("LLA-001", 20000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe un producto con el codigo LLA-001"));
    }

    @Test
    void listarFiltraPorTipo() throws Exception {
        crearServicio(tokenUno, servicio("SRV-1", 20000)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/inventory/products?itemType=SERVICIO").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value("SRV-1"));
        mockMvc.perform(get("/api/inventory/products?itemType=PRODUCTO").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value("LLA-001"));
        mockMvc.perform(get("/api/inventory/products").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$", hasSize(2)));
        // El otro tenant no ve el servicio.
        mockMvc.perform(get("/api/inventory/products?itemType=SERVICIO").header("Authorization", "Bearer " + tokenDos))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/inventory/products?itemType=OTRO").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editarNoPuedeCambiarElTipo() throws Exception {
        Long servicio = idDe(crearServicio(tokenUno, servicio("SRV-1", 20000)));

        editar(servicio, """
                {"code":"SRV-1","description":"Servicio","itemType":"PRODUCTO","purchaseCost":100,"categoryId":%d}
                """.formatted(llantas.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(InventoryService.MENSAJE_TIPO_INMUTABLE));
        editar(llanta.getId(), """
                {"code":"LLA-001","description":"Llanta","itemType":"SERVICIO","purchaseCost":100,"categoryId":%d,"salePrice":5000}
                """.formatted(llantas.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(InventoryService.MENSAJE_TIPO_INMUTABLE));

        // Con el mismo tipo (o sin tipo) el servicio se edita y sigue sin stock ni costo.
        editar(servicio, """
                {"code":"SRV-1","description":"Alineacion 3D","itemType":"SERVICIO","salePrice":40000,"purchaseCost":500}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Alineacion 3D"))
                .andExpect(jsonPath("$.suggestedPrice", closeTo(40000.0, DELTA)))
                .andExpect(jsonPath("$.taxAmount", closeTo(7600.0, DELTA)))
                .andExpect(jsonPath("$.purchaseCost", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.itemType").value("SERVICIO"));
    }

    @Test
    void losRecalculosDePrecioNoTocanElPrecioFijoDelServicio() throws Exception {
        Long id = idDe(crearServicio(tokenUno, servicio("SRV-1", 35000)));
        mockMvc.perform(put("/api/inventory/financial-settings")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expensesRate":0.1,"dianRate":0.05,"icaRate":0.01,"cardCommissionRate":0}
                                """))
                .andExpect(status().isOk());

        fixture.usarTenant(tenantUno);
        Product servicio = inventoryService.findProductById(id).orElseThrow();
        assertThat(servicio.getSuggestedPrice()).isCloseTo(35000.0, within(DELTA));
        assertThat(servicio.getTaxAmount()).isCloseTo(6650.0, within(DELTA));
    }

    /**
     * El backend no calcula alertas de stock ni valorizacion: las arma el frontend con el
     * listado. Los servicios salen del listado de PRODUCTO y no suman valor a costo.
     */
    @Test
    void elServicioQuedaFueraDelListadoDeProductosYNoSumaValorDeInventario() throws Exception {
        crearServicio(tokenUno, servicio("SRV-1", 35000)).andExpect(status().isCreated());

        fixture.usarTenant(tenantUno);
        List<Product> productos = inventoryService.findProducts(ItemType.PRODUCTO);
        assertThat(productos).extracting(Product::getCode).containsExactly("LLA-001");
        double valorTodos = inventoryService.findAllProducts().stream()
                .mapToDouble(p -> p.getPurchaseCost() * p.getQuantity()).sum();
        assertThat(valorTodos).isCloseTo(700.0, within(DELTA));
    }

    // ---- Ventas y compras ----

    @Test
    void ventaDeContadoYACreditoConUnServicioNoTocaStock() throws Exception {
        Long id = idDe(crearServicio(tokenUno, servicio("SRV-1", 35000)));
        fixture.usarTenant(tenantUno);

        Sale contado = vender(PaymentType.CONTADO, new SalesService.LineaFactura(id, 2, 35000.0));
        assertThat(contado.getSubtotal()).isCloseTo(70000.0, within(DELTA));
        assertThat(contado.getTotal()).isCloseTo(83300.0, within(DELTA));
        // Sin descontable: el IVA por pagar es todo el generado.
        assertThat(contado.getIvaPorPagar()).isCloseTo(13300.0, within(DELTA));

        Sale credito = vender(PaymentType.CREDITO, new SalesService.LineaFactura(id, 1, 35000.0));
        assertThat(credito.getTotal()).isCloseTo(41650.0, within(DELTA));

        assertThat(inventoryService.findProductById(id).orElseThrow().getQuantity()).isZero();
        SaleDetail linea = salesService.detallesDe(contado).get(0);
        assertThat(linea.getUnitCost()).isCloseTo(0.0, within(DELTA));
    }

    @Test
    void ventaMixtaDescuentaSoloElProductoYAnularNoDevuelveStockAlServicio() throws Exception {
        Long id = idDe(crearServicio(tokenUno, servicio("SRV-1", 35000)));
        fixture.usarTenant(tenantUno);

        Sale venta = vender(PaymentType.CONTADO,
                new SalesService.LineaFactura(llanta.getId(), 3, 150.0),
                new SalesService.LineaFactura(id, 1, 35000.0));
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getQuantity()).isEqualTo(4);
        assertThat(inventoryService.findProductById(id).orElseThrow().getQuantity()).isZero();

        salesService.anularFactura(venta.getId());
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getQuantity()).isEqualTo(7);
        assertThat(inventoryService.findProductById(id).orElseThrow().getQuantity()).isZero();

        salesService.restaurarFactura(venta.getId());
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getQuantity()).isEqualTo(4);
        assertThat(inventoryService.findProductById(id).orElseThrow().getQuantity()).isZero();
        fixture.verificarInvariante();
    }

    @Test
    void unServicioNoSePuedeComprar() throws Exception {
        Long id = idDe(crearServicio(tokenUno, servicio("SRV-1", 35000)));
        fixture.usarTenant(tenantUno);
        Counterparty proveedor = fixture.crearProveedor("Proveedor", "800" + System.nanoTime());

        assertThatThrownBy(() -> purchasesService.crearFactura("PROV-1", proveedor, FECHA, FECHA.plusDays(30),
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100.0),
                        new PurchasesService.LineaCompra(id, 1, 100.0))))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessage("No se puede comprar un servicio: Servicio SRV-1");
        assertThat(purchasesService.findAllPurchases()).isEmpty();
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getQuantity()).isEqualTo(7);
    }

    // ---- Reportes ----

    @Test
    void elIngresoDelServicioEntraEnElEstadoDeResultadosYEnElIva() throws Exception {
        Long id = idDe(crearServicio(tokenUno, servicio("SRV-1", 35000)));
        fixture.usarTenant(tenantUno);
        vender(PaymentType.CONTADO,
                new SalesService.LineaFactura(llanta.getId(), 1, 150.0),
                new SalesService.LineaFactura(id, 1, 35000.0));

        KpisResponse kpis = reportingService.getPeriodKpis(FECHA.withDayOfMonth(1), FECHA.withDayOfMonth(31));
        assertThat(kpis.netSales()).isCloseTo(35150.0, within(DELTA));
        assertThat(kpis.costOfSales()).isCloseTo(100.0, within(DELTA));
        assertThat(kpis.grossProfit()).isCloseTo(35050.0, within(DELTA));

        IvaReportResponse iva = reportingService.buildReporteIva(FECHA.withDayOfMonth(1), FECHA.withDayOfMonth(31));
        assertThat(iva.categories()).anySatisfy(c -> {
            assertThat(c.category()).isEqualTo("SERVICIOS");
            assertThat(c.ivaGenerado()).isCloseTo(6650.0, within(DELTA));
            assertThat(c.ivaDescontable()).isCloseTo(0.0, within(DELTA));
        });
        assertThat(reportingService.getMovements(FECHA.withDayOfMonth(1), FECHA.withDayOfMonth(31))).hasSize(1);
    }

    // ---- Apoyo ----

    // ---- vatApplies ----

    @Test
    void vatAppliesDiceSiElItemLlevaIva() throws Exception {
        fixture.usarTenant(tenantUno);
        ProductCategory sinIva = new ProductCategory();
        sinIva.setName("BATERIAS");
        sinIva.setColor("#2ecc71");
        sinIva.setYellowStockMin(1);
        sinIva.setRedStockMin(0);
        sinIva.setTargetMargin(0.0);
        sinIva.setTaxTypes(java.util.Set.of());
        fixture.crearProducto(inventoryService.saveCategory(sinIva), "BAT-001", 100.0, 3);
        // Costo 0: sin taxAmount, pero su categoria lleva IVA.
        fixture.crearProducto(llantas, "LLA-000", 0.0, 1);
        TenantContext.clear();
        crearServicio(tokenUno, servicio("SRV-IVA", 20000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.vatApplies").value(true));
        crearServicio(tokenUno, """
                {"code":"SRV-SIN","description":"Lavado","itemType":"SERVICIO","salePrice":10000,"vatApplies":false}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.vatApplies").value(false));

        mockMvc.perform(get("/api/inventory/products").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'LLA-001')].vatApplies").value(true))
                .andExpect(jsonPath("$[?(@.code == 'LLA-000')].vatApplies").value(true))
                .andExpect(jsonPath("$[?(@.code == 'BAT-001')].vatApplies").value(false))
                .andExpect(jsonPath("$[?(@.code == 'SRV-IVA')].vatApplies").value(true))
                .andExpect(jsonPath("$[?(@.code == 'SRV-SIN')].vatApplies").value(false));
    }

    private Sale vender(PaymentType tipo, SalesService.LineaFactura... lineas) {
        Counterparty cliente = fixture.crearCliente("Cliente", "900" + System.nanoTime());
        Account caja = tipo == PaymentType.CONTADO ? fixture.crearCuenta("Caja " + System.nanoTime()) : null;
        return salesService.crearFactura(null, cliente, FECHA, tipo == PaymentType.CREDITO ? FECHA.plusDays(30) : null,
                tipo, caja, tipo == PaymentType.CONTADO ? "Efectivo" : null, List.of(lineas));
    }

    private static String servicio(String codigo, double precio) {
        return """
                {"code":"%s","description":"Servicio %s","itemType":"SERVICIO","salePrice":%s}
                """.formatted(codigo, codigo, precio);
    }

    private ResultActions crearServicio(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/inventory/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions editar(Long id, String body) throws Exception {
        return mockMvc.perform(put("/api/inventory/products/" + id)
                .header("Authorization", "Bearer " + tokenUno)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static Long idDe(ResultActions resultado) throws Exception {
        Integer id = JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

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
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"%s","password":"%s"}
                                """.formatted(slug, username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
