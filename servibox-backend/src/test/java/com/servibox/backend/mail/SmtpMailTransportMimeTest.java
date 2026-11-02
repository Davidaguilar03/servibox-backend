package com.servibox.backend.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Estructura MIME real: se serializa como saldria por SMTP y se vuelve a leer. No abre red. */
class SmtpMailTransportMimeTest {

    private final SmtpMailTransport transport = new SmtpMailTransport("smtp.example.invalid", 587, "", "", "starttls",
            "ServiBox <no-reply@servibox.co>");

    private MimeMessage serializado(String text, String html) throws Exception {
        MimeMessage message = transport.build("ana@example.invalid", "Activa tu cuenta de ServiBox", text, html);
        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void conHtmlEsAlternativeConTextoYRelatedConLogoEnLinea() throws Exception {
        MimeMessage m = serializado("Hola Peña, enlace en texto", "<p>Hola Peña</p><img src=\"cid:" + MailRenderer.LOGO_CID + "\">");

        InternetAddress from = (InternetAddress) m.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@servibox.co");
        assertThat(from.getPersonal()).isEqualTo("ServiBox");
        assertThat(m.getAllRecipients()[0]).hasToString("ana@example.invalid");
        assertThat(m.getSubject()).isEqualTo("Activa tu cuenta de ServiBox");
        assertThat(m.getContentType()).startsWith("multipart/alternative");

        Multipart alternative = (Multipart) m.getContent();
        assertThat(alternative.getCount()).isEqualTo(2);
        BodyPart texto = alternative.getBodyPart(0);
        assertThat(texto.isMimeType("text/plain")).isTrue();
        assertThat(texto.getContentType()).containsIgnoringCase("utf-8");
        assertThat(texto.getContent()).isEqualTo("Hola Peña, enlace en texto");

        BodyPart relatedPart = alternative.getBodyPart(1);
        assertThat(relatedPart.isMimeType("multipart/related")).isTrue();
        Multipart related = (Multipart) relatedPart.getContent();
        assertThat(related.getCount()).isEqualTo(2);
        BodyPart html = related.getBodyPart(0);
        assertThat(html.isMimeType("text/html")).isTrue();
        assertThat((String) html.getContent()).contains("Hola Peña").contains("cid:" + MailRenderer.LOGO_CID);

        BodyPart logo = related.getBodyPart(1);
        assertThat(logo.isMimeType("image/png")).isTrue();
        assertThat(logo.getDisposition()).isEqualTo(Part.INLINE);
        assertThat(logo.getHeader("Content-ID")).containsExactly("<" + MailRenderer.LOGO_CID + ">");
        try (InputStream in = logo.getInputStream()) {
            assertThat(in.readNBytes(8)).containsExactly(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        }
    }

    @Test
    void sinHtmlEsTextoSimpleComoAntes() throws Exception {
        MimeMessage m = serializado("cuerpo viejo con eñe", null);

        assertThat(m.isMimeType("text/plain")).isTrue();
        assertThat(m.getContent()).isEqualTo("cuerpo viejo con eñe");
    }

    @Test
    void direccionInvalidaEsFalloPermanenteSinIntentarRed() {
        MailTransportException ex = org.junit.jupiter.api.Assertions.assertThrows(MailTransportException.class,
                () -> transport.send("no es correo@@", "Asunto", "texto", null));

        assertThat(ex.isPermanent()).isTrue();
    }
}
