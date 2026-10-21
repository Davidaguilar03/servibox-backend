package com.servibox.backend.inventory;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.service.PurchasesService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/inventory/products/{id} y PUT /api/inventory/categories/{id}, por HTTP con dos
 * tenants reales.
 *
 * Cifras, sin configuracion financiera (todo en cero): suggestedPrice = costo / (1 - margen)
 * y taxAmount = costo * IVA. Costo 100 con IVA 19 y margen 0 -> taxAmount 19, precio 100.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryEditingTest {

    private static final String PASSWORD = "clave-correcta";
    private static final double DELTA = 0.001;

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
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long tenantUno;
    private Long tenantDos;
    private String tokenUno;
    private String tokenDos;

    /** Del tenant uno: LLANTAS (IVA 19, margen 0) con un producto de costo 100 y stock 7. */
    private ProductCategory llantas;
    private TaxType iva;
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
        iva = llantas.getTaxTypes().iterator().next();
        llanta = fixture.crearProducto(llantas, "LLA-001", 100.0, 7);
        TenantContext.clear();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- Producto ----

    @Test
    void editarDescripcionCostoYCategoriaRecalculaLosDerivados() throws Exception {
        Long aceites = crearCategoria(tokenUno, "ACEITES", 0.5, List.of());

        editarProducto(tokenUno, llanta.getId(), "LLA-001", "Llanta editada", 200.0, aceites)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(llanta.getId()))
                .andExpect(jsonPath("$.code").value("LLA-001"))
                .andExpect(jsonPath("$.description").value("Llanta editada"))
                .andExpect(jsonPath("$.purchaseCost", closeTo(200.0, DELTA)))
                .andExpect(jsonPath("$.quantity").value(7))
                .andExpect(jsonPath("$.taxAmount", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.suggestedPrice", closeTo(400.0, DELTA)))
                .andExpect(jsonPath("$.categoryId").value(aceites))
                .andExpect(jsonPath("$.categoryName").value("ACEITES"));

        // Y queda guardado: el GET devuelve lo mismo.
        mockMvc.perform(get("/api/inventory/products/" + llanta.getId())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedPrice", closeTo(400.0, DELTA)))
                .andExpect(jsonPath("$.taxAmount", closeTo(0.0, DELTA)));
    }

    @Test
    void cambiarSoloElCostoRehaceIvaYPrecioConLaMismaCategoria() throws Exception {
        editarProducto(tokenUno, llanta.getId(), "LLA-001", "Producto LLA-001", 300.0, llantas.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxAmount", closeTo(57.0, DELTA)))
                .andExpect(jsonPath("$.suggestedPrice", closeTo(300.0, DELTA)));
    }

    @Test
    void elStockNoCambiaAunqueSeEnvie() throws Exception {
        mockMvc.perform(put("/api/inventory/products/" + llanta.getId())
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LLA-001","description":"Llanta","purchaseCost":100,"quantity":999,"categoryId":%d}
                                """.formatted(llantas.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(7));
    }

    @Test
    void unCodigoRepetidoEnElTenantResponde409() throws Exception {
        fixture.usarTenant(tenantUno);
        Product otro = fixture.crearProducto(llantas, "LLA-002", 100.0, 1);
        TenantContext.clear();

        editarProducto(tokenUno, otro.getId(), "LLA-001", "Otro", 100.0, llantas.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe un producto con el codigo LLA-001"));
    }

    @Test
    void elCodigoSeCambiaSiElProductoNoTieneFacturas() throws Exception {
        editarProducto(tokenUno, llanta.getId(), "LLA-NUEVO", "Llanta", 100.0, llantas.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("LLA-NUEVO"));
    }

    @Test
    void elCodigoNoSeCambiaSiElProductoTieneLineasDeVenta() throws Exception {
        fixture.usarTenant(tenantUno);
        Counterparty cliente = fixture.crearCliente("Cliente", "900" + System.nanoTime());
        Account caja = fixture.crearCuenta("Caja General");
        salesService.crearFactura(null, cliente, LocalDate.now(), null, PaymentType.CONTADO, caja, "Efectivo",
                List.of(new SalesService.LineaFactura(llanta.getId(), 1, 200.0)));
        TenantContext.clear();

        editarProducto(tokenUno, llanta.getId(), "LLA-NUEVO", "Llanta", 100.0, llantas.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "El codigo LLA-001 no se puede cambiar: el producto ya tiene facturas de venta o de compra"));

        // Con el mismo codigo el resto si se edita.
        editarProducto(tokenUno, llanta.getId(), "LLA-001", "Llanta vendida", 100.0, llantas.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Llanta vendida"));
    }

    @Test
    void elCodigoNoSeCambiaSiElProductoTieneLineasDeCompra() throws Exception {
        fixture.usarTenant(tenantUno);
        Counterparty proveedor = fixture.crearProveedor("Proveedor", "800" + System.nanoTime());
        purchasesService.crearFactura("PROV-1", proveedor, LocalDate.now(), null,
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100.0)));
        TenantContext.clear();

        editarProducto(tokenUno, llanta.getId(), "LLA-NUEVO", "Llanta", 100.0, llantas.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("no se puede cambiar")));
    }

    @Test
    void unaCategoriaDeOtroTenantResponde400() throws Exception {
        Long categoriaDeDos = crearCategoria(tokenDos, "ACEITES", 0.0, List.of());

        editarProducto(tokenUno, llanta.getId(), "LLA-001", "Llanta", 100.0, categoriaDeDos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Categoria no encontrada: " + categoriaDeDos));
    }

    @Test
    void unProductoDeOtroTenantResponde404() throws Exception {
        Long categoriaDeDos = crearCategoria(tokenDos, "ACEITES", 0.0, List.of());

        editarProducto(tokenDos, llanta.getId(), "LLA-001", "Robado", 100.0, categoriaDeDos)
                .andExpect(status().isNotFound());
        editarProducto(tokenUno, 999999L, "X", "X", 100.0, llantas.getId())
                .andExpect(status().isNotFound());

        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getDescription())
                .isEqualTo("Producto LLA-001");
    }

    @Test
    void camposInvalidosDelProductoResponden400() throws Exception {
        for (String cuerpo : List.of(
                "{\"code\":\"\",\"description\":\"Llanta\",\"purchaseCost\":100,\"categoryId\":CAT}",
                "{\"code\":\"LLA-001\",\"description\":\" \",\"purchaseCost\":100,\"categoryId\":CAT}",
                "{\"code\":\"LLA-001\",\"description\":\"Llanta\",\"categoryId\":CAT}",
                "{\"code\":\"LLA-001\",\"description\":\"Llanta\",\"purchaseCost\":\"caro\",\"categoryId\":CAT}",
                "{\"code\":\"LLA-001\",\"description\":\"Llanta\",\"purchaseCost\":100}")) {
            mockMvc.perform(put("/api/inventory/products/" + llanta.getId())
                            .header("Authorization", "Bearer " + tokenUno)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo.replace("CAT", String.valueOf(llantas.getId()))))
                    .andExpect(status().isBadRequest());
        }
        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getDescription())
                .isEqualTo("Producto LLA-001");
    }

    @Test
    void cambiarElCostoNoAlteraUnaVentaYaEmitida() throws Exception {
        fixture.usarTenant(tenantUno);
        Counterparty cliente = fixture.crearCliente("Cliente", "900" + System.nanoTime());
        Account caja = fixture.crearCuenta("Caja General");
        // 2 a 200: subtotal 400, IVA 76, total 476; descontable 2 * 19 = 38.
        Sale venta = salesService.crearFactura(null, cliente, LocalDate.now(), null, PaymentType.CONTADO, caja,
                "Efectivo", List.of(new SalesService.LineaFactura(llanta.getId(), 2, 200.0)));
        TenantContext.clear();

        editarProducto(tokenUno, llanta.getId(), "LLA-001", "Llanta", 500.0, llantas.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxAmount", closeTo(95.0, DELTA)));

        fixture.usarTenant(tenantUno);
        Sale recargada = salesService.findSaleById(venta.getId()).orElseThrow();
        assertThat(recargada.getSubtotal()).isCloseTo(400.0, within(DELTA));
        assertThat(recargada.getTotal()).isCloseTo(476.0, within(DELTA));
        assertThat(recargada.getIvaPorPagar()).isCloseTo(38.0, within(DELTA));
        SaleDetail linea = salesService.findDetailsBySaleId(venta.getId()).get(0);
        assertThat(linea.getPrice()).isCloseTo(200.0, within(DELTA));
        assertThat(linea.getIvaAmount()).isCloseTo(76.0, within(DELTA));
        assertThat(linea.getIvaDifference()).isCloseTo(38.0, within(DELTA));
    }

    @Test
    void editarUnProductoExigeAutenticacion() throws Exception {
        mockMvc.perform(put("/api/inventory/products/" + llanta.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- Categoria ----

    @Test
    void editarTodosLosCamposDeUnaCategoria() throws Exception {
        Long id = crearCategoria(tokenUno, "ACEITES", 0.0, List.of());

        editarCategoria(tokenUno, id, "lubricantes ", "#123456", 8, 2, 0.25, List.of(iva.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("LUBRICANTES"))
                .andExpect(jsonPath("$.color").value("#123456"))
                .andExpect(jsonPath("$.yellowStockMin").value(8))
                .andExpect(jsonPath("$.redStockMin").value(2))
                .andExpect(jsonPath("$.targetMargin", closeTo(0.25, DELTA)))
                .andExpect(jsonPath("$.taxTypes", hasSize(1)))
                .andExpect(jsonPath("$.taxTypes[0].id").value(iva.getId()));

        MvcResult result = mockMvc.perform(get("/api/inventory/categories")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andReturn();
        List<String> nombres = JsonPath.read(result.getResponse().getContentAsString(), "$[*].name");
        assertThat(nombres).containsExactlyInAnyOrder("LLANTAS", "LUBRICANTES");
    }

    @Test
    void unNombreRepetidoResponde409YElPropioNoChoca() throws Exception {
        Long id = crearCategoria(tokenUno, "ACEITES", 0.0, List.of());

        editarCategoria(tokenUno, id, "llantas", "#123456", 5, 2, 0.0, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una categoria con el nombre LLANTAS"));

        editarCategoria(tokenUno, id, "aceites", "#654321", 5, 2, 0.0, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ACEITES"))
                .andExpect(jsonPath("$.color").value("#654321"));

        // Otro tenant puede tener el mismo nombre.
        Long deDos = crearCategoria(tokenDos, "ACEITES", 0.0, List.of());
        editarCategoria(tokenDos, deDos, "LLANTAS", "#000000", 5, 2, 0.0, List.of())
                .andExpect(status().isOk());
    }

    /** Las mismas validaciones que el POST: todo obligatorio salvo los impuestos. */
    @Test
    void umbralesYMargenInvalidosResponden400() throws Exception {
        for (String cuerpo : List.of(
                "{\"name\":\"X\",\"color\":\"#000\",\"redStockMin\":2,\"targetMargin\":0.1}",
                "{\"name\":\"X\",\"color\":\"#000\",\"yellowStockMin\":5,\"targetMargin\":0.1}",
                "{\"name\":\"X\",\"color\":\"#000\",\"yellowStockMin\":5,\"redStockMin\":2}",
                "{\"name\":\"X\",\"color\":\"#000\",\"yellowStockMin\":\"cinco\",\"redStockMin\":2,\"targetMargin\":0.1}",
                "{\"name\":\"X\",\"color\":\"#000\",\"yellowStockMin\":5,\"redStockMin\":2,\"targetMargin\":\"alto\"}",
                "{\"name\":\"\",\"color\":\"#000\",\"yellowStockMin\":5,\"redStockMin\":2,\"targetMargin\":0.1}")) {
            mockMvc.perform(put("/api/inventory/categories/" + llantas.getId())
                            .header("Authorization", "Bearer " + tokenUno)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void unImpuestoDeOtroTenantResponde400() throws Exception {
        fixture.usarTenant(tenantDos);
        TaxType ivaDeDos = fixture.crearCategoriaConIva().getTaxTypes().iterator().next();
        TenantContext.clear();

        editarCategoria(tokenUno, llantas.getId(), "LLANTAS", "#e74c3c", 5, 2, 0.0, List.of(ivaDeDos.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Impuesto no encontrado: " + ivaDeDos.getId()));

        // No cambio nada.
        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findCategoryById(llantas.getId()).orElseThrow().getTaxTypes())
                .extracting(TaxType::getId).containsExactly(iva.getId());
    }

    @Test
    void cambiarElMargenRecalculaSoloLosProductosDeLaCategoria() throws Exception {
        fixture.usarTenant(tenantUno);
        ProductCategory otra = new ProductCategory();
        otra.setName("OTRA");
        otra.setColor("#000000");
        otra.setYellowStockMin(5);
        otra.setRedStockMin(2);
        otra.setTargetMargin(0.0);
        otra.setTaxTypes(Set.of(iva));
        otra = inventoryService.saveCategory(otra);
        Product deOtra = fixture.crearProducto(otra, "OTR-001", 100.0, 1);
        Product segunda = fixture.crearProducto(llantas, "LLA-002", 300.0, 1);
        TenantContext.clear();

        editarCategoria(tokenUno, llantas.getId(), "LLANTAS", "#e74c3c", 5, 2, 0.5, List.of(iva.getId()))
                .andExpect(status().isOk());

        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getSuggestedPrice())
                .isCloseTo(200.0, within(DELTA));
        assertThat(inventoryService.findProductById(segunda.getId()).orElseThrow().getSuggestedPrice())
                .isCloseTo(600.0, within(DELTA));
        assertThat(inventoryService.findProductById(deOtra.getId()).orElseThrow().getSuggestedPrice())
                .isCloseTo(100.0, within(DELTA));
    }

    @Test
    void reemplazarLosImpuestosRehaceElIvaDeLosProductos() throws Exception {
        fixture.usarTenant(tenantUno);
        TaxType reducido = new TaxType();
        reducido.setName("IVA 5");
        reducido.setRate(0.05);
        reducido.setIsVat(Boolean.TRUE);
        reducido.setAppliesToTransaction(Boolean.TRUE);
        reducido = inventoryService.saveTaxType(reducido);
        TenantContext.clear();

        editarCategoria(tokenUno, llantas.getId(), "LLANTAS", "#e74c3c", 5, 2, 0.0, List.of(reducido.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxTypes", hasSize(1)))
                .andExpect(jsonPath("$.taxTypes[0].name").value("IVA 5"));
        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getTaxAmount())
                .isCloseTo(5.0, within(DELTA));
        TenantContext.clear();

        editarCategoria(tokenUno, llantas.getId(), "LLANTAS", "#e74c3c", 5, 2, 0.0, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxTypes", hasSize(0)));
        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findProductById(llanta.getId()).orElseThrow().getTaxAmount())
                .isCloseTo(0.0, within(DELTA));
    }

    @Test
    void unaCategoriaDeOtroTenantResponde404() throws Exception {
        editarCategoria(tokenDos, llantas.getId(), "ROBADA", "#000000", 5, 2, 0.0, List.of())
                .andExpect(status().isNotFound());
        editarCategoria(tokenUno, 999999L, "NADA", "#000000", 5, 2, 0.0, List.of())
                .andExpect(status().isNotFound());

        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findCategoryById(llantas.getId()).orElseThrow().getName()).isEqualTo("LLANTAS");
    }

    @Test
    void editarUnaCategoriaExigeAutenticacion() throws Exception {
        mockMvc.perform(put("/api/inventory/categories/" + llantas.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- Semilla y llamadas ----

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

    private Long crearCategoria(String token, String nombre, double margen, List<Long> impuestos) throws Exception {
        String body = """
                {"name":"%s","color":"#e74c3c","yellowStockMin":5,"redStockMin":2,"targetMargin":%s,"taxTypeIds":%s}
                """.formatted(nombre, margen, impuestos);
        MvcResult result = mockMvc.perform(post("/api/inventory/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private ResultActions editarProducto(String token, Long id, String codigo, String descripcion, double costo,
                                         Long categoriaId) throws Exception {
        return mockMvc.perform(put("/api/inventory/products/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","description":"%s","purchaseCost":%s,"categoryId":%d}
                        """.formatted(codigo, descripcion, costo, categoriaId)));
    }

    private ResultActions editarCategoria(String token, Long id, String nombre, String color, int amarillo, int rojo,
                                          double margen, List<Long> impuestos) throws Exception {
        return mockMvc.perform(put("/api/inventory/categories/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","color":"%s","yellowStockMin":%d,"redStockMin":%d,"targetMargin":%s,"taxTypeIds":%s}
                        """.formatted(nombre, color, amarillo, rojo, margen, impuestos)));
    }
}
