package com.servibox.backend.inventory;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.repository.FinancialSettingsRepository;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/inventory/tax-types y GET / PUT /api/inventory/financial-settings, por HTTP con
 * dos tenants reales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventorySettingsApiTest {

    private static final String PASSWORD = "clave-correcta";
    private static final double DELTA = 0.001;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private FinancialSettingsRepository financialSettingsRepository;

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

    // ---- Impuestos ----

    @Test
    void listaLosImpuestosDelTenantPorNombre() throws Exception {
        fixture.usarTenant(tenantUno);
        impuesto("ReteICA", 0.0082, false);
        impuesto("IVA", 0.19, true);
        impuesto("ReteFuente", 0.025, false);
        TenantContext.clear();

        mockMvc.perform(get("/api/inventory/tax-types").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("IVA"))
                .andExpect(jsonPath("$[0].rate", closeTo(0.19, DELTA)))
                .andExpect(jsonPath("$[0].isVat").value(true))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[1].name").value("ReteFuente"))
                .andExpect(jsonPath("$[2].name").value("ReteICA"))
                .andExpect(jsonPath("$[2].isVat").value(false))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void losImpuestosNoSeCruzanEntreTenants() throws Exception {
        fixture.usarTenant(tenantUno);
        impuesto("IVA", 0.19, true);
        fixture.usarTenant(tenantDos);
        impuesto("IVA DOS", 0.05, true);
        TenantContext.clear();

        assertThat(nombresDeImpuestos(tokenUno)).containsExactly("IVA");
        assertThat(nombresDeImpuestos(tokenDos)).containsExactly("IVA DOS");
    }

    @Test
    void losImpuestosExigenAutenticacion() throws Exception {
        mockMvc.perform(get("/api/inventory/tax-types")).andExpect(status().isUnauthorized());
    }

    // ---- Configuracion financiera ----

    @Test
    void sinConfiguracionGuardadaDevuelveCeros() throws Exception {
        mockMvc.perform(get("/api/inventory/financial-settings").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate").value(0.0))
                .andExpect(jsonPath("$.dianRate").value(0.0))
                .andExpect(jsonPath("$.icaRate").value(0.0))
                .andExpect(jsonPath("$.cardCommissionRate").value(0.0));
    }

    @Test
    void elPrimerPutCreaYElSegundoActualizaLaMismaFila() throws Exception {
        guardar(tokenUno, 0.10, 0.02, 0.0082, 0.035)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate", closeTo(0.10, DELTA)))
                .andExpect(jsonPath("$.dianRate", closeTo(0.02, DELTA)))
                .andExpect(jsonPath("$.icaRate", closeTo(0.0082, DELTA)))
                .andExpect(jsonPath("$.cardCommissionRate", closeTo(0.035, DELTA)));

        guardar(tokenUno, 0.15, 0.0, 0.0, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate", closeTo(0.15, DELTA)))
                .andExpect(jsonPath("$.cardCommissionRate").value(0.0));

        mockMvc.perform(get("/api/inventory/financial-settings").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate", closeTo(0.15, DELTA)))
                .andExpect(jsonPath("$.dianRate").value(0.0));

        fixture.usarTenant(tenantUno);
        assertThat(financialSettingsRepository.findAll()).hasSize(1);
    }

    @Test
    void porcentajesFueraDeRangoResponden400() throws Exception {
        for (String cuerpo : List.of(
                "{\"expensesRate\":-0.01,\"dianRate\":0,\"icaRate\":0}",
                "{\"expensesRate\":0,\"dianRate\":1.5,\"icaRate\":0}",
                "{\"expensesRate\":0,\"dianRate\":0,\"icaRate\":2}",
                "{\"expensesRate\":0,\"dianRate\":0,\"icaRate\":0,\"cardCommissionRate\":-1}",
                "{\"dianRate\":0,\"icaRate\":0}",
                "{\"expensesRate\":\"diez\",\"dianRate\":0,\"icaRate\":0}")) {
            mockMvc.perform(put("/api/inventory/financial-settings")
                            .header("Authorization", "Bearer " + tokenUno)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isBadRequest());
        }
        // Los extremos 0 y 1 si valen.
        guardar(tokenUno, 1.0, 0.0, 0.0, 1.0).andExpect(status().isOk());
    }

    @Test
    void laConfiguracionNoSeCruzaEntreTenants() throws Exception {
        guardar(tokenUno, 0.10, 0.02, 0.0082, 0.035).andExpect(status().isOk());

        mockMvc.perform(get("/api/inventory/financial-settings").header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate").value(0.0));

        guardar(tokenDos, 0.20, 0.0, 0.0, null).andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/financial-settings").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.expensesRate", closeTo(0.10, DELTA)));
    }

    @Test
    void laConfiguracionExigeAutenticacion() throws Exception {
        mockMvc.perform(get("/api/inventory/financial-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/inventory/financial-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expensesRate\":0,\"dianRate\":0,\"icaRate\":0}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Costo 100, margen 0.30: sin configuracion 100 / 0.70 = 142.86 (precio_sugerido tiene
     * 2 decimales en la base). Con gastos 0.10,
     * k = 0.90 y precio = 100 * 0.90 / (0.90 - 0.30) = 150. Solo cambian los del tenant.
     */
    @Test
    void cambiarLaConfiguracionRecalculaLosPreciosDelTenant() throws Exception {
        fixture.usarTenant(tenantUno);
        Product deUno = productoConMargen("UNO-001", 0.30);
        fixture.usarTenant(tenantDos);
        Product deDos = productoConMargen("DOS-001", 0.30);
        TenantContext.clear();

        guardar(tokenUno, 0.10, 0.0, 0.0, null).andExpect(status().isOk());

        fixture.usarTenant(tenantUno);
        assertThat(inventoryService.findProductById(deUno.getId()).orElseThrow().getSuggestedPrice())
                .isCloseTo(150.0, within(DELTA));
        fixture.usarTenant(tenantDos);
        assertThat(inventoryService.findProductById(deDos.getId()).orElseThrow().getSuggestedPrice())
                .isCloseTo(142.86, within(DELTA));
    }

    // ---- Semilla y llamadas ----

    private TaxType impuesto(String nombre, double tasa, boolean esIva) {
        TaxType tax = new TaxType();
        tax.setName(nombre);
        tax.setRate(tasa);
        tax.setIsVat(esIva);
        tax.setAppliesToTransaction(Boolean.TRUE);
        return inventoryService.saveTaxType(tax);
    }

    private Product productoConMargen(String codigo, double margen) {
        ProductCategory categoria = new ProductCategory();
        categoria.setName("LLANTAS");
        categoria.setColor("#e74c3c");
        categoria.setYellowStockMin(5);
        categoria.setRedStockMin(2);
        categoria.setTargetMargin(margen);
        categoria.setTaxTypes(Set.of(impuesto("IVA", 0.19, true)));
        return fixture.crearProducto(inventoryService.saveCategory(categoria), codigo, 100.0, 1);
    }

    private List<String> nombresDeImpuestos(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/inventory/tax-types").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].name");
    }

    private ResultActions guardar(String token, double gastos, double dian, double ica, Double comision)
            throws Exception {
        return mockMvc.perform(put("/api/inventory/financial-settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"expensesRate":%s,"dianRate":%s,"icaRate":%s,"cardCommissionRate":%s}
                        """.formatted(gastos, dian, ica, comision)));
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
}
