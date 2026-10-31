package com.servibox.backend.tenant;

/**
 * Estado del tenant visto por la plataforma. En la base es activo_tenant: true ACTIVE,
 * false SUSPENDED. Las transiciones validas viven aqui; cualquier otra es 409.
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED;

    public static TenantStatus of(Tenant tenant) {
        return Boolean.TRUE.equals(tenant.getActive()) ? ACTIVE : SUSPENDED;
    }

    public boolean canTransitionTo(TenantStatus target) {
        return switch (this) {
            case ACTIVE -> target == SUSPENDED;
            case SUSPENDED -> target == ACTIVE;
        };
    }

    public boolean active() {
        return this == ACTIVE;
    }
}
