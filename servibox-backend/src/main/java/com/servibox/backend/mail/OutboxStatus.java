package com.servibox.backend.mail;

/** SENT es "aceptado por el transporte", no "entregado al buzon". */
public enum OutboxStatus {
    PENDING, SENT, FAILED
}
