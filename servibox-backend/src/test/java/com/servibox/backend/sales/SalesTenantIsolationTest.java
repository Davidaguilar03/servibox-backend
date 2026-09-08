package com.servibox.backend.sales;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.service.DuplicateInvoiceNumberException;
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
 * Aislamiento del modulo sales de punta a punta y unicidad del numero de factura por
 * tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesTenantIsolationTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

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

    /** Siembra un tenant completo y le emite una factura de contado. */
    private Sale facturarEn(Long tenantId, String numeroFactura, String codigoProducto, String documentoCliente) {
        TenantContext.setTenantId(tenantId);
        try {
            ProductCategory categoria = fixture.crearCategoriaConIva();
            Product producto = fixture.crearProducto(categoria, codigoProducto, 100000.0, 10);
            Account cuenta = fixture.crearCuenta("Caja General");
            Customer cliente = fixture.crearCliente("Cliente " + documentoCliente, documentoCliente);
            return salesService.crearFactura(numeroFactura, cliente, LocalDate.now(), null,
                    PaymentType.CONTADO, cuenta, "Efectivo",
                    List.of(new SalesService.LineaFactura(producto.getId(), 1, 100000.0)));
        } finally {
            TenantContext.clear();
        }
    }

    private List<String> facturasVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sales")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].invoiceNumber");
    }

    @Test
    void unaFacturaDeUnTenantNoEsVisibleParaOtro() throws Exception {
        facturarEn(tenantUno, "VEN-UNO", "LLA-UNO", "900000001");
        facturarEn(tenantDos, "VEN-DOS", "LLA-DOS", "900000002");

        assertThat(facturasVisiblesPara(tokenUno)).containsExactly("VEN-UNO");
        assertThat(facturasVisiblesPara(tokenDos)).containsExactly("VEN-DOS");
    }

    @Test
    void laFacturaDeOtroTenantNoSeDevuelvePorId() throws Exception {
        Sale deUno = facturarEn(tenantUno, "VEN-UNO", "LLA-UNO", "900000001");
        facturarEn(tenantDos, "VEN-DOS", "LLA-DOS", "900000002");

        mockMvc.perform(get("/api/sales/" + deUno.getId())
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());

        // Su dueno si la ve, con sus lineas y su cliente.
        mockMvc.perform(get("/api/sales/" + deUno.getId())
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("VEN-UNO"))
                .andExpect(jsonPath("$.status").value("PAGADA"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].ivaAmount").value(19000.0))
                .andExpect(jsonPath("$.customerName").value("Cliente 900000001"));
    }

    /** Ni los detalles ni los clientes del otro tenant se cuelan por el GET de la factura. */
    @Test
    void losDetallesYClientesTampocoSeCruzanEntreTenants() throws Exception {
        facturarEn(tenantUno, "VEN-UNO", "LLA-UNO", "900000001");
        facturarEn(tenantDos, "VEN-DOS", "LLA-DOS", "900000002");

        TenantContext.setTenantId(tenantUno);
        assertThat(salesService.findAllCustomers())
                .extracting(Customer::getDocument)
                .containsExactly("900000001");

        Sale deUno = salesService.findAllSales().get(0);
        assertThat(salesService.detallesDe(deUno))
                .singleElement()
                .satisfies(d -> assertThat(d.getProduct().getCode()).isEqualTo("LLA-UNO"));

        TenantContext.setTenantId(tenantDos);
        assertThat(salesService.findAllSales())
                .extracting(Sale::getInvoiceNumber)
                .containsExactly("VEN-DOS");
    }

    @Test
    void elMismoNumeroDeFacturaEsValidoEnTenantsDistintos() {
        facturarEn(tenantUno, "VEN-00001", "LLA-UNO", "900000001");

        assertThatCode(() -> facturarEn(tenantDos, "VEN-00001", "LLA-DOS", "900000002"))
                .doesNotThrowAnyException();

        TenantContext.setTenantId(tenantUno);
        assertThat(salesService.findAllSales()).hasSize(1);
        TenantContext.setTenantId(tenantDos);
        assertThat(salesService.findAllSales()).hasSize(1);
    }

    @Test
    void elMismoNumeroDeFacturaDentroDelMismoTenantFalla() throws Exception {
        facturarEn(tenantUno, "VEN-00001", "LLA-UNO", "900000001");

        TenantContext.setTenantId(tenantUno);
        Product otro = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-OTRO", 50000.0, 5);
        Account cuenta = fixture.crearCuenta("Bancolombia");

        assertThatThrownBy(() -> salesService.crearFactura("VEN-00001", null, LocalDate.now(), null,
                PaymentType.CONTADO, cuenta, "Efectivo",
                List.of(new SalesService.LineaFactura(otro.getId(), 1, 50000.0))))
                .isInstanceOf(DuplicateInvoiceNumberException.class)
                .hasMessage("Ya existe una factura con el numero VEN-00001");
    }

    @Test
    void abonarYAnularUnaFacturaAjenaNoEsPosible() throws Exception {
        Sale deUno = facturarEn(tenantUno, "VEN-UNO", "LLA-UNO", "900000001");
        facturarEn(tenantDos, "VEN-DOS", "LLA-DOS", "900000002");

        mockMvc.perform(post("/api/sales/" + deUno.getId() + "/annul")
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/sales/" + deUno.getId() + "/collections")
                        .header("Authorization", "Bearer " + tokenDos)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":1,"amount":1000}
                                """))
                .andExpect(status().isNotFound());

        // Sigue PAGADA para su dueno.
        TenantContext.setTenantId(tenantUno);
        assertThat(salesService.findSaleById(deUno.getId()).orElseThrow().getStatus())
                .isEqualTo(SaleStatus.PAGADA);
    }

    @Test
    void ventasExigeAutenticacion() throws Exception {
        mockMvc.perform(get("/api/sales")).andExpect(status().isUnauthorized());
    }
}
