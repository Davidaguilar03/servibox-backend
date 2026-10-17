package com.servibox.backend.auth.entity;

/**
 * Roles del diccionario (`rol` en USUARIOS). Por ahora solo viajan en el JWT: no hay
 * reglas de permisos por rol. Ver 03-DECISIONS.md.
 */
public enum Role {
    ADMIN,
    MECANICO,
    CAJERO
}
