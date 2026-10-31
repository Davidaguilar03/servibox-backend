package com.servibox.backend.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Solo configuracion: crear el transporte no abre conexion. Valores ficticios, sin credenciales reales. */
class SmtpMailTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmtpMailTransport.class)
            .withPropertyValues("servibox.mail.smtp.port=587", "servibox.mail.smtp.username=",
                    "servibox.mail.smtp.password=", "servibox.mail.from=");

    @Test
    void sinHostNoHayTransporte() {
        runner.withPropertyValues("servibox.mail.smtp.host=")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MailTransport.class));
    }

    @Test
    void conHostYRemitenteHayTransporteSmtp() {
        runner.withPropertyValues("servibox.mail.smtp.host=smtp.example.invalid", "servibox.mail.from=no-reply@example.invalid")
                .run(context -> assertThat(context).hasSingleBean(SmtpMailTransport.class));
    }

    @Test
    void conHostSinRemitenteNoArranca() {
        runner.withPropertyValues("servibox.mail.smtp.host=smtp.example.invalid")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("MAIL_FROM es obligatorio cuando SMTP_HOST esta configurado"));
    }
}
