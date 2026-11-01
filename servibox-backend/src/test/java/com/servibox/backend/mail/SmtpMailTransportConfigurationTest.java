package com.servibox.backend.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Solo configuracion: crear el transporte no abre conexion. Valores ficticios, sin credenciales reales. */
class SmtpMailTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmtpMailTransport.class)
            .withPropertyValues("servibox.mail.smtp.port=587", "servibox.mail.smtp.username=",
                    "servibox.mail.smtp.password=", "servibox.mail.from=", "servibox.mail.smtp.security=starttls");
    private static final String[] HOST = {"servibox.mail.smtp.host=smtp.example.invalid",
            "servibox.mail.from=no-reply@example.invalid"};

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

    @Test
    void starttlsPorDefectoExigeStarttlsYVerificaElServidor() {
        runner.withPropertyValues(HOST).run(context -> {
            Properties p = context.getBean(SmtpMailTransport.class).javaMailProperties();
            assertThat(p).containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.starttls.required", "true")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                    .doesNotContainKey("mail.smtp.ssl.enable").doesNotContainKey("mail.smtp.ssl.trust")
                    .doesNotContainKey("mail.smtp.ssl.socketFactory");
        });
    }

    @Test
    void sslUsaTlsImplicitoSinStarttlsYVerificaElServidor() {
        for (String valor : new String[]{"ssl", " SSL "}) {
            runner.withPropertyValues(HOST).withPropertyValues("servibox.mail.smtp.security=" + valor,
                    "servibox.mail.smtp.port=465").run(context -> {
                SmtpMailTransport t = context.getBean(SmtpMailTransport.class);
                Properties p = t.javaMailProperties();
                assertThat(p).containsEntry("mail.smtp.ssl.enable", "true")
                        .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                        .doesNotContainKey("mail.smtp.starttls.enable").doesNotContainKey("mail.smtp.starttls.required")
                        .doesNotContainKey("mail.smtp.ssl.trust").doesNotContainKey("mail.smtp.ssl.socketFactory");
                assertThat(((JavaMailSenderImpl) ReflectionTestUtils.getField(t, "sender")).getPort()).isEqualTo(465);
            });
        }
    }

    @Test
    void valorDeSeguridadInvalidoNoArranca() {
        for (String valor : new String[]{"tls", "", "none", "smtps"}) {
            runner.withPropertyValues(HOST).withPropertyValues("servibox.mail.smtp.security=" + valor)
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("SMTP_SECURITY debe ser starttls o ssl"));
        }
    }

    @Test
    void laVariableSmtpSecurityLlegaALaPropiedad() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("SMTP_SECURITY=ssl")
                .run(context -> assertThat(context.getEnvironment().getProperty("servibox.mail.smtp.security"))
                        .isEqualTo("ssl"));
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getEnvironment().getProperty("servibox.mail.smtp.security"))
                        .isEqualTo("starttls"));
    }
}
