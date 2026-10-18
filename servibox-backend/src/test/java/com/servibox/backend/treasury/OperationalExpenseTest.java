package com.servibox.backend.treasury;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.service.TreasuryService;
import com.servibox.backend.treasury.entity.MovementCategory;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gastos operativos (movimientos GASTO_OPERATIVO): registro, efecto en el saldo, edicion
 * en la misma fila (revertir el efecto viejo y aplicar el nuevo) y eliminacion. Espejo de
 * OccasionalIncomeTest con el signo invertido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationalExpenseTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TreasuryService treasuryService;

    /** El "hoy" de negocio, el mismo que usan los servicios para la fecha por defecto. */
    @Autowired
    private Clock clock;

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

    /** Cuenta con saldo de apertura, para que el gasto tenga de donde salir. */
    private Account crearCuentaEn(Long tenantId, String nombre, double saldoInicial) {
        TenantContext.setTenantId(tenantId);
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(AccountType.EFECTIVO);
        cuenta.setInitialBalance(saldoInicial);
        return treasuryService.saveAccount(cuenta);
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    private List<Movement> movimientosDe(Account cuenta) {
        return treasuryService.findMovementsByAccountId(cuenta.getId());
    }

    @Test
    void registrarUnGastoOperativoRestaDelSaldoYEsUnMovimientoDeSuCategoria() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        assertThat(saldoDe(caja)).isEqualTo(100000.0);

        Movement gasto = treasuryService.registrarGastoOperativo(
                caja, "Arriendo del local", 40000.0, LocalDate.now(), "Mes de septiembre");

        // 100000 - 40000 = 60000.
        assertThat(saldoDe(caja)).isEqualTo(60000.0);

        assertThat(movimientosDe(caja)).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo(gasto.getId());
            assertThat(m.getType()).isEqualTo(MovementType.EGRESO);
            assertThat(m.getCategory()).isEqualTo(MovementCategory.GASTO_OPERATIVO);
            assertThat(m.getAmount()).isEqualTo(40000.0);
            assertThat(m.getConcept()).isEqualTo("Arriendo del local");
            assertThat(m.getNotes()).isEqualTo("Mes de septiembre");
            assertThat(m.getAccount().getId()).isEqualTo(caja.getId());
            assertThat(m.getSale()).isNull();
            assertThat(m.getPurchase()).isNull();
        });

        assertThat(treasuryService.balanceGlobal()).isEqualTo(60000.0);
    }

    /** Las observaciones no son obligatorias, igual que en el formulario de Autollantas. */
    @Test
    void unGastoOperativoSinObservacionesNiFechaEsValido() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);

        Movement gasto = treasuryService.registrarGastoOperativo(caja, "Papeleria", 5000.0, null, null);

        assertThat(gasto.getNotes()).isNull();
        assertThat(gasto.getDate()).isEqualTo(LocalDate.now(clock));
        assertThat(saldoDe(caja)).isEqualTo(95000.0);
    }

    /**
     * Editar revierte el efecto anterior y aplica el nuevo, no acumula los dos. Con 100000
     * de saldo, un gasto de 40000 editado a 55000 tiene que dejar 45000, no 5000.
     */
    @Test
    void editarUnGastoOperativoRevierteElEfectoAnteriorYAplicaElNuevoEnLaMismaFila() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);

        Movement gasto = treasuryService.registrarGastoOperativo(
                caja, "Arriendo del local", 40000.0, LocalDate.now(), null);
        assertThat(saldoDe(caja)).isEqualTo(60000.0);

        LocalDate otraFecha = LocalDate.now().minusDays(3);
        treasuryService.editarMovimientoManual(gasto.getId(), caja, "Arriendo reajustado",
                55000.0, otraFecha, "Se reajusto el canon");

        assertThat(saldoDe(caja)).isEqualTo(45000.0);

        // Sigue siendo la misma fila, con los datos nuevos: no hay contra-movimiento.
        assertThat(movimientosDe(caja)).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo(gasto.getId());
            assertThat(m.getType()).isEqualTo(MovementType.EGRESO);
            assertThat(m.getCategory()).isEqualTo(MovementCategory.GASTO_OPERATIVO);
            assertThat(m.getAmount()).isEqualTo(55000.0);
            assertThat(m.getConcept()).isEqualTo("Arriendo reajustado");
            assertThat(m.getDate()).isEqualTo(otraFecha);
            assertThat(m.getNotes()).isEqualTo("Se reajusto el canon");
        });
    }

    /** La reversion mira la cuenta anterior: el dinero vuelve a la cuenta de la que salio. */
    @Test
    void editarUnGastoCambiandoDeCuentaDevuelveElDineroALaCuentaOriginal() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        Account banco = crearCuentaEn(tenantUno, "Banco", 200000.0);

        Movement gasto = treasuryService.registrarGastoOperativo(
                caja, "Servicios publicos", 40000.0, null, null);
        assertThat(saldoDe(caja)).isEqualTo(60000.0);

        treasuryService.editarMovimientoManual(gasto.getId(), banco, "Servicios publicos", 40000.0, null, null);

        assertThat(saldoDe(caja)).isEqualTo(100000.0);
        assertThat(saldoDe(banco)).isEqualTo(160000.0);
        assertThat(movimientosDe(caja)).isEmpty();
        assertThat(movimientosDe(banco)).singleElement()
                .satisfies(m -> assertThat(m.getId()).isEqualTo(gasto.getId()));
    }

    @Test
    void eliminarUnGastoOperativoBorraLaFilaYDevuelveElSaldo() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        treasuryService.registrarGastoOperativo(caja, "Papeleria", 10000.0, null, null);
        double saldoPrevio = saldoDe(caja);

        Movement gasto = treasuryService.registrarGastoOperativo(caja, "Arriendo del local", 40000.0, null, null);
        assertThat(saldoDe(caja)).isEqualTo(50000.0);

        treasuryService.eliminarMovimientoManual(gasto.getId());

        assertThat(saldoDe(caja)).isEqualTo(saldoPrevio);
        // El registro desaparece, no queda en estado ANULADA.
        assertThat(treasuryService.findMovementById(gasto.getId())).isEmpty();
        assertThat(movimientosDe(caja)).extracting(Movement::getConcept).containsExactly("Papeleria");
    }

    @Test
    void unGastoOperativoDeUnTenantNoEsVisibleParaOtro() throws Exception {
        Account cajaUno = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        treasuryService.registrarGastoOperativo(cajaUno, "Arriendo tenant uno", 40000.0, null, null);

        Account cajaDos = crearCuentaEn(tenantDos, "Caja General", 100000.0);
        treasuryService.registrarGastoOperativo(cajaDos, "Servicios tenant dos", 15000.0, null, null);
        TenantContext.clear();

        assertThat(conceptosVisiblesPara(tokenUno)).containsExactly("Arriendo tenant uno");
        assertThat(conceptosVisiblesPara(tokenDos)).containsExactly("Servicios tenant dos");

        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.total").value(60000.0));
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenDos))
                .andExpect(jsonPath("$.total").value(85000.0));
    }

    /** El gasto de otro tenant no se puede editar ni eliminar: para el segundo no existe. */
    @Test
    void nadieEditaNiEliminaElGastoDeOtroTenant() throws Exception {
        Account cajaUno = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        Movement ajeno = treasuryService.registrarGastoOperativo(cajaUno, "Arriendo tenant uno", 40000.0, null, null);
        Account cajaDos = crearCuentaEn(tenantDos, "Caja General", 100000.0);
        TenantContext.clear();

        mockMvc.perform(put("/api/treasury/movements/" + ajeno.getId())
                        .header("Authorization", "Bearer " + tokenDos)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Secuestrado","amount":1}
                                """.formatted(cajaDos.getId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/treasury/movements/" + ajeno.getId())
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());

        // Intacto para su dueno.
        TenantContext.setTenantId(tenantUno);
        assertThat(treasuryService.findMovementById(ajeno.getId())).isPresent()
                .get().satisfies(m -> assertThat(m.getConcept()).isEqualTo("Arriendo tenant uno"));
        assertThat(saldoDe(cajaUno)).isEqualTo(60000.0);
    }

    private List<String> conceptosVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/treasury/movements")
                        .param("category", "GASTO_OPERATIVO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].concept");
    }

    @Test
    void losEndpointsCubrenElCicloCompletoDelGasto() throws Exception {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        TenantContext.clear();

        MvcResult creado = mockMvc.perform(post("/api/treasury/movements")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Arriendo del local","amount":40000,"notes":"Mes de septiembre"}
                                """.formatted(caja.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EGRESO"))
                .andExpect(jsonPath("$.category").value("GASTO_OPERATIVO"))
                .andExpect(jsonPath("$.concept").value("Arriendo del local"))
                .andExpect(jsonPath("$.amount").value(40000.0))
                .andExpect(jsonPath("$.notes").value("Mes de septiembre"))
                .andExpect(jsonPath("$.accountName").value("Caja General"))
                .andReturn();
        Integer gastoId = JsonPath.read(creado.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/treasury/movements/" + gastoId)
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Arriendo del local","amount":55000}
                                """.formatted(caja.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(55000.0))
                .andExpect(jsonPath("$.id").value(gastoId));

        // Un solo movimiento y el saldo refleja solo el gasto editado.
        mockMvc.perform(get("/api/treasury/accounts/" + caja.getId() + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(55000.0));
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.total").value(45000.0));

        mockMvc.perform(delete("/api/treasury/movements/" + gastoId)
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/treasury/movements")
                        .param("category", "GASTO_OPERATIVO")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.total").value(100000.0));
    }

    /** Un gasto no se convierte en ingreso editandolo: la categoria es fija. */
    @Test
    void laCategoriaDeUnMovimientoManualNoSePuedeCambiarAlEditar() throws Exception {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        Movement gasto = treasuryService.registrarGastoOperativo(caja, "Arriendo", 40000.0, null, null);
        TenantContext.clear();

        mockMvc.perform(put("/api/treasury/movements/" + gasto.getId())
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"INGRESO_OCASIONAL","concept":"Arriendo","amount":40000}
                                """.formatted(caja.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La categoria de un movimiento no se puede cambiar: el "
                        + gasto.getId() + " es GASTO_OPERATIVO"));

        TenantContext.setTenantId(tenantUno);
        assertThat(treasuryService.findMovementById(gasto.getId())).get()
                .satisfies(m -> {
                    assertThat(m.getCategory()).isEqualTo(MovementCategory.GASTO_OPERATIVO);
                    assertThat(m.getType()).isEqualTo(MovementType.EGRESO);
                });
        assertThat(saldoDe(caja)).isEqualTo(60000.0);
    }

    @Test
    void editarYEliminarMovimientosExigenAutenticacion() throws Exception {
        mockMvc.perform(put("/api/treasury/movements/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/treasury/movements/1")).andExpect(status().isUnauthorized());
    }
}
