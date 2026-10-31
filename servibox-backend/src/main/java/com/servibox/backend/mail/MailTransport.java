package com.servibox.backend.mail;

/**
 * Entrega un correo a un transporte (SMTP hoy). Volver sin excepcion significa que el
 * transporte lo acepto, no que llego al buzon ni que se leyo.
 */
public interface MailTransport {

    void send(String to, String subject, String body) throws MailTransportException;
}
