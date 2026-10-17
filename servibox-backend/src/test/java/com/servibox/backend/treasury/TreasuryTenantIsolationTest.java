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
        crearCuenta(tokenUno, "Caja General", "EFECTIVO");
        crearCuenta(tokenDos, "Bancolombia", "BANCO");

        assertThat(cuentasVisiblesPara(tokenUno)).containsExactly("Caja General");
        assertThat(cuentasVisiblesPara(tokenDos)).containsExactly("Bancolombia");
    }

    @Test
    void elMismoNombreDeCuentaEsValidoEnTenantsDistintosPeroNoDentroDelMismo() throws Exception {
        crearCuenta(tokenUno, "Caja General", "EFECTIVO");

        // Mismo nombre, otro tenant: pasa.
        crearCuenta(tokenDos, "Caja General", "EFECTIVO");

        // Mismo nombre, mismo tenant: 409 con mensaje legible, no error crudo de la base.
        mockMvc.perform(post("/api/treasury/accounts")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Caja General","initialBalance":0,"type":"EFECTIVO"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una cuenta con el nombre Caja General"));
    }

    @Test
    void losMovimientosDeUnaCuentaAjenaNoSeVenNiPorId() throws Exception {
        Long cuentaDeUno = crearCuenta(tokenUno, "Caja General", "EFECTIVO");

        // Para el otro tenant esa cuenta no existe: 404, nunca los movimientos ajenos.
        mockMvc.perform(get("/api/treasury/accounts/" + cuentaDeUno + "/movements")
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());
    }

    @Test
    void elBalanceGlobalSoloCuentaElDineroDelTenantQuePregunta() throws Exception {
        Long cuentaDeUno = crearCuenta(tokenUno, "Caja General", "EFECTIVO");
        crearCuenta(tokenDos, "Caja General", "EFECTIVO");

        mockMvc.perform(post("/api/treasury/movements")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"INGRESO_OCASIONAL","concept":"Venta","amount":50000}
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
        Long cuenta = crearCuenta(tokenUno, "Caja General", "EFECTIVO");

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

    /**
     * El endpoint de movimientos tiene que mostrar tambien las dos patas de una
     * transferencia, de categoria TRANSFERENCIA, con el INGRESO apuntando al EGRESO.
     */
    @Test
    void elListadoDeMovimientosMuestraLosGeneradosPorUnaTransferencia() throws Exception {
        Long cajaGeneral = crearCuenta(tokenUno, "Caja General", "EFECTIVO");
        Long bancolombia = crearCuenta(tokenUno, "Bancolombia", "BANCO");

        MvcResult creada = mockMvc.perform(post("/api/treasury/transfers")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originAccountId":%d,"destinationAccountId":%d,"concept":"Consignacion del dia","amount":10000}
                                """.formatted(cajaGeneral, bancolombia)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.concept").value("Consignacion del dia"))
                .andExpect(jsonPath("$.amount").value(10000.0))
                .andReturn();
        Integer egresoId = JsonPath.read(creada.getResponse().getContentAsString(), "$.outgoingMovementId");
        Integer ingresoId = JsonPath.read(creada.getResponse().getContentAsString(), "$.incomingMovementId");

        mockMvc.perform(get("/api/treasury/accounts/" + cajaGeneral + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("EGRESO"))
                .andExpect(jsonPath("$[0].amount").value(10000.0))
                .andExpect(jsonPath("$[0].id").value(egresoId))
                .andExpect(jsonPath("$[0].concept").value("Consignacion del dia"))
                .andExpect(jsonPath("$[0].category").value("TRANSFERENCIA"))
                .andExpect(jsonPath("$[0].notes").doesNotExist())
                .andExpect(jsonPath("$[0].relatedMovementId").doesNotExist());

        mockMvc.perform(get("/api/treasury/accounts/" + bancolombia + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("INGRESO"))
                .andExpect(jsonPath("$[0].amount").value(10000.0))
                .andExpect(jsonPath("$[0].id").value(ingresoId))
                .andExpect(jsonPath("$[0].concept").value("Consignacion del dia"))
                .andExpect(jsonPath("$[0].category").value("TRANSFERENCIA"))
                .andExpect(jsonPath("$[0].relatedMovementId").value(egresoId));
    }

    /** Sin concepto del usuario, cada pata lleva el autogenerado y la respuesta el del EGRESO. */
    @Test
    void unaTransferenciaSinConceptoUsaElAutogenerado() throws Exception {
        Long caja = crearCuenta(tokenUno, "Caja General", "EFECTIVO");
        Long banco = crearCuenta(tokenUno, "Bancolombia", "BANCO");

        mockMvc.perform(post("/api/treasury/transfers")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originAccountId":%d,"destinationAccountId":%d,"amount":10000}
                                """.formatted(caja, banco)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.concept").value("Transferencia a Bancolombia"));

        mockMvc.perform(get("/api/treasury/accounts/" + banco + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$[0].concept").value("Transferencia desde Caja General"))
                .andExpect(jsonPath("$[0].notes").doesNotExist());
    }

    /** Los tres tipos de cuenta del diccionario se guardan y se leen por la API. */
    @Test
    void losTiposDeCuentaDelDiccionarioSeGuardanYSeLeen() throws Exception {
        crearCuenta(tokenUno, "Caja General", "EFECTIVO");
        crearCuenta(tokenUno, "Bancolombia", "BANCO");
        crearCuenta(tokenUno, "Nequi", "DIGITAL");

        MvcResult result = mockMvc.perform(get("/api/treasury/accounts")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andReturn();
        List<String> tipos = JsonPath.read(result.getResponse().getContentAsString(), "$[*].type");
        assertThat(tipos).containsExactlyInAnyOrder("EFECTIVO", "BANCO", "DIGITAL");
    }

    private void registrar(String token, Long cuenta, String categoria, String concepto, int monto, String fecha)
            throws Exception {
        mockMvc.perform(post("/api/treasury/movements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"%s","concept":"%s","amount":%d,"date":"%s"}
                                """.formatted(cuenta, categoria, concepto, monto, fecha)))
                .andExpect(status().isCreated());
    }

    private List<String> conceptos(String token, String... filtros) throws Exception {
        var peticion = get("/api/treasury/movements").header("Authorization", "Bearer " + token);
        for (int i = 0; i < filtros.length; i += 2) {
            peticion.param(filtros[i], filtros[i + 1]);
        }
        MvcResult result = mockMvc.perform(peticion).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].concept");
    }

    @Test
    void elLibroDeMovimientosFiltraPorCuentaCategoriaYRangoSinMostrarOtroTenant() throws Exception {
        Long caja = crearCuenta(tokenUno, "Caja General", "EFECTIVO");
        Long banco = crearCuenta(tokenUno, "Bancolombia", "BANCO");
        Long cajaAjena = crearCuenta(tokenDos, "Caja General", "EFECTIVO");

        registrar(tokenUno, caja, "INGRESO_OCASIONAL", "Chatarra agosto", 30000, "2026-08-20");
        registrar(tokenUno, caja, "GASTO_OPERATIVO", "Arriendo septiembre", 10000, "2026-09-05");
        registrar(tokenUno, banco, "INGRESO_OCASIONAL", "Reintegro septiembre", 5000, "2026-09-10");
        registrar(tokenDos, cajaAjena, "INGRESO_OCASIONAL", "Ajeno", 99000, "2026-09-10");

        // Sin filtros: todo lo del tenant, del mas reciente al mas antiguo, nada del otro.
        assertThat(conceptos(tokenUno))
                .containsExactly("Reintegro septiembre", "Arriendo septiembre", "Chatarra agosto");
        assertThat(conceptos(tokenUno, "accountId", caja.toString()))
                .containsExactly("Arriendo septiembre", "Chatarra agosto");
        assertThat(conceptos(tokenUno, "category", "INGRESO_OCASIONAL"))
                .containsExactly("Reintegro septiembre", "Chatarra agosto");
        assertThat(conceptos(tokenUno, "desde", "2026-09-01", "hasta", "2026-09-30"))
                .containsExactly("Reintegro septiembre", "Arriendo septiembre");
        assertThat(conceptos(tokenUno, "accountId", caja.toString(), "category", "GASTO_OPERATIVO",
                "desde", "2026-09-01", "hasta", "2026-09-30"))
                .containsExactly("Arriendo septiembre");

        // Filtrar por la cuenta del otro tenant no la destapa.
        assertThat(conceptos(tokenUno, "accountId", cajaAjena.toString())).isEmpty();
        assertThat(conceptos(tokenDos)).containsExactly("Ajeno");
    }

    @Test
    void crearUnMovimientoManualConUnaCategoriaQueNoEsManualResponde400() throws Exception {
        Long caja = crearCuenta(tokenUno, "Caja General", "EFECTIVO");

        for (String categoria : List.of("PAGO_COMPRA", "RECAUDO_VENTA", "TRANSFERENCIA")) {
            mockMvc.perform(post("/api/treasury/movements")
                            .header("Authorization", "Bearer " + tokenUno)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountId":%d,"category":"%s","concept":"Trampa","amount":1000}
                                    """.formatted(caja, categoria)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Un movimiento manual es GASTO_OPERATIVO o "
                            + "INGRESO_OCASIONAL; " + categoria + " se gestiona desde su operacion"));
        }

        assertThat(conceptos(tokenUno)).isEmpty();
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.total").value(0.0));
    }

    @Test
    void laTesoreriaExigeAutenticacion() throws Exception {
        mockMvc.perform(get("/api/treasury/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/treasury/balance")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/treasury/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
