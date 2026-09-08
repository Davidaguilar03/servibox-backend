package com.servibox.backend.purchases;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.purchases.entity.Supplier;
import com.servibox.backend.purchases.repository.PurchaseRepository;
import com.servibox.backend.purchases.repository.SupplierRepository;
import com.servibox.backend.purchases.service.PurchasesService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento del modulo purchases y unicidad del numero de factura por tenant, con la
 * restriccion de base verificada saltandose el service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchasesTenantIsolationTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private SupplierRepository supplierRepository;

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

    /** Siembra un tenant completo y le registra una compra a credito. */
    private Purchase comprarEn(Long tenantId, String numero, String codigoProducto, String nit) {
        TenantContext.setTenantId(tenantId);
        try {
            ProductCategory categoria = fixture.crearCategoriaConIva();
            Product producto = fixture.crearProducto(categoria, codigoProducto, 100000.0, 10);

            Supplier proveedor = new Supplier();
            proveedor.setName("Proveedor " + nit);
            proveedor.setBusinessName("Proveedor " + nit + " S.A.S.");
            proveedor.setDocument(nit);
            Supplier guardado = purchasesService.saveSupplier(proveedor);

            return purchasesService.crearFactura(numero, guardado, LocalDate.now(), null,
                    PaymentType.CREDITO, null, null,
                    List.of(new PurchasesService.LineaCompra(producto.getId(), 1, 100000.0)));
        } finally {
            TenantContext.clear();
        }
    }

    private List<String> comprasVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/purchases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].invoiceNumber");
    }

    @Test
    void unaCompraDeUnTenantNoEsVisibleParaOtro() throws Exception {
        comprarEn(tenantUno, "FAC-UNO", "LLA-UNO", "900000001");
        comprarEn(tenantDos, "FAC-DOS", "LLA-DOS", "900000002");

        assertThat(comprasVisiblesPara(tokenUno)).containsExactly("FAC-UNO");
        assertThat(comprasVisiblesPara(tokenDos)).containsExactly("FAC-DOS");
    }

    @Test
    void laCompraDeOtroTenantNoSeDevuelvePorId() throws Exception {
        Purchase deUno = comprarEn(tenantUno, "FAC-UNO", "LLA-UNO", "900000001");
        comprarEn(tenantDos, "FAC-DOS", "LLA-DOS", "900000002");

        mockMvc.perform(get("/api/purchases/" + deUno.getId())
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/purchases/" + deUno.getId())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("FAC-UNO"))
                .andExpect(jsonPath("$.status").value("PENDIENTE"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].productCode").value("LLA-UNO"))
                .andExpect(jsonPath("$.supplierName").value("Proveedor 900000001"));
    }

    /** Ni los detalles ni los proveedores del otro tenant se cuelan. */
    @Test
    void losDetallesYProveedoresTampocoSeCruzanEntreTenants() {
        comprarEn(tenantUno, "FAC-UNO", "LLA-UNO", "900000001");
        comprarEn(tenantDos, "FAC-DOS", "LLA-DOS", "900000002");

        TenantContext.setTenantId(tenantUno);
        assertThat(purchasesService.findAllSuppliers())
                .extracting(Supplier::getDocument)
                .containsExactly("900000001");

        Purchase deUno = purchasesService.findAllPurchases().get(0);
        assertThat(purchasesService.detallesDe(deUno))
                .singleElement()
                .satisfies(d -> assertThat(d.getProduct().getCode()).isEqualTo("LLA-UNO"));

        TenantContext.setTenantId(tenantDos);
        assertThat(purchasesService.findAllPurchases())
                .extracting(Purchase::getInvoiceNumber)
                .containsExactly("FAC-DOS");
    }

    @Test
    void pagarYAnularUnaCompraAjenaNoEsPosible() throws Exception {
        Purchase deUno = comprarEn(tenantUno, "FAC-UNO", "LLA-UNO", "900000001");
        comprarEn(tenantDos, "FAC-DOS", "LLA-DOS", "900000002");

        mockMvc.perform(post("/api/purchases/" + deUno.getId() + "/annul")
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/purchases/" + deUno.getId() + "/payments")
                        .header("Authorization", "Bearer " + tokenDos)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":1,"amount":1000}
                                """))
                .andExpect(status().isNotFound());

        TenantContext.setTenantId(tenantUno);
        assertThat(purchasesService.findPurchaseById(deUno.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseStatus.PENDIENTE);
    }

    @Test
    void elMismoNumeroDeFacturaEsValidoEnTenantsDistintos() {
        comprarEn(tenantUno, "FAC-00001", "LLA-UNO", "900000001");

        assertThatCode(() -> comprarEn(tenantDos, "FAC-00001", "LLA-DOS", "900000002"))
                .doesNotThrowAnyException();

        TenantContext.setTenantId(tenantUno);
        assertThat(purchasesService.findAllPurchases()).hasSize(1);
        TenantContext.setTenantId(tenantDos);
        assertThat(purchasesService.findAllPurchases()).hasSize(1);
    }

    /**
     * La validacion del service no reemplaza a la restriccion de base. Esto prueba que
     * uk_compra_tenant_invoice_number y uk_proveedor_tenant_document existen en el DDL, que
     * es la divergencia deliberada frente a Autollantas.
     */
    @Test
    void laBaseFrenaNumerosYNitsRepetidosAunSaltandoseElService() {
        TenantContext.setTenantId(tenantUno);

        purchaseRepository.saveAndFlush(compraCruda("FAC-CRUDA"));
        assertThatThrownBy(() -> purchaseRepository.saveAndFlush(compraCruda("FAC-CRUDA")))
                .isInstanceOf(DataIntegrityViolationException.class);

        supplierRepository.saveAndFlush(proveedorCrudo("900555444"));
        assertThatThrownBy(() -> supplierRepository.saveAndFlush(proveedorCrudo("900555444")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Purchase compraCruda(String numero) {
        Purchase compra = new Purchase();
        compra.setInvoiceNumber(numero);
        compra.setInvoiceDate(LocalDate.now());
        compra.setPaymentType(PaymentType.CREDITO);
        compra.setStatus(PurchaseStatus.PENDIENTE);
        compra.setSubtotal(0.0);
        compra.setIvaTotal(0.0);
        compra.setTotal(0.0);
        return compra;
    }

    private Supplier proveedorCrudo(String nit) {
        Supplier proveedor = new Supplier();
        proveedor.setName("Proveedor " + nit);
        proveedor.setDocument(nit);
        return proveedor;
    }

    @Test
    void comprasExigeAutenticacion() throws Exception {
        mockMvc.perform(get("/api/purchases")).andExpect(status().isUnauthorized());
    }
}
