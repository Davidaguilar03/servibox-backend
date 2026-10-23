package com.servibox.backend.purchases;

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
 * Validaciones de POST /api/purchases y de sus pagos: precio mayor que cero, vencimiento en
 * credito, pago no anterior a la factura, y el formato de moneda del 409 de saldo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchasesValidationTest {

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
    private Account banco;
    private Counterparty proveedor;
    private LocalDate hoy;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-compras-validacion-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();
        hoy = LocalDate.now(clock);

        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        treasuryService.registrarIngresoOcasional(caja, "Fondo", 3250000.0, hoy.minusDays(30), null);
        banco = fixture.crearCuenta("Bancolombia");
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

    /** Campos extra (fechas, cuenta) van en `extra`, terminados en coma. */
    private ResultActions comprar(String numero, String paymentType, String extra, String precio) throws Exception {
        return mockMvc.perform(post("/api/purchases")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"invoiceNumber":"%s","supplierId":%d,%s"paymentType":"%s",
                         "details":[{"productId":%d,"quantity":1,"price":%s}]}
                        """.formatted(numero, proveedor.getId(), extra, paymentType, llanta.getId(), precio)));
    }

    private ResultActions pagar(int compra, Account cuenta, String monto, LocalDate fecha) throws Exception {
        return mockMvc.perform(post("/api/purchases/" + compra + "/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":%d,\"amount\":%s,\"date\":\"%s\"}".formatted(cuenta.getId(), monto, fecha)));
    }

    private ResultActions compras() throws Exception {
        return mockMvc.perform(get("/api/purchases").header("Authorization", "Bearer " + token));
    }

    private String credito() {
        return "\"dueDate\":\"%s\",".formatted(hoy.plusDays(30));
    }

    private static void error400(ResultActions resultado, String mensaje) throws Exception {
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(mensaje));
    }

    @Test
    void unPrecioCeroONegativoEs400SinRegistrarLaCompra() throws Exception {
        error400(comprar("FAC-1", "CREDITO", credito(), "0"), "El precio de Producto LLA-001 debe ser mayor a cero");
        error400(comprar("FAC-1", "CREDITO", credito(), "-1"), "El precio de Producto LLA-001 debe ser mayor a cero");
        error400(comprar("FAC-1", "CONTADO", "\"accountId\":%d,".formatted(caja.getId()), "0"),
                "El precio de Producto LLA-001 debe ser mayor a cero");

        compras().andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/inventory/products/" + llanta.getId()).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.quantity").value(10));
    }

    @Test
    void unaCompraACreditoSinVencimientoOConVencimientoAnteriorEs400() throws Exception {
        error400(comprar("FAC-1", "CREDITO", "", "100000"), "Una factura a credito necesita fecha de vencimiento");
        error400(comprar("FAC-1", "CREDITO",
                        "\"invoiceDate\":\"%s\",\"dueDate\":\"%s\",".formatted(hoy, hoy.minusDays(1)), "100000"),
                "El vencimiento no puede ser anterior a la fecha de la factura");
        compras().andExpect(jsonPath("$.length()").value(0));

        comprar("FAC-1", "CREDITO", "\"invoiceDate\":\"%s\",\"dueDate\":\"%s\",".formatted(hoy, hoy), "100000")
                .andExpect(status().isCreated());
    }

    @Test
    void unPagoAnteriorALaFacturaEs400YElMismoDiaPasa() throws Exception {
        LocalDate emitida = hoy.minusDays(5);
        MvcResult creada = comprar("FAC-1", "CREDITO",
                "\"invoiceDate\":\"%s\",\"dueDate\":\"%s\",".formatted(emitida, hoy.plusDays(30)), "100000")
                .andExpect(status().isCreated())
                .andReturn();
        int compra = JsonPath.read(creada.getResponse().getContentAsString(), "$.id");

        error400(pagar(compra, caja, "10000", emitida.minusDays(1)),
                "La fecha del pago no puede ser anterior a la fecha de la factura");
        mockMvc.perform(get("/api/treasury/movements?purchaseId=" + compra).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));

        pagar(compra, caja, "10000", emitida)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(emitida.toString()));
    }

    @Test
    void elSaldoInsuficienteDeUnaCompraDeContadoSeMuestraComoMoneda() throws Exception {
        // 3.540.000 + IVA 19 = 4.212.600; la caja tiene 3.250.000.
        comprar("FAC-1", "CONTADO", "\"accountId\":%d,".formatted(caja.getId()), "3540000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error")
                        .value("Saldo insuficiente en Caja General: hay $ 3.250.000 y se requieren $ 4.212.600"));
    }

    @Test
    void elSaldoInsuficienteDeUnPagoSeMuestraComoMonedaInclusoConDoceMillones() throws Exception {
        MvcResult creada = comprar("FAC-1", "CREDITO", credito(), "12000000")
                .andExpect(status().isCreated())
                .andReturn();
        int compra = JsonPath.read(creada.getResponse().getContentAsString(), "$.id");

        pagar(compra, banco, "12000000", hoy)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Saldo insuficiente en Bancolombia: hay $ 0 y se requieren $ 12.000.000"));
    }
}
