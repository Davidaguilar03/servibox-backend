package com.servibox.backend.sales;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.service.SalesService;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consecutivo de la factura de venta: FV- y al menos 4 digitos, el mayor del tenant
 * (anuladas incluidas) mas uno, cuando la venta llega sin invoiceNumber.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesInvoiceNumberingTest {

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

    private Long tenantId;
    private String token;
    private Product llanta;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-numeracion-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        tenantId = tenantRepository.save(tenant).getId();

        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 100);
        TenantContext.clear();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    /** Venta a credito por la API; numero null omite el campo. */
    private ResultActions vender(String numero) throws Exception {
        String campo = numero == null ? "" : "\"invoiceNumber\":\"%s\",".formatted(numero);
        return mockMvc.perform(post("/api/sales")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {%s"dueDate":"2099-12-31","paymentType":"CREDITO",
                         "details":[{"productId":%d,"quantity":1,"price":100000}]}
                        """.formatted(campo, llanta.getId())));
    }

    private String numeroDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.invoiceNumber");
    }

    private ResultActions siguiente() throws Exception {
        return mockMvc.perform(get("/api/sales/next-invoice-number").header("Authorization", "Bearer " + token));
    }

    @Test
    void laPrimeraVentaSinNumeroRecibeFV0001YLaSegundaFV0002() throws Exception {
        vender(null).andExpect(status().isCreated()).andExpect(jsonPath("$.invoiceNumber").value("FV-0001"));
        vender(null).andExpect(status().isCreated()).andExpect(jsonPath("$.invoiceNumber").value("FV-0002"));
    }

    @Test
    void unNumeroEnBlancoRecibeNumeroGenerado() throws Exception {
        vender("").andExpect(status().isCreated()).andExpect(jsonPath("$.invoiceNumber").value("FV-0001"));
        vender("   ").andExpect(status().isCreated()).andExpect(jsonPath("$.invoiceNumber").value("FV-0002"));
    }

    @Test
    void unNumeroExplicitoSeRespetaYRepetidoEs409() throws Exception {
        vender("VEN-00001").andExpect(status().isCreated()).andExpect(jsonPath("$.invoiceNumber").value("VEN-00001"));

        vender("VEN-00001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una factura con el numero VEN-00001"));

        // Un FV- explicito que choca con el siguiente generado tambien es 409.
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0001"));
        vender("FV-0001").andExpect(status().isConflict());
    }

    @Test
    void conFV0009ExistenteLaSiguienteEsFV0010() throws Exception {
        vender("FV-0009").andExpect(status().isCreated());

        siguiente().andExpect(status().isOk()).andExpect(jsonPath("$.invoiceNumber").value("FV-0010"));
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0010"));
    }

    @Test
    void pasadoFV9999SigueConCincoDigitos() throws Exception {
        vender("FV-9999").andExpect(status().isCreated());

        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-10000"));
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-10001"));
    }

    @Test
    void losNumerosDeOtroFormatoSeIgnoran() throws Exception {
        for (String otro : List.of("A-15", "FV-12A", "fv-0050", "XFV-0099", "FV-")) {
            vender(otro).andExpect(status().isCreated());
        }

        siguiente().andExpect(jsonPath("$.invoiceNumber").value("FV-0001"));
        vender("FV-0003").andExpect(status().isCreated());
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0004"));
    }

    @Test
    void elNumeroDeUnaVentaAnuladaNoSeReutiliza() throws Exception {
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0001"));
        int segunda = JsonPath.read(vender(null).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/sales/" + segunda + "/annul").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"))
                .andExpect(jsonPath("$.invoiceNumber").value("FV-0002"));

        siguiente().andExpect(jsonPath("$.invoiceNumber").value("FV-0003"));
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0003"));
    }

    @Test
    void dosTenantsLlevanConsecutivosIndependientes() throws Exception {
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0001"));
        vender(null).andExpect(jsonPath("$.invoiceNumber").value("FV-0002"));

        fixture.usarTenant(fixture.crearTenant());
        Product otraLlanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        assertThat(salesService.siguienteNumeroFactura()).isEqualTo("FV-0001");
        String deOtro = salesService.crearFactura(null, null, LocalDate.now(), LocalDate.now().plusDays(30), PaymentType.CREDITO, null, null,
                List.of(new SalesService.LineaFactura(otraLlanta.getId(), 1, 100000.0))).getInvoiceNumber();
        assertThat(deOtro).isEqualTo("FV-0001");
        TenantContext.clear();

        siguiente().andExpect(jsonPath("$.invoiceNumber").value("FV-0003"));
    }

    @Test
    void elGetDevuelveElNumeroQueAsignaElPostSinReservarlo() throws Exception {
        vender(null).andExpect(status().isCreated());

        String primero = numeroDe(siguiente().andExpect(status().isOk()));
        String segundo = numeroDe(siguiente().andExpect(status().isOk()));
        assertThat(primero).isEqualTo("FV-0002").isEqualTo(segundo);

        fixture.usarTenant(tenantId);
        assertThat(salesService.findAllSales()).hasSize(1);
        TenantContext.clear();

        vender(null).andExpect(jsonPath("$.invoiceNumber").value(primero));
    }

    @Test
    void elGetSinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/sales/next-invoice-number")).andExpect(status().isUnauthorized());
    }
}
