package com.servibox.backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginIntegrationTest {

    private static final String SLUG = "taller-prueba";
    private static final String USERNAME = "admin-prueba";
    private static final String PASSWORD = "clave-correcta";
    private static final String RUTA_PROTEGIDA = "/api/prueba/protegida";

    /**
     * No hay ninguna ruta de negocio todavia. Este controlador existe solo para tener algo
     * detras de la cadena de seguridad contra lo que probar.
     */
    @TestConfiguration
    static class RutaProtegidaDePrueba {

        @RestController
        static class ControladorDePrueba {
            @GetMapping(RUTA_PROTEGIDA)
            String responder() {
                return "ok";
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Long tenantId;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = new Tenant();
        tenant.setName("Taller de Prueba");
        tenant.setSlug(SLUG);
        tenant.setActive(Boolean.TRUE);
        tenantId = tenantRepository.save(tenant).getId();

        TenantContext.setTenantId(tenantId);
        try {
            User user = new User();
            user.setUsername(USERNAME);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setEmail("admin@taller-prueba.local");
            user.setActive(true);
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private String body(String password) {
        return """
                {"tenantSlug":"%s","username":"%s","password":"%s"}
                """.formatted(SLUG, USERNAME, password);
    }

    private String loginYObtenerToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private void desactivarUsuario() {
        TenantContext.setTenantId(tenantId);
        try {
            User user = userRepository.findByUsernameAndTenantId(USERNAME, tenantId).orElseThrow();
            user.setActive(false);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void loginConCredencialesCorrectasDevuelveToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginConContrasenaIncorrectaDevuelve401ConFormatoDeError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("clave-equivocada")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Credenciales invalidas"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void elTokenLlevaElTenantIdDelUsuario() throws Exception {
        Claims claims = jwtService.validateAndParse(loginYObtenerToken());

        assertThat(jwtService.extractTenantId(claims)).isEqualTo(tenantId);
        assertThat(jwtService.extractUsername(claims)).isEqualTo(USERNAME);
        assertThat(jwtService.extractRole(claims)).isEqualTo(Role.ADMIN.name());
    }

    @Test
    void tokenValidoDeUsuarioActivoAccedeARutaProtegida() throws Exception {
        String token = loginYObtenerToken();

        mockMvc.perform(get(RUTA_PROTEGIDA).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenValidoDeUsuarioDesactivadoDespuesEsRechazado() throws Exception {
        String token = loginYObtenerToken();

        desactivarUsuario();

        mockMvc.perform(get(RUTA_PROTEGIDA).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rutaProtegidaSinTokenEsRechazada() throws Exception {
        mockMvc.perform(get(RUTA_PROTEGIDA))
                .andExpect(status().isUnauthorized());
    }
}
