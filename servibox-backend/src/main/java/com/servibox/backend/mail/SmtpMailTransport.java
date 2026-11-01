package com.servibox.backend.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

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
    }

    /** Solo para pruebas del mapeo de propiedades. */
    Properties javaMailProperties() {
        return sender.getJavaMailProperties();
    }

    @Override
    public void send(String to, String subject, String body) throws MailTransportException {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailParseException | MailPreparationException ex) {
            throw new MailTransportException(true, ex);
        } catch (MailException ex) {
            throw new MailTransportException(false, ex);
        }
    }
}
