package com.servibox.backend.tenant.dto;

import com.servibox.backend.tenant.Tenant;

/** nit y phone son null solo en los tenants anteriores a que fueran obligatorios, hasta que editen su perfil. */
public record TenantProfileResponse(String name, String slug, String nit, String phone) {

    public static TenantProfileResponse from(Tenant tenant) {
        return new TenantProfileResponse(tenant.getName(), tenant.getSlug(), tenant.getNit(), tenant.getPhone());
    }
}
