package com.servibox.backend.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * Transporte SMTP configurado por SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD y
 * MAIL_FROM. Sin SMTP_HOST no existe el bean: el outbox queda PENDING y la app arranca igual.
 * SMTP_SECURITY elige el cifrado: starttls (por defecto, exige STARTTLS, puerto 587) o ssl (TLS
 * implicito, SMTPS, puerto 465). En ambos se verifica el certificado y el nombre del servidor;
 * nunca se confia en todos. No registra destinatarios, cuerpos ni credenciales.
 */
@Component
@ConditionalOnExpression("'${servibox.mail.smtp.host:}' != ''")
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final byte[] logoPng;

    public SmtpMailTransport(
            @Value("${servibox.mail.smtp.host}") String host,
            @Value("${servibox.mail.smtp.port}") int port,
            @Value("${servibox.mail.smtp.username}") String username,
            @Value("${servibox.mail.smtp.password}") String password,
            @Value("${servibox.mail.smtp.security}") String security,
            @Value("${servibox.mail.from}") String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("MAIL_FROM es obligatorio cuando SMTP_HOST esta configurado");
        }
        String modo = security == null ? "" : security.strip().toLowerCase(Locale.ROOT);
        if (!modo.equals("starttls") && !modo.equals("ssl")) {
            throw new IllegalStateException("SMTP_SECURITY debe ser starttls o ssl");
        }
        sender.setHost(host);
        sender.setPort(port);
        if (!username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        if (modo.equals("ssl")) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        this.from = from;
        try {
            this.logoPng = new ClassPathResource("mail/servibox-logo.png").getContentAsByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el logo del correo", ex);
        }
    }

    /** Solo para pruebas del mapeo de propiedades. */
    Properties javaMailProperties() {
        return sender.getJavaMailProperties();
    }

    @Override
    public void send(String to, String subject, String text, String html) throws MailTransportException {
        try {
            sender.send(build(to, subject, text, html));
        } catch (MessagingException | MailParseException | MailPreparationException ex) {
            throw new MailTransportException(true, ex);
        } catch (MailException ex) {
            throw new MailTransportException(false, ex);
        }
    }

    /** Con html: alternative(texto, related(html, logo)), sin adjuntos visibles. Sin html: texto plano simple. */
    MimeMessage build(String to, String subject, String text, String html) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        if (html == null) {
            helper.setText(text);
            return message;
        }
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setText(html, "UTF-8", "html");
        MimeBodyPart logo = new MimeBodyPart();
        logo.setDataHandler(new DataHandler(new ByteArrayDataSource(logoPng, "image/png")));
        logo.setContentID("<" + MailRenderer.LOGO_CID + ">");
        logo.setDisposition(Part.INLINE);
        logo.setFileName("servibox-logo.png");
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(htmlPart);
        related.addBodyPart(logo);
        MimeBodyPart relatedPart = new MimeBodyPart();
        relatedPart.setContent(related);
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(text, "UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(relatedPart);
        message.setContent(alternative);
        return message;
    }
}
