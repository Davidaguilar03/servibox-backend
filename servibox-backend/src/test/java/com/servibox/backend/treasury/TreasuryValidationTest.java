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
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saldo del origen en POST /api/treasury/transfers (409 con montos en moneda) y largo de
 * concepto y notas en los movimientos manuales. Los gastos siguen sin exigir saldo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TreasuryValidationTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String token;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-tesoreria-validacion-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        TenantContext.setTenantId(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
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

    private int crearCuenta(String nombre, String saldoInicial) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/treasury/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","initialBalance":%s,"type":"EFECTIVO"}
                                """.formatted(nombre, saldoInicial)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private ResultActions transferir(int origen, int destino, String monto) throws Exception {
        return mockMvc.perform(post("/api/treasury/transfers")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originAccountId":%d,"destinationAccountId":%d,"amount":%s}
                        """.formatted(origen, destino, monto)));
    }

    private ResultActions movimiento(String metodo, String ruta, int cuenta, String concepto, String notas)
            throws Exception {
        String cuerpo = """
                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"%s","amount":1000,"notes":"%s"}
                """.formatted(cuenta, concepto, notas);
        return mockMvc.perform((metodo.equals("POST") ? post(ruta) : put(ruta))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private ResultActions cuentas() throws Exception {
        return mockMvc.perform(get("/api/treasury/accounts").header("Authorization", "Bearer " + token));
    }

    private ResultActions movimientos() throws Exception {
        return mockMvc.perform(get("/api/treasury/movements").header("Authorization", "Bearer " + token));
    }

    @Test
    void unaTransferenciaSinSaldoEs409YNoMueveDineroEnNingunaCuenta() throws Exception {
        int caja = crearCuenta("Caja General", "3250000");
        int banco = crearCuenta("Bancolombia", "100");

        transferir(caja, banco, "4212600")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error")
                        .value("Saldo insuficiente en Caja General: hay $ 3.250.000 y se requieren $ 4.212.600"));

        cuentas()
                .andExpect(jsonPath("$[?(@.id == %d)].currentBalance".formatted(caja)).value(3250000.0))
                .andExpect(jsonPath("$[?(@.id == %d)].currentBalance".formatted(banco)).value(100.0));
        movimientos().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void conElSaldoExactoPasaYUnPesoDeMasEs409() throws Exception {
        int caja = crearCuenta("Caja General", "50000");
        int banco = crearCuenta("Bancolombia", "0");

        transferir(caja, banco, "50001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Saldo insuficiente en Caja General: hay $ 50.000 y se requieren $ 50.001"));

        transferir(caja, banco, "50000").andExpect(status().isCreated());
        cuentas()
                .andExpect(jsonPath("$[?(@.id == %d)].currentBalance".formatted(caja)).value(0.0))
                .andExpect(jsonPath("$[?(@.id == %d)].currentBalance".formatted(banco)).value(50000.0));

        transferir(caja, banco, "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Saldo insuficiente en Caja General: hay $ 0 y se requieren $ 1"));
    }

    @Test
    void elMensajeDeSaldoNoUsaNotacionDecimalNiCientificaConDoceMillones() throws Exception {
        int caja = crearCuenta("Caja General", "11999999.4");
        int banco = crearCuenta("Bancolombia", "0");

        transferir(caja, banco, "12000000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Saldo insuficiente en Caja General: hay $ 11.999.999 y se requieren $ 12.000.000"));
    }

    @Test
    void unGastoOperativoSigueSinExigirSaldo() throws Exception {
        int caja = crearCuenta("Caja General", "0");

        movimiento("POST", "/api/treasury/movements", caja, "Arriendo", "")
                .andExpect(status().isCreated());
        cuentas().andExpect(jsonPath("$[0].currentBalance").value(-1000.0));
    }

    @Test
    void conceptoYNotasDeMasDe255SonCuatrocientosEnElPostYEnElPut() throws Exception {
        int caja = crearCuenta("Caja General", "0");
        String largo = "x".repeat(256);

        movimiento("POST", "/api/treasury/movements", caja, largo, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El concepto admite hasta 255 caracteres"));
        movimiento("POST", "/api/treasury/movements", caja, "Arriendo", largo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Las notas admiten hasta 255 caracteres"));
        movimientos().andExpect(jsonPath("$.length()").value(0));

        MvcResult creado = movimiento("POST", "/api/treasury/movements", caja, "x".repeat(255), "y".repeat(255))
                .andExpect(status().isCreated())
                .andReturn();
        int id = JsonPath.read(creado.getResponse().getContentAsString(), "$.id");

        movimiento("PUT", "/api/treasury/movements/" + id, caja, largo, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El concepto admite hasta 255 caracteres"));
        movimiento("PUT", "/api/treasury/movements/" + id, caja, "Arriendo", largo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Las notas admiten hasta 255 caracteres"));
        movimientos().andExpect(jsonPath("$[0].concept").value("x".repeat(255)));
        cuentas().andExpect(jsonPath("$[0].currentBalance").value(-1000.0));
    }
}
