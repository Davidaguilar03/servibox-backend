package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.mail.MailTransport;
import com.servibox.backend.mail.OutboxWorker;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Sin SMTP_HOST no hay transporte: la app arranca, aprobar encola y el correo queda PENDING. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
class ActivationWithoutTransportTest {

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired OutboxWorker worker;
    @Autowired ObjectProvider<MailTransport> transport;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        RegistrationActivationTest.limpiar(jdbc, requests, admins);
    }

    @Test
    void sinTransporteElCorreoQuedaPendingConSuCuerpoCifrado() throws Exception {
        assertThat(transport.getIfAvailable()).isNull();
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        String platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
        MvcResult r = mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-sin-smtp")
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo("taller-sin-smtp")))
                .andReturn();
        String id = JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");
        assertThat(mvc.perform(post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/approve")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(worker.processBatch()).isZero();
        assertThat(jdbc.queryForMap("SELECT estado, intentos, payload_cifrado FROM correos_salientes"))
                .containsEntry("ESTADO", "PENDING").containsEntry("INTENTOS", 0)
                .hasEntrySatisfying("PAYLOAD_CIFRADO", p -> assertThat(p).isNotNull());
    }
}
