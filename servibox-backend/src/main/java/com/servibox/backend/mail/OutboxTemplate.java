package com.servibox.backend.mail;

/**
 * Plantillas del outbox. Asunto, boton y motivo del pie son fijos; el cuerpo viaja cifrado en el
 * mensaje. actionPath identifica en el cuerpo el enlace que se vuelve boton (ver MailRenderer).
 */
public enum OutboxTemplate {
    ACTIVATION("Activa tu cuenta de ServiBox", "Activar mi cuenta", "/activar#token=",
            "Recibes este correo porque se solicitó una cuenta de ServiBox con esta dirección.");

    private final String subject;
    private final String actionLabel;
    private final String actionPath;
    private final String reason;

    OutboxTemplate(String subject, String actionLabel, String actionPath, String reason) {
        this.subject = subject;
        this.actionLabel = actionLabel;
        this.actionPath = actionPath;
        this.reason = reason;
    }

    public String subject() {
        return subject;
    }

    public String actionLabel() {
        return actionLabel;
    }

    public String actionPath() {
        return actionPath;
    }

    public String reason() {
        return reason;
    }
}
