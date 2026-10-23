package com.servibox.backend.sales;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
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

import java.time.Clock;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validaciones de POST /api/sales y de sus abonos: precio mayor que cero, vencimiento
 * obligatorio y no anterior a la factura en credito, y abono no anterior a la factura.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesValidationTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private String token;
    private Product llanta;
    private Account caja;
    private LocalDate hoy;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-ventas-validacion-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        TenantContext.clear();
        hoy = LocalDate.now(clock);

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

    /** Campos extra (fechas, cuenta) van en `extra`, terminados en coma. */
    private ResultActions vender(String paymentType, String extra, String precio) throws Exception {
        return mockMvc.perform(post("/api/sales")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {%s"paymentType":"%s","details":[{"productId":%d,"quantity":1,"price":%s}]}
                        """.formatted(extra, paymentType, llanta.getId(), precio)));
    }

    private ResultActions abonar(int venta, LocalDate fecha) throws Exception {
        return mockMvc.perform(post("/api/sales/" + venta + "/collections")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":%d,\"amount\":10000,\"date\":\"%s\"}".formatted(caja.getId(), fecha)));
    }

    private ResultActions ventas() throws Exception {
        return mockMvc.perform(get("/api/sales").header("Authorization", "Bearer " + token));
    }

    private ResultActions stock() throws Exception {
        return mockMvc.perform(get("/api/inventory/products/" + llanta.getId()).header("Authorization", "Bearer " + token));
    }

    private static void error400(ResultActions resultado, String mensaje) throws Exception {
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(mensaje));
    }

    @Test
    void unPrecioCeroONegativoEs400SinFacturarNiDescontarStock() throws Exception {
        String cuenta = "\"accountId\":%d,".formatted(caja.getId());
        error400(vender("CONTADO", cuenta, "0"), "El precio de Producto LLA-001 debe ser mayor a cero");
        error400(vender("CONTADO", cuenta, "-5000"), "El precio de Producto LLA-001 debe ser mayor a cero");
        String credito = "\"dueDate\":\"%s\",".formatted(hoy.plusDays(30));
        error400(vender("CREDITO", credito, "0"), "El precio de Producto LLA-001 debe ser mayor a cero");

        ventas().andExpect(jsonPath("$.length()").value(0));
        stock().andExpect(jsonPath("$.quantity").value(10));
    }

    @Test
    void unaVentaACreditoSinVencimientoEs400() throws Exception {
        error400(vender("CREDITO", "", "100000"), "Una factura a credito necesita fecha de vencimiento");

        ventas().andExpect(jsonPath("$.length()").value(0));
        stock().andExpect(jsonPath("$.quantity").value(10));
    }

    @Test
    void unVencimientoAnteriorALaFacturaEs400YElMismoDiaPasa() throws Exception {
        String anterior = "\"invoiceDate\":\"%s\",\"dueDate\":\"%s\",".formatted(hoy, hoy.minusDays(1));
        error400(vender("CREDITO", anterior, "100000"), "El vencimiento no puede ser anterior a la fecha de la factura");

        // Sin invoiceDate la factura es de hoy: un vencimiento de ayer tambien es anterior.
        error400(vender("CREDITO", "\"dueDate\":\"%s\",".formatted(hoy.minusDays(1)), "100000"),
                "El vencimiento no puede ser anterior a la fecha de la factura");

        vender("CREDITO", "\"invoiceDate\":\"%s\",\"dueDate\":\"%s\",".formatted(hoy, hoy), "100000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value(hoy.toString()));
    }

    @Test
    void enContadoElVencimientoNoSeValida() throws Exception {
        vender("CONTADO", "\"accountId\":%d,\"dueDate\":\"%s\",".formatted(caja.getId(), hoy.minusDays(10)), "100000")
                .andExpect(status().isCreated());
    }

    @Test
    void unAbonoAnteriorALaFacturaEs400YElMismoDiaPasa() throws Exception {
        LocalDate emitida = hoy.minusDays(5);
        MvcResult creada = vender("CREDITO",
                "\"invoiceDate\":\"%s\",\"dueDate\":\"%s\",".formatted(emitida, hoy.plusDays(30)), "100000")
                .andExpect(status().isCreated())
                .andReturn();
        int venta = JsonPath.read(creada.getResponse().getContentAsString(), "$.id");

        error400(abonar(venta, emitida.minusDays(1)),
                "La fecha del abono no puede ser anterior a la fecha de la factura");
        mockMvc.perform(get("/api/treasury/movements?saleId=" + venta).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));

        abonar(venta, emitida)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(emitida.toString()));
    }
}
