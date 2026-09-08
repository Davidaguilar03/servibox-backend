package com.servibox.backend.treasury;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento del modulo treasury de punta a punta: dos tenants reales, cada uno con su
 * JWT, hablando por HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TreasuryTenantIsolationTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String tokenUno;
    private String tokenDos;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        crearTenantConUsuario(slugUno, "admin-uno");
        crearTenantConUsuario(slugDos, "admin-dos");
        tokenUno = login(slugUno, "admin-uno");
        tokenDos = login(slugDos, "admin-dos");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private void crearTenantConUsuario(String slug, String username) {
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

    private Long crearCuenta(String token, String nombre, String tipo) throws Exception {
        String body = """
                {"name":"%s","initialBalance":0,"type":"%s"}
                """.formatted(nombre, tipo);
        MvcResult result = mockMvc.perform(post("/api/treasury/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private List<String> cuentasVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/treasury/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].name");
    }

    @Test
    void unaCuentaDeUnTenantNoEsVisibleParaOtro() throws Exception {
        crearCuenta(tokenUno, "Caja General", "CASH");
        crearCuenta(tokenDos, "Bancolombia", "BANK");

        assertThat(cuentasVisiblesPara(tokenUno)).containsExactly("Caja General");
        assertThat(cuentasVisiblesPara(tokenDos)).containsExactly("Bancolombia");
    }

    @Test
    void elMismoNombreDeCuentaEsValidoEnTenantsDistintosPeroNoDentroDelMismo() throws Exception {
        crearCuenta(tokenUno, "Caja General", "CASH");

        // Mismo nombre, otro tenant: pasa.
        crearCuenta(tokenDos, "Caja General", "CASH");

        // Mismo nombre, mismo tenant: 409 con mensaje legible, no error crudo de la base.
        mockMvc.perform(post("/api/treasury/accounts")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Caja General","initialBalance":0,"type":"CASH"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una cuenta con el nombre Caja General"));
    }

    @Test
    void losMovimientosDeUnaCuentaAjenaNoSeVenNiPorId() throws Exception {
        Long cuentaDeUno = crearCuenta(tokenUno, "Caja General", "CASH");

        // El filtro de Hibernate no encuentra la cuenta para el otro tenant.
        mockMvc.perform(get("/api/treasury/accounts/" + cuentaDeUno + "/movements")
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elBalanceGlobalSoloCuentaElDineroDelTenantQuePregunta() throws Exception {
        Long cuentaDeUno = crearCuenta(tokenUno, "Caja General", "CASH");
        crearCuenta(tokenDos, "Caja General", "CASH");

        mockMvc.perform(post("/api/treasury/movements")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"type":"INGRESO","concept":"Venta","amount":50000}
                                """.formatted(cuentaDeUno)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(50000.0));

        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0.0));
    }

    @Test
    void laTransferenciaInvalidaResponde400ConMensajeLegible() throws Exception {
        Long cuenta = crearCuenta(tokenUno, "Caja General", "CASH");

        mockMvc.perform(post("/api/treasury/transfers")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originAccountId":%d,"destinationAccountId":%d,"concept":"Sin sentido","amount":10000}
                                """.formatted(cuenta, cuenta)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("La cuenta origen y la cuenta destino no pueden ser la misma"));
    }

    @Test
    void laTesoreriaExigeAutenticacion() throws Exception {
        mockMvc.perform(get("/api/treasury/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/treasury/balance")).andExpect(status().isUnauthorized());
    }
}
