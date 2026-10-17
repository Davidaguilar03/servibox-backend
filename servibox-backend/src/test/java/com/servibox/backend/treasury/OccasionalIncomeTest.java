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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ingresos ocasionales (movimientos INGRESO_OCASIONAL): registro, efecto en el saldo y
 * eliminacion revirtiendo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OccasionalIncomeTest {

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

    private Account crearCuentaEn(Long tenantId, String nombre) {
        TenantContext.setTenantId(tenantId);
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(AccountType.CASH);
        cuenta.setInitialBalance(0.0);
        return treasuryService.saveAccount(cuenta);
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    @Test
    void registrarUnIngresoOcasionalSumaAlSaldoYEsUnMovimientoDeSuCategoria() {
        Account caja = crearCuentaEn(tenantUno, "Caja General");
        assertThat(saldoDe(caja)).isEqualTo(0.0);

        Movement ingreso = treasuryService.registrarIngresoOcasional(
                caja, "Venta de chatarra", 30000.0, LocalDate.now(), "Retal de rines");

        // El saldo quedo en 30000.
        assertThat(saldoDe(caja)).isEqualTo(30000.0);

        // No hay tabla de ingresos: el ingreso es el propio movimiento.
        List<Movement> movimientos = treasuryService.findMovementsByAccountId(caja.getId());
        assertThat(movimientos).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo(ingreso.getId());
            assertThat(m.getType()).isEqualTo(MovementType.INGRESO);
            assertThat(m.getCategory()).isEqualTo(MovementCategory.INGRESO_OCASIONAL);
            assertThat(m.getAmount()).isEqualTo(30000.0);
            assertThat(m.getConcept()).isEqualTo("Venta de chatarra");
            assertThat(m.getNotes()).isEqualTo("Retal de rines");
            assertThat(m.getAccount().getId()).isEqualTo(caja.getId());
            assertThat(m.getSourceType()).isNull();
            assertThat(m.getSourceId()).isNull();
        });

        assertThat(treasuryService.balanceGlobal()).isEqualTo(30000.0);
    }

    @Test
    void eliminarUnIngresoOcasionalBorraLaFilaYDevuelveElSaldo() {
        Account caja = crearCuentaEn(tenantUno, "Caja General");
        treasuryService.registrarIngresoOcasional(caja, "Saldo de apertura", 50000.0, null, null);
        double saldoPrevio = saldoDe(caja);

        Movement ingreso = treasuryService.registrarIngresoOcasional(
                caja, "Venta de chatarra", 30000.0, null, null);
        assertThat(saldoDe(caja)).isEqualTo(80000.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).hasSize(2);

        treasuryService.eliminarMovimientoManual(ingreso.getId());

        assertThat(saldoDe(caja)).isEqualTo(saldoPrevio);
        // A diferencia de una factura anulada, el registro desaparece.
        assertThat(treasuryService.findMovementById(ingreso.getId())).isEmpty();
        assertThat(treasuryService.buscarMovimientos(null, MovementCategory.INGRESO_OCASIONAL, null, null))
                .extracting(Movement::getConcept)
                .containsExactly("Saldo de apertura");
    }

    @Test
    void unIngresoOcasionalDeUnTenantNoEsVisibleParaOtro() throws Exception {
        Account cajaUno = crearCuentaEn(tenantUno, "Caja General");
        treasuryService.registrarIngresoOcasional(cajaUno, "Chatarra tenant uno", 30000.0, null, null);

        Account cajaDos = crearCuentaEn(tenantDos, "Caja General");
        treasuryService.registrarIngresoOcasional(cajaDos, "Reintegro tenant dos", 15000.0, null, null);
        TenantContext.clear();

        assertThat(conceptosVisiblesPara(tokenUno)).containsExactly("Chatarra tenant uno");
        assertThat(conceptosVisiblesPara(tokenDos)).containsExactly("Reintegro tenant dos");

        // El balance global de cada uno solo cuenta lo suyo.
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenUno))
                .andExpect(jsonPath("$.total").value(30000.0));
        mockMvc.perform(get("/api/treasury/balance").header("Authorization", "Bearer " + tokenDos))
                .andExpect(jsonPath("$.total").value(15000.0));
    }

    private List<String> conceptosVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/treasury/movements")
                        .param("category", "INGRESO_OCASIONAL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].concept");
    }

    @Test
    void elEndpointRegistraElIngresoYLoDevuelveEnLosListados() throws Exception {
        Account caja = crearCuentaEn(tenantUno, "Caja General");
        TenantContext.clear();

        MvcResult creado = mockMvc.perform(post("/api/treasury/movements")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"INGRESO_OCASIONAL","concept":"Venta de chatarra","amount":30000}
                                """.formatted(caja.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INGRESO"))
                .andExpect(jsonPath("$.category").value("INGRESO_OCASIONAL"))
                .andExpect(jsonPath("$.concept").value("Venta de chatarra"))
                .andExpect(jsonPath("$.amount").value(30000.0))
                .andExpect(jsonPath("$.accountName").value("Caja General"))
                .andReturn();
        Integer ingresoId = JsonPath.read(creado.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/treasury/movements")
                        .param("category", "INGRESO_OCASIONAL")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ingresoId));

        mockMvc.perform(get("/api/treasury/accounts/" + caja.getId() + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ingresoId))
                .andExpect(jsonPath("$[0].type").value("INGRESO"))
                .andExpect(jsonPath("$[0].category").value("INGRESO_OCASIONAL"))
                .andExpect(jsonPath("$[0].sourceType").doesNotExist());
    }

    @Test
    void losMovimientosExigenAutenticacion() throws Exception {
        mockMvc.perform(get("/api/treasury/movements")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/treasury/movements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
