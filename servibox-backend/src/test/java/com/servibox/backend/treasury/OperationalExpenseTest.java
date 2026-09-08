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
import com.servibox.backend.treasury.entity.MovementSourceType;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.entity.OperationalExpense;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gastos operativos: registro, efecto en el saldo, edicion (revertir y recrear) y
 * eliminacion revirtiendo. Espejo de OccasionalIncomeTest con el signo invertido, mas la
 * edicion, que el ingreso ocasional no tiene.
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
        cuenta.setType(AccountType.CASH);
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
    void registrarUnGastoOperativoRestaDelSaldoYDejaSuMovimiento() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        assertThat(saldoDe(caja)).isEqualTo(100000.0);

        OperationalExpense gasto = treasuryService.registrarGastoOperativo(
                caja, "Arriendo del local", 40000.0, LocalDate.now(), "Mes de septiembre");

        // 100000 - 40000 = 60000.
        assertThat(saldoDe(caja)).isEqualTo(60000.0);

        assertThat(gasto.getId()).isNotNull();
        assertThat(gasto.getConcept()).isEqualTo("Arriendo del local");
        assertThat(gasto.getAmount()).isEqualTo(40000.0);
        assertThat(gasto.getNotes()).isEqualTo("Mes de septiembre");
        assertThat(gasto.getAccount().getId()).isEqualTo(caja.getId());

        List<Movement> movimientos = movimientosDe(caja);
        assertThat(movimientos).hasSize(1);
        Movement movimiento = movimientos.get(0);
        assertThat(movimiento.getType()).isEqualTo(MovementType.EGRESO);
        assertThat(movimiento.getAmount()).isEqualTo(40000.0);
        assertThat(movimiento.getConcept()).isEqualTo("Arriendo del local");
        assertThat(movimiento.getSourceType()).isEqualTo(MovementSourceType.OPERATIONAL_EXPENSE);
        assertThat(movimiento.getSourceId()).isEqualTo(gasto.getId());

        assertThat(treasuryService.balanceGlobal()).isEqualTo(60000.0);
    }

    /** Las observaciones no son obligatorias, igual que en el formulario de Autollantas. */
    @Test
    void unGastoOperativoSinObservacionesEsValido() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);

        OperationalExpense gasto = treasuryService.registrarGastoOperativo(
                caja, "Papeleria", 5000.0, null, null);

        assertThat(gasto.getNotes()).isNull();
        assertThat(gasto.getDate()).isEqualTo(LocalDate.now());
        assertThat(saldoDe(caja)).isEqualTo(95000.0);
    }

    /**
     * El caso que da sentido al mecanismo: editar revierte el movimiento viejo y crea uno
     * nuevo, no acumula los dos. Con 100000 de saldo, un gasto de 40000 editado a 55000
     * tiene que dejar 45000, no 5000 (que seria 100000 - 40000 - 55000).
     */
    @Test
    void editarUnGastoOperativoRevierteElMovimientoAnteriorYAplicaElNuevo() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);

        OperationalExpense gasto = treasuryService.registrarGastoOperativo(
                caja, "Arriendo del local", 40000.0, LocalDate.now(), null);
        assertThat(saldoDe(caja)).isEqualTo(60000.0);
        assertThat(movimientosDe(caja)).hasSize(1);

        treasuryService.editarGastoOperativo(gasto.getId(), caja, "Arriendo del local",
                55000.0, LocalDate.now(), "Se reajusto el canon");

        // Solo cuenta el gasto editado.
        assertThat(saldoDe(caja)).isEqualTo(45000.0);

        // Y queda UN solo movimiento, el nuevo: el viejo se borro, no quedo un contra-movimiento.
        List<Movement> movimientos = movimientosDe(caja);
        assertThat(movimientos).hasSize(1);
        assertThat(movimientos.get(0).getType()).isEqualTo(MovementType.EGRESO);
        assertThat(movimientos.get(0).getAmount()).isEqualTo(55000.0);
        assertThat(movimientos.get(0).getSourceType()).isEqualTo(MovementSourceType.OPERATIONAL_EXPENSE);
        assertThat(movimientos.get(0).getSourceId()).isEqualTo(gasto.getId());

        OperationalExpense recargado = treasuryService.findOperationalExpenseById(gasto.getId()).orElseThrow();
        assertThat(recargado.getAmount()).isEqualTo(55000.0);
        assertThat(recargado.getNotes()).isEqualTo("Se reajusto el canon");
        // Sigue siendo la misma fila, no una nueva.
        assertThat(treasuryService.findAllOperationalExpenses()).hasSize(1);
    }

    /**
     * La reversion mira la cuenta del movimiento viejo, no la del gasto ya editado: el
     * dinero tiene que volver a la cuenta de la que salio.
     */
    @Test
    void editarUnGastoCambiandoDeCuentaDevuelveElDineroALaCuentaOriginal() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        Account banco = crearCuentaEn(tenantUno, "Banco", 200000.0);

        OperationalExpense gasto = treasuryService.registrarGastoOperativo(
                caja, "Servicios publicos", 40000.0, null, null);
        assertThat(saldoDe(caja)).isEqualTo(60000.0);

        treasuryService.editarGastoOperativo(gasto.getId(), banco, "Servicios publicos",
                40000.0, null, null);

        assertThat(saldoDe(caja)).isEqualTo(100000.0);
        assertThat(saldoDe(banco)).isEqualTo(160000.0);
        assertThat(movimientosDe(caja)).isEmpty();
        assertThat(movimientosDe(banco)).hasSize(1);
    }

    @Test
    void eliminarUnGastoOperativoBorraElMovimientoYDevuelveElSaldo() {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        treasuryService.registrarGastoOperativo(caja, "Papeleria", 10000.0, null, null);
        double saldoPrevio = saldoDe(caja);

        OperationalExpense gasto = treasuryService.registrarGastoOperativo(
                caja, "Arriendo del local", 40000.0, null, null);
        assertThat(saldoDe(caja)).isEqualTo(50000.0);
        assertThat(movimientosDe(caja)).hasSize(2);

        treasuryService.anularGastoOperativo(gasto.getId());

        assertThat(saldoDe(caja)).isEqualTo(saldoPrevio);
        assertThat(movimientosDe(caja)).hasSize(1);
        // El registro desaparece, no queda en estado ANULADA.
        assertThat(treasuryService.findOperationalExpenseById(gasto.getId())).isEmpty();
        assertThat(treasuryService.findAllOperationalExpenses())
                .extracting(OperationalExpense::getConcept)
                .containsExactly("Papeleria");
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
        OperationalExpense ajeno = treasuryService.registrarGastoOperativo(
                cajaUno, "Arriendo tenant uno", 40000.0, null, null);
        Account cajaDos = crearCuentaEn(tenantDos, "Caja General", 100000.0);
        TenantContext.clear();

        mockMvc.perform(put("/api/treasury/operational-expenses/" + ajeno.getId())
                        .header("Authorization", "Bearer " + tokenDos)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"concept":"Secuestrado","amount":1}
                                """.formatted(cajaDos.getId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/treasury/operational-expenses/" + ajeno.getId())
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound());

        // Intacto para su dueno.
        TenantContext.setTenantId(tenantUno);
        assertThat(treasuryService.findOperationalExpenseById(ajeno.getId())).isPresent();
        assertThat(saldoDe(cajaUno)).isEqualTo(60000.0);
    }

    private List<String> conceptosVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/treasury/operational-expenses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].concept");
    }

    @Test
    void losEndpointsCubrenElCicloCompletoDelGasto() throws Exception {
        Account caja = crearCuentaEn(tenantUno, "Caja General", 100000.0);
        TenantContext.clear();

        MvcResult creado = mockMvc.perform(post("/api/treasury/operational-expenses")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"concept":"Arriendo del local","amount":40000,"notes":"Mes de septiembre"}
                                """.formatted(caja.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.concept").value("Arriendo del local"))
                .andExpect(jsonPath("$.amount").value(40000.0))
                .andExpect(jsonPath("$.notes").value("Mes de septiembre"))
                .andExpect(jsonPath("$.accountName").value("Caja General"))
                .andReturn();
        Integer gastoId = JsonPath.read(creado.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/treasury/accounts/" + caja.getId() + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("EGRESO"))
                .andExpect(jsonPath("$[0].amount").value(40000.0))
                .andExpect(jsonPath("$[0].sourceType").value("OPERATIONAL_EXPENSE"))
                .andExpect(jsonPath("$[0].sourceId").value(gastoId));

        mockMvc.perform(put("/api/treasury/operational-expenses/" + gastoId)
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"concept":"Arriendo del local","amount":55000}
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

        mockMvc.perform(delete("/api/treasury/operational-expenses/" + gastoId)
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/treasury/operational-expenses")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.total").value(100000.0));
    }

    @Test
    void losGastosOperativosExigenAutenticacion() throws Exception {
        mockMvc.perform(get("/api/treasury/operational-expenses")).andExpect(status().isUnauthorized());
    }
}
