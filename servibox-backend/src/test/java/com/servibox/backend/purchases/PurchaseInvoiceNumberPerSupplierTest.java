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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El numero de factura de compra es unico por proveedor dentro del tenant (V3), sin
 * distinguir mayusculas y con las anuladas incluidas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseInvoiceNumberPerSupplierTest {

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

    /** Lo que deja un tenant de prueba: su token, un producto y dos proveedores. */
    private record Taller(String token, Product llanta, Counterparty norte, Counterparty valle) {
    }

    private Taller taller;

    @BeforeEach
    void seed() throws Exception {
        taller = crearTaller("taller-compras-proveedor-");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Taller crearTaller(String prefijo) throws Exception {
        String slug = prefijo + System.nanoTime();
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
        Product llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        Counterparty norte = fixture.crearProveedor("Llantas del Norte", "800333444");
        Counterparty valle = fixture.crearProveedor("Lubricantes del Valle", "800555666");
        TenantContext.clear();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return new Taller(JsonPath.read(result.getResponse().getContentAsString(), "$.token"), llanta, norte, valle);
    }

    private ResultActions comprar(Taller t, String numero, Counterparty proveedor) throws Exception {
        return mockMvc.perform(post("/api/purchases")
                .header("Authorization", "Bearer " + t.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"invoiceNumber":"%s","supplierId":%d,"dueDate":"2099-12-31","paymentType":"CREDITO",
                         "details":[{"productId":%d,"quantity":1,"price":100000}]}
                        """.formatted(numero, proveedor.getId(), t.llanta().getId())));
    }

    private int idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions compras(Taller t) throws Exception {
        return mockMvc.perform(get("/api/purchases").header("Authorization", "Bearer " + t.token()));
    }

    @Test
    void dosProveedoresPuedenUsarElMismoNumero() throws Exception {
        comprar(taller, "FAC-100", taller.norte())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.name").value("Llantas del Norte"));
        comprar(taller, "FAC-100", taller.valle())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.name").value("Lubricantes del Valle"));

        compras(taller).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void elMismoProveedorNoPuedeRepetirElNumeroNiCambiandoMayusculas() throws Exception {
        comprar(taller, "FAC-100", taller.norte()).andExpect(status().isCreated());

        comprar(taller, "FAC-100", taller.norte())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Ya existe una factura de compra FAC-100 del proveedor Llantas del Norte"));
        comprar(taller, "fac-100", taller.norte())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una factura de compra fac-100 del proveedor Llantas del Norte"));

        compras(taller).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void elMismoNumeroYProveedorEnOtroTenantEsValido() throws Exception {
        Taller otro = crearTaller("taller-compras-proveedor-otro-");
        comprar(taller, "FAC-100", taller.norte()).andExpect(status().isCreated());

        // Mismo NIT y mismo numero, pero es otro tenant: otro tercero y otra compra.
        comprar(otro, "FAC-100", otro.norte()).andExpect(status().isCreated());

        compras(taller).andExpect(jsonPath("$.length()").value(1));
        compras(otro).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unaCompraAnuladaReservaSuNumeroSoloParaSuProveedorYSePuedeRecuperar() throws Exception {
        int anulada = idDe(comprar(taller, "FAC-200", taller.norte()).andExpect(status().isCreated()));
        mockMvc.perform(post("/api/purchases/" + anulada + "/annul").header("Authorization", "Bearer " + taller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANULADA"));

        comprar(taller, "FAC-200", taller.norte())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una factura de compra FAC-200 del proveedor Llantas del Norte"));
        comprar(taller, "FAC-200", taller.valle()).andExpect(status().isCreated());

        mockMvc.perform(post("/api/purchases/" + anulada + "/restore").header("Authorization", "Bearer " + taller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE"))
                .andExpect(jsonPath("$.invoiceNumber").value("FAC-200"));
    }

    @Test
    void dosComprasSimultaneasDelMismoProveedorYNumeroDanUn201YUn409() throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> resultados = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<MvcResult> compra = () -> {
                    salida.await();
                    return comprar(taller, "FAC-300", taller.norte()).andReturn();
                };
                resultados.add(pool.submit(compra));
            }
            salida.countDown();

            List<Integer> codigos = new ArrayList<>();
            for (Future<MvcResult> resultado : resultados) {
                MvcResult r = resultado.get();
                codigos.add(r.getResponse().getStatus());
                if (r.getResponse().getStatus() == 409) {
                    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.error"))
                            .isEqualTo("Ya existe una factura de compra FAC-300 del proveedor Llantas del Norte");
                }
            }
            assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }

        compras(taller).andExpect(jsonPath("$.length()").value(1));
    }
}
