package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.mail.FakeMailTransport;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** La tarea programada (formato de duracion de Spring) entrega sola lo que aprobar encolo. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeMailTransport.class)
@TestPropertySource(properties = {"servibox.registration-requests.enabled=true",
        "servibox.mail.outbox.scheduler-enabled=true", "servibox.mail.outbox.poll-interval=200ms"})
class ScheduledOutboxTest {

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired FakeMailTransport transport;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        RegistrationActivationTest.limpiar(jdbc, requests, admins);
    }

    @Test
    void elWorkerProgramadoEntregaElCorreoDeActivacion() throws Exception {
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        String platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
        MvcResult r = mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-programado")
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo("taller-programado")))
                .andReturn();
        String id = JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");
        mvc.perform(post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/approve")
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"));

        long limite = System.currentTimeMillis() + 10_000;
        while (transport.sent().isEmpty() && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
        }
        assertThat(transport.sent()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT estado FROM correos_salientes", String.class)).isEqualTo("SENT");
    }
}
