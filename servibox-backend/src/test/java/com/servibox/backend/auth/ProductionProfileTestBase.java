package com.servibox.backend.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contexto con los perfiles test y prod sobre H2 (tarea 51): ProductionConfiguration valida y
 * el registro inmediato ve el perfil prod. La base y las claves (ficticias, solo de estas
 * pruebas) van como propiedades de la prueba, que mandan sobre las de ambos perfiles.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:servibox-prod-${random.uuid};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "servibox.jwt.secret=unit-prod-profile-tenant-key-2026-xxxxxxxx",
        "servibox.platform.jwt.secret=unit-prod-profile-platform-key-2026-xxxxxx",
        "servibox.mail.outbox.encryption-key=unit-prod-profile-outbox-key-2026-xxxxxxx",
        "servibox.activation.base-url=https://app.example.invalid"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
abstract class ProductionProfileTestBase {

    static final String NOT_FOUND = "{\"error\":\"Recurso no encontrado\",\"status\":404}";

    static final String REGISTRO = """
            {"businessName":"Taller Prod","nit":"900123456-7","phone":"3001234567","slug":"taller-prod",
             "adminFullName":"Ana Prod","adminEmail":"ana@example.invalid","adminUsername":"admin",
             "password":"clave-segura-1","acceptTerms":true}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    void registroInmediatoResponde404SinCrearNada() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND, true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Long.class)).isZero();
    }
}
