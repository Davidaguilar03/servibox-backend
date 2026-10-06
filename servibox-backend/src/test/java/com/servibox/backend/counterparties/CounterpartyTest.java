package com.servibox.backend.counterparties;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.counterparties.service.DuplicateCounterpartyDocumentException;
import com.servibox.backend.counterparties.service.InvalidCounterpartyException;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Sale;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Terceros: un mismo documento es una sola fila por tenant, que sirve de cliente, de
 * proveedor o de los dos, y la venta o compra que lo referencia valida su rol.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CounterpartyTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private CounterpartyService counterpartyService;

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

    private Long tenantId;
    private String token;
    private Product llanta;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-terceros-" + System.nanoTime();
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
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        TenantContext.clear();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");

        fixture.usarTenant(tenantId);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Sale vender(String numero, Counterparty cliente) {
        return salesService.crearFactura(numero, cliente, LocalDate.now(), null,
                com.servibox.backend.sales.entity.PaymentType.CREDITO, null, null,
                List.of(new SalesService.LineaFactura(llanta.getId(), 1, 100000.0)));
    }

    private void comprar(String numero, Counterparty proveedor) {
        purchasesService.crearFactura(numero, proveedor, LocalDate.now(), null,
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100000.0)));
    }

    private Counterparty recargar(Counterparty tercero) {
        return counterpartyService.findById(tercero.getId()).orElseThrow();
    }

    @Test
    void unClienteUsadoDespuesComoProveedorPasaAAmbosSinDuplicarse() {
        Counterparty cliente = fixture.crearCliente("Andina", "900123456");
        assertThat(cliente.getRole()).isEqualTo(CounterpartyRole.CLIENTE);

        Counterparty proveedor = fixture.crearProveedor("Andina S.A.S.", "900123456");

        assertThat(proveedor.getId()).isEqualTo(cliente.getId());
        assertThat(recargar(cliente).getRole()).isEqualTo(CounterpartyRole.AMBOS);
        assertThat(counterpartyService.findAll()).hasSize(1);
        assertThatCode(() -> comprar("FAC-00001", proveedor)).doesNotThrowAnyException();
    }

    @Test
    void unProveedorUsadoDespuesComoClientePasaAAmbosSinDuplicarse() {
        Counterparty proveedor = fixture.crearProveedor("Andina", "900123456");

        Counterparty cliente = fixture.crearCliente("Andina", "900123456");

        assertThat(cliente.getId()).isEqualTo(proveedor.getId());
        assertThat(recargar(proveedor).getRole()).isEqualTo(CounterpartyRole.AMBOS);
        assertThat(counterpartyService.findAll()).hasSize(1);
        assertThatCode(() -> vender("VEN-00001", cliente)).doesNotThrowAnyException();
    }

    @Test
    void reutilizarConElMismoRolNoLoCambia() {
        Counterparty primero = fixture.crearCliente("Andina", "900123456");
        Counterparty segundo = fixture.crearCliente("Otro nombre", "900123456");

        assertThat(segundo.getId()).isEqualTo(primero.getId());
        assertThat(segundo.getRole()).isEqualTo(CounterpartyRole.CLIENTE);
        assertThat(segundo.getName()).isEqualTo("Andina");
    }

    @Test
    void unClienteReferenciadoPorIdEnUnaCompraSeRechazaCon400() throws Exception {
        Counterparty cliente = fixture.crearCliente("Andina", "900123456");

        assertThatThrownBy(() -> comprar("FAC-00001", cliente))
                .isInstanceOf(InvalidCounterpartyException.class)
                .hasMessage("El tercero 900123456 es CLIENTE y una compra exige PROVEEDOR o AMBOS");

        mockMvc.perform(post("/api/purchases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"FAC-00002","supplierId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(cliente.getId(), llanta.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("El tercero 900123456 es CLIENTE y una compra exige PROVEEDOR o AMBOS"));

        fixture.usarTenant(tenantId); // el filtro JWT limpia el contexto al terminar la peticion
        assertThat(purchasesService.findAllPurchases()).isEmpty();
        assertThat(recargar(cliente).getRole()).isEqualTo(CounterpartyRole.CLIENTE);
    }

    @Test
    void unProveedorReferenciadoPorIdEnUnaVentaSeRechazaCon400() throws Exception {
        Counterparty proveedor = fixture.crearProveedor("Andina", "900123456");

        assertThatThrownBy(() -> vender("VEN-00001", proveedor))
                .isInstanceOf(InvalidCounterpartyException.class)
                .hasMessage("El tercero 900123456 es PROVEEDOR y una venta exige CLIENTE o AMBOS");

        mockMvc.perform(post("/api/sales")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"VEN-00002","customerId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(proveedor.getId(), llanta.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("El tercero 900123456 es PROVEEDOR y una venta exige CLIENTE o AMBOS"));

        fixture.usarTenant(tenantId); // el filtro JWT limpia el contexto al terminar la peticion
        assertThat(salesService.findAllSales()).isEmpty();
    }

    @Test
    void unTerceroAmbosSirveEnVentasYEnCompras() throws Exception {
        fixture.crearCliente("Andina", "900123456");
        Counterparty ambos = fixture.crearProveedor("Andina", "900123456");
        assertThat(ambos.getRole()).isEqualTo(CounterpartyRole.AMBOS);

        mockMvc.perform(post("/api/sales")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"VEN-00001","customerId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(ambos.getId(), llanta.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.id").value(ambos.getId()))
                .andExpect(jsonPath("$.customer.role").value("AMBOS"))
                .andExpect(jsonPath("$.customer.documentType").value("CC"))
                .andExpect(jsonPath("$.customer.documentNumber").value("900123456"));

        mockMvc.perform(post("/api/purchases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"FAC-00001","supplierId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(ambos.getId(), llanta.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.id").value(ambos.getId()))
                .andExpect(jsonPath("$.supplier.name").value("Andina"));
    }

    @Test
    void losDatosDeContactoExistentesNoSePisanYLosVaciosSeCompletan() {
        Counterparty original = counterpartyService.obtenerOCrear(CounterpartyRole.CLIENTE, DocumentType.NIT,
                "900123456", "Andina", "ventas@andina.local", null);

        counterpartyService.obtenerOCrear(CounterpartyRole.PROVEEDOR, DocumentType.NIT,
                "900123456", "Otro nombre", "otro@correo.local", "3001112233");

        Counterparty actual = recargar(original);
        assertThat(actual.getEmail()).isEqualTo("ventas@andina.local");
        assertThat(actual.getPhone()).isEqualTo("3001112233");
        assertThat(actual.getName()).isEqualTo("Andina");
        assertThat(actual.getRole()).isEqualTo(CounterpartyRole.AMBOS);
    }

    @Test
    void elMismoDocumentoEnDosTenantsSonDosTercerosDistintos() {
        Counterparty deUno = fixture.crearCliente("Andina", "900123456");

        Long otroTenant = fixture.crearTenant();
        fixture.usarTenant(otroTenant);
        Counterparty deDos = fixture.crearProveedor("Andina", "900123456");

        assertThat(deDos.getId()).isNotEqualTo(deUno.getId());
        assertThat(deDos.getRole()).isEqualTo(CounterpartyRole.PROVEEDOR);
        assertThat(counterpartyService.findAll()).hasSize(1);
        // El de otro tenant no se encuentra por id.
        assertThat(counterpartyService.findById(deUno.getId())).isEmpty();

        fixture.usarTenant(tenantId);
        assertThat(recargar(deUno).getRole()).isEqualTo(CounterpartyRole.CLIENTE);
    }

    @Test
    void unTerceroDeOtroTenantNoSePuedeUsarPorId() throws Exception {
        fixture.usarTenant(fixture.crearTenant());
        Counterparty ajeno = fixture.crearCliente("Ajeno", "800000001");
        TenantContext.clear();

        mockMvc.perform(post("/api/sales")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"VEN-00001","customerId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(ajeno.getId(), llanta.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cliente no encontrado: " + ajeno.getId()));
    }

    @Test
    void crearUnDocumentoRepetidoSeRechazaConMensajeClaro() {
        fixture.crearCliente("Andina", "900123456");

        Counterparty repetido = new Counterparty();
        repetido.setRole(CounterpartyRole.PROVEEDOR);
        repetido.setName("Otro");
        repetido.setDocumentType(DocumentType.NIT);
        repetido.setDocumentNumber("900123456");

        assertThatThrownBy(() -> counterpartyService.crear(repetido))
                .isInstanceOf(DuplicateCounterpartyDocumentException.class)
                .hasMessage("Ya existe un tercero con el documento 900123456");
    }

    @Test
    void elTipoDeDocumentoEsObligatorio() {
        assertThatThrownBy(() -> counterpartyService.obtenerOCrear(CounterpartyRole.CLIENTE, null,
                "900123456", "Andina", null, null))
                .isInstanceOf(InvalidCounterpartyException.class)
                .hasMessage("El tercero necesita un tipo de documento");
        assertThat(counterpartyService.findAll()).isEmpty();
    }
}
