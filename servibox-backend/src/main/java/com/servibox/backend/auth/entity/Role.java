package com.servibox.backend.auth.entity;

/**
 * Roles del diccionario (`rol` en USUARIOS). La unica regla por rol es que solo ADMIN edita
 * el perfil del tenant (TenantController). Ver 03-DECISIONS.md.
 */
public enum Role {
    ADMIN,
    MECANICO,
    CAJERO
}
