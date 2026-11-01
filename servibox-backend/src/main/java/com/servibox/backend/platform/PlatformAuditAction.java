package com.servibox.backend.platform;

/** Acciones de escritura de la plataforma que quedan en AUDITORIA_PLATAFORMA (CHECK ck_auditoria_accion). */
public enum PlatformAuditAction {
    SUSPEND_TENANT,
    REACTIVATE_TENANT,
    REVOKE_USER_SESSIONS,
    REVOKE_TENANT_SESSIONS,
    /** Reenvio del enlace de activacion de una solicitud (V12): apunta a la solicitud, no a un tenant. */
    RESEND_ACTIVATION
}