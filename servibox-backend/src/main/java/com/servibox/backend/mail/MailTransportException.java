package com.servibox.backend.mail;

/** Fallo del transporte. permanent: no tiene sentido reintentar el mismo mensaje. Sin datos del correo. */
public class MailTransportException extends Exception {

    private final boolean permanent;

    public MailTransportException(boolean permanent, Throwable cause) {
        super(cause == null ? "Fallo de transporte" : cause.getClass().getSimpleName(), cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
