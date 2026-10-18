package com.servibox.backend.treasury;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.sales.SalesFixture;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fecha opcional de abonos y pagos: sin date es hoy en la zona de negocio, una fecha pasada
 * se respeta y una futura es 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CollectionPaymentDateTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private TreasuryService treasuryService;

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
    private Counterparty proveedor;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-fechas-" + System.nanoTime();
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
        treasuryService.registrarIngresoOcasional(caja, "Fondo", 1000000.0, LocalDate.now(clock).minusDays(30), null);
        proveedor = fixture.crearProveedor("Llantas del Norte", "800333444");
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

    private int crear(String ruta, String cuerpo) throws Exception {
        MvcResult result = mockMvc.perform(post(ruta)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private int venderACredito() throws Exception {
        return crear("/api/sales", """
                {"paymentType":"CREDITO","details":[{"productId":%d,"quantity":1,"price":100000}]}
                """.formatted(llanta.getId()));
    }

    private int comprarACredito() throws Exception {
        return crear("/api/purchases", """
                {"invoiceNumber":"FAC-00001","supplierId":%d,"paymentType":"CREDITO",
                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                """.formatted(proveedor.getId(), llanta.getId()));
    }

    /** date null omite el campo; si no, va tal cual dentro de comillas. */
    private ResultActions abonarOPagar(String ruta, String date) throws Exception {
        String campo = date == null ? "" : ",\"date\":\"%s\"".formatted(date);
        return mockMvc.perform(post(ruta)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":%d,\"amount\":10000%s}".formatted(caja.getId(), campo)));
    }

    private ResultActions movimientos(String filtro) throws Exception {
        return mockMvc.perform(get("/api/treasury/movements?" + filtro).header("Authorization", "Bearer " + token));
    }

    @Test
    void unAbonoSinFechaSeFechaHoyEnLaZonaDeNegocio() throws Exception {
        int venta = venderACredito();
        String hoy = LocalDate.now(clock).toString();

        abonarOPagar("/api/sales/" + venta + "/collections", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(hoy));

        movimientos("saleId=" + venta)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value(hoy));
    }

    @Test
    void unAbonoConFechaPasadaSeRespeta() throws Exception {
        int venta = venderACredito();
        String pasada = LocalDate.now(clock).minusDays(5).toString();

        abonarOPagar("/api/sales/" + venta + "/collections", pasada)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(pasada));

        movimientos("saleId=" + venta)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value(pasada));
    }

    @Test
    void unAbonoConFechaFuturaOInvalidaEs400YNoDejaMovimiento() throws Exception {
        int venta = venderACredito();
        LocalDate hoy = LocalDate.now(clock);
        LocalDate manana = hoy.plusDays(1);

        abonarOPagar("/api/sales/" + venta + "/collections", manana.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("La fecha del abono (" + manana + ") no puede ser posterior a hoy (" + hoy + ")"));

        abonarOPagar("/api/sales/" + venta + "/collections", "ayer")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        movimientos("saleId=" + venta).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unPagoSinFechaSeFechaHoyEnLaZonaDeNegocio() throws Exception {
        int compra = comprarACredito();
        String hoy = LocalDate.now(clock).toString();

        abonarOPagar("/api/purchases/" + compra + "/payments", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(hoy));

        movimientos("purchaseId=" + compra)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value(hoy));
    }

    @Test
    void unPagoConFechaPasadaSeRespeta() throws Exception {
        int compra = comprarACredito();
        String pasada = LocalDate.now(clock).minusDays(5).toString();

        abonarOPagar("/api/purchases/" + compra + "/payments", pasada)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(pasada));

        movimientos("purchaseId=" + compra)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value(pasada));
    }

    @Test
    void unPagoConFechaFuturaOInvalidaEs400YNoDejaMovimiento() throws Exception {
        int compra = comprarACredito();
        LocalDate hoy = LocalDate.now(clock);
        LocalDate manana = hoy.plusDays(1);

        abonarOPagar("/api/purchases/" + compra + "/payments", manana.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("La fecha del pago (" + manana + ") no puede ser posterior a hoy (" + hoy + ")"));

        abonarOPagar("/api/purchases/" + compra + "/payments", "2026-13-40")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        movimientos("purchaseId=" + compra).andExpect(jsonPath("$.length()").value(0));
    }
}
