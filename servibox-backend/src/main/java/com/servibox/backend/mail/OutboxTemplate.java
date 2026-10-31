package com.servibox.backend.mail;

/** Plantillas del outbox. El asunto es fijo; el cuerpo viaja cifrado en el mensaje. */
public enum OutboxTemplate {
    ACTIVATION("Activa tu cuenta de ServiBox");

    private final String subject;

    OutboxTemplate(String subject) {
        this.subject = subject;
    }

    public String subject() {
        return subject;
    }
}
