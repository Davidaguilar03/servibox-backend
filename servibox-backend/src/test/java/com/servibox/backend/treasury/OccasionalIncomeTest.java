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
import com.servibox.backend.treasury.entity.OccasionalIncome;
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
 * Ingresos ocasionales: registro, efecto en el saldo y eliminacion revirtiendo.
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
    void registrarUnIngresoOcasionalSumaAlSaldoYDejaSuMovimiento() {
        Account caja = crearCuentaEn(tenantUno, "Caja General");
        assertThat(saldoDe(caja)).isEqualTo(0.0);

        OccasionalIncome ingreso = treasuryService.registrarIngresoOcasional(
                caja, "Venta de chatarra", 30000.0, LocalDate.now());

        // El saldo quedo en 30000.
        assertThat(saldoDe(caja)).isEqualTo(30000.0);

        assertThat(ingreso.getId()).isNotNull();
        assertThat(ingreso.getConcept()).isEqualTo("Venta de chatarra");
        assertThat(ingreso.getAmount()).isEqualTo(30000.0);
        assertThat(ingreso.getAccount().getId()).isEqualTo(caja.getId());

        // Y aparece en el listado de movimientos de esa cuenta, como INGRESO.
        List<Movement> movimientos = treasuryService.findMovementsByAccountId(caja.getId());
        assertThat(movimientos).hasSize(1);
        Movement movimiento = movimientos.get(0);
        assertThat(movimiento.getType()).isEqualTo(MovementType.INGRESO);
        assertThat(movimiento.getAmount()).isEqualTo(30000.0);
        assertThat(movimiento.getConcept()).isEqualTo("Venta de chatarra");
        assertThat(movimiento.getSourceOccasionalIncome().getId()).isEqualTo(ingreso.getId());
        // No viene ni de una transferencia ni de una venta.
        assertThat(movimiento.getSourceTransfer()).isNull();
        assertThat(movimiento.getSourceSale()).isNull();

        assertThat(treasuryService.balanceGlobal()).isEqualTo(30000.0);
    }

    @Test
    void anularUnIngresoOcasionalBorraElMovimientoYDevuelveElSaldo() {
        Account caja = crearCuentaEn(tenantUno, "Caja General");
        treasuryService.registrarIngresoOcasional(caja, "Saldo de apertura", 50000.0, null);
        double saldoPrevio = saldoDe(caja);

        OccasionalIncome ingreso = treasuryService.registrarIngresoOcasional(
                caja, "Venta de chatarra", 30000.0, null);
        assertThat(saldoDe(caja)).isEqualTo(80000.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).hasSize(2);

        treasuryService.anularIngresoOcasional(ingreso.getId());

        assertThat(saldoDe(caja)).isEqualTo(saldoPrevio);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).hasSize(1);
        // A diferencia de una factura anulada, el registro desaparece.
        assertThat(treasuryService.findOccasionalIncomeById(ingreso.getId())).isEmpty();
        assertThat(treasuryService.findAllOccasionalIncomes())
                .extracting(OccasionalIncome::getConcept)
                .containsExactly("Saldo de apertura");
    }

    @Test
    void unIngresoOcasionalDeUnTenantNoEsVisibleParaOtro() throws Exception {
        Account cajaUno = crearCuentaEn(tenantUno, "Caja General");
        treasuryService.registrarIngresoOcasional(cajaUno, "Chatarra tenant uno", 30000.0, null);

        Account cajaDos = crearCuentaEn(tenantDos, "Caja General");
        treasuryService.registrarIngresoOcasional(cajaDos, "Reintegro tenant dos", 15000.0, null);
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
        MvcResult result = mockMvc.perform(get("/api/treasury/occasional-incomes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].concept");
    }

    @Test
    void elEndpointRegistraElIngresoYLoDevuelveEnElListado() throws Exception {
        Account caja = crearCuentaEn(tenantUno, "Caja General");
        TenantContext.clear();

        mockMvc.perform(post("/api/treasury/occasional-incomes")
                        .header("Authorization", "Bearer " + tokenUno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"concept":"Venta de chatarra","amount":30000}
                                """.formatted(caja.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.concept").value("Venta de chatarra"))
                .andExpect(jsonPath("$.amount").value(30000.0))
                .andExpect(jsonPath("$.accountName").value("Caja General"));

        mockMvc.perform(get("/api/treasury/occasional-incomes")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // El movimiento quedo ligado al ingreso tambien via HTTP.
        mockMvc.perform(get("/api/treasury/accounts/" + caja.getId() + "/movements")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("INGRESO"))
                .andExpect(jsonPath("$[0].amount").value(30000.0))
                .andExpect(jsonPath("$[0].concept").value("Venta de chatarra"))
                .andExpect(jsonPath("$[0].sourceTransferId").doesNotExist())
                .andExpect(jsonPath("$[0].sourceSaleId").doesNotExist());
    }

    @Test
    void losIngresosOcasionalesExigenAutenticacion() throws Exception {
        mockMvc.perform(get("/api/treasury/occasional-incomes")).andExpect(status().isUnauthorized());
    }
}
