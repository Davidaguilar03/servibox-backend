package com.servibox.backend.mail;

/**
 * Entrega un correo a un transporte (SMTP hoy). Volver sin excepcion significa que el
 * transporte lo acepto, no que llego al buzon ni que se leyo.
 */
public interface MailTransport {

    /** html null: solo texto plano. Con html: multipart/alternative con el logo incrustado (MailRenderer.LOGO_CID). */
    void send(String to, String subject, String text, String html) throws MailTransportException;
}
