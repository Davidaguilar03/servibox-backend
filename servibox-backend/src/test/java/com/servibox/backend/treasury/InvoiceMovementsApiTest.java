package com.servibox.backend.treasury;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Sale;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los movimientos de facturas vistos por la API: abonos y pagos como movimientos, los
 * filtros saleId y purchaseId, y que los de una factura ANULADA no se expongan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceMovementsApiTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private TreasuryService treasuryService;

    private Long tenantUno;
    private Long tenantDos;
    private String tokenUno;
    private String tokenDos;

    /** Semilla de tenantUno. */
    private Product llanta;
    private Account caja;
    private Account banco;
    private Counterparty cliente;
    private Counterparty proveedor;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenantConUsuario(slugUno);
        tenantDos = crearTenantConUsuario(slugDos);
        tokenUno = login(slugUno);
        tokenDos = login(slugDos);

        fixture.usarTenant(tenantUno);
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        banco = fixture.crearCuenta("Bancolombia");
        treasuryService.registrarIngresoOcasional(banco, "Fondeo de prueba", 1000000.0, null, null);
        cliente = fixture.crearCliente("Cliente Uno", "900123456");
        proveedor = fixture.crearProveedor("Importadora Andina", "900987654");
        TenantContext.clear();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Long crearTenantConUsuario(String slug) {
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        TenantContext.setTenantId(tenantId);
        try {
            User user = new User();
            user.setUsername("admin");
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setActive(true);
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
        return tenantId;
    }

    private String login(String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Siembra en tenantUno y deja el contexto limpio para la peticion HTTP. */
    private Sale venderACredito(String numero, int cantidad) {
        fixture.usarTenant(tenantUno);
        try {
            return salesService.crearFactura(numero, cliente, LocalDate.now(), null,
                    com.servibox.backend.sales.entity.PaymentType.CREDITO, null, null,
                    List.of(new SalesService.LineaFactura(llanta.getId(), cantidad, 100000.0)));
        } finally {
            TenantContext.clear();
        }
    }

    private Purchase comprarACredito(String numero) {
        fixture.usarTenant(tenantUno);
        try {
            return purchasesService.crearFactura(numero, proveedor, LocalDate.now(), null,
                    com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                    List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100000.0)));
        } finally {
            TenantContext.clear();
        }
    }

    private Integer abonar(Sale venta, Account cuenta, int monto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sales/" + venta.getId() + "/collections")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"amount":%d}
                                """.formatted(cuenta.getId(), monto)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private Integer pagar(Purchase compra, Account cuenta, int monto) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchases/" + compra.getId() + "/payments")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"amount":%d}
                                """.formatted(cuenta.getId(), monto)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private List<Integer> ids(String token, String url, String... filtros) throws Exception {
        var peticion = get(url).header("Authorization", "Bearer " + token);
        for (int i = 0; i < filtros.length; i += 2) {
            peticion.param(filtros[i], filtros[i + 1]);
        }
        MvcResult result = mockMvc.perform(peticion).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].id");
    }

    private void accion(String url, int estadoEsperado) throws Exception {
        mockMvc.perform(post(url).header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().is(estadoEsperado));
    }

    /** Las rutas de abono y pago se conservan y responden con el movimiento creado. */
    @Test
    void abonosYPagosRespondenConSuMovimiento() throws Exception {
        Sale venta = venderACredito("VEN-00001", 1);
        Purchase compra = comprarACredito("FAC-00001");

        mockMvc.perform(post("/api/sales/" + venta.getId() + "/collections")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"amount":19000}
                                """.formatted(caja.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("RECAUDO_VENTA"))
                .andExpect(jsonPath("$.type").value("INGRESO"))
                .andExpect(jsonPath("$.concept").value("Abono factura VEN-00001"))
                .andExpect(jsonPath("$.accountId").value(caja.getId()))
                .andExpect(jsonPath("$.saleId").value(venta.getId()))
                .andExpect(jsonPath("$.purchaseId").doesNotExist())
                .andExpect(jsonPath("$.sourceType").doesNotExist());

        mockMvc.perform(post("/api/purchases/" + compra.getId() + "/payments")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"amount":19000}
                                """.formatted(banco.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("PAGO_COMPRA"))
                .andExpect(jsonPath("$.type").value("EGRESO"))
                .andExpect(jsonPath("$.concept").value("Pago compra FAC-00001"))
                .andExpect(jsonPath("$.purchaseId").value(compra.getId()))
                .andExpect(jsonPath("$.saleId").doesNotExist());

        // La factura ya no expone cuenta: la cuenta vive en el movimiento.
        mockMvc.perform(get("/api/sales/" + venta.getId()).header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingBalance").value(100000.0))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.accountName").doesNotExist());
        mockMvc.perform(get("/api/purchases/" + compra.getId()).header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingBalance").value(100000.0))
                .andExpect(jsonPath("$.accountId").doesNotExist());
    }

    @Test
    void elLibroFiltraPorSaleIdYPurchaseIdSinMostrarOtroTenant() throws Exception {
        Sale venta = venderACredito("VEN-00002", 1);
        Sale otraVenta = venderACredito("VEN-00003", 1);
        Purchase compra = comprarACredito("FAC-00002");
        Integer abono1 = abonar(venta, caja, 10000);
        Integer abono2 = abonar(venta, banco, 20000);
        Integer abonoOtra = abonar(otraVenta, caja, 5000);
        Integer pago = pagar(compra, banco, 30000);

        assertThat(ids(tokenUno, "/api/treasury/movements", "saleId", venta.getId().toString()))
                .containsExactlyInAnyOrder(abono1, abono2);
        assertThat(ids(tokenUno, "/api/treasury/movements", "saleId", otraVenta.getId().toString()))
                .containsExactly(abonoOtra);
        assertThat(ids(tokenUno, "/api/treasury/movements", "purchaseId", compra.getId().toString()))
                .containsExactly(pago);
        assertThat(ids(tokenUno, "/api/treasury/movements",
                "saleId", venta.getId().toString(), "accountId", banco.getId().toString()))
                .containsExactly(abono2);

        // El otro tenant no ve nada de esto, ni filtrando por los ids ajenos.
        assertThat(ids(tokenDos, "/api/treasury/movements", "saleId", venta.getId().toString())).isEmpty();
        assertThat(ids(tokenDos, "/api/treasury/movements", "purchaseId", compra.getId().toString())).isEmpty();
        assertThat(ids(tokenDos, "/api/treasury/movements")).isEmpty();
    }

    /**
     * Mientras la factura esta ANULADA sus movimientos siguen en la base pero no salen en el
     * libro, ni filtrando por la factura, ni en el extracto de la cuenta. Al restaurarla,
     * vuelven a salir con los mismos ids.
     */
    @Test
    void losMovimientosDeUnaFacturaAnuladaNoSeExponenYVuelvenAlRestaurar() throws Exception {
        Sale venta = venderACredito("VEN-00004", 1);
        Purchase compra = comprarACredito("FAC-00003");
        Integer abono = abonar(venta, caja, 19000);
        Integer pago = pagar(compra, banco, 19000);
        assertThat(ids(tokenUno, "/api/treasury/movements")).contains(abono, pago);

        accion("/api/sales/" + venta.getId() + "/annul", 200);
        accion("/api/purchases/" + compra.getId() + "/annul", 200);

        assertThat(ids(tokenUno, "/api/treasury/movements")).doesNotContain(abono, pago);
        assertThat(ids(tokenUno, "/api/treasury/movements", "saleId", venta.getId().toString())).isEmpty();
        assertThat(ids(tokenUno, "/api/treasury/movements", "purchaseId", compra.getId().toString())).isEmpty();
        assertThat(ids(tokenUno, "/api/treasury/accounts/" + caja.getId() + "/movements")).isEmpty();
        assertThat(ids(tokenUno, "/api/treasury/accounts/" + banco.getId() + "/movements")).doesNotContain(pago);
        fixture.usarTenant(tenantUno);
        assertThat(treasuryService.movimientosDeVenta(venta)).extracting(m -> m.getId().intValue())
                .containsExactly(abono);
        assertThat(treasuryService.movimientosDeCompra(compra)).extracting(m -> m.getId().intValue())
                .containsExactly(pago);
        fixture.verificarInvariante();
        TenantContext.clear();

        accion("/api/sales/" + venta.getId() + "/restore", 200);
        accion("/api/purchases/" + compra.getId() + "/restore", 200);

        assertThat(ids(tokenUno, "/api/treasury/movements", "saleId", venta.getId().toString()))
                .containsExactly(abono);
        assertThat(ids(tokenUno, "/api/treasury/movements", "purchaseId", compra.getId().toString()))
                .containsExactly(pago);
        assertThat(ids(tokenUno, "/api/treasury/accounts/" + caja.getId() + "/movements")).containsExactly(abono);
        fixture.usarTenant(tenantUno);
        fixture.verificarInvariante();
    }

    /** Restaurar sin saldo o sin stock responde 409 y la factura sigue ANULADA. */
    @Test
    void restaurarSinSaldoOSinStockResponde409YNoCambiaNada() throws Exception {
        Purchase compra = comprarACredito("FAC-00004");
        pagar(compra, banco, 119000);
        accion("/api/purchases/" + compra.getId() + "/annul", 200);
        fixture.usarTenant(tenantUno);
        treasuryService.registrarGastoOperativo(banco, "Retiro", 950000.0, null, null);
        TenantContext.clear();

        mockMvc.perform(post("/api/purchases/" + compra.getId() + "/restore")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/purchases/" + compra.getId()).header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.status").value("ANULADA"));

        Sale venta = venderACredito("VEN-00005", 6);
        accion("/api/sales/" + venta.getId() + "/annul", 200);
        venderACredito("VEN-00006", 8);

        mockMvc.perform(post("/api/sales/" + venta.getId() + "/restore")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/sales/" + venta.getId()).header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.status").value("ANULADA"));

        fixture.usarTenant(tenantUno);
        fixture.verificarInvariante();
    }
}
