package com.servibox.backend.tenant;

/**
 * Holder del tenant activo para el hilo que atiende el request.
 * Lo llena JwtAuthenticationFilter desde el claim del JWT y lo consume el filtro de
 * Hibernate que habilita TenantAwareJpaTransactionManager.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
