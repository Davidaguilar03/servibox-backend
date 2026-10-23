package com.servibox.backend.tenant.dto;

import com.servibox.backend.tenant.Tenant;

/** nit y phone son null en los tenants anteriores al registro publico o si se borraron. */
public record TenantProfileResponse(String name, String slug, String nit, String phone) {

    public static TenantProfileResponse from(Tenant tenant) {
        return new TenantProfileResponse(tenant.getName(), tenant.getSlug(), tenant.getNit(), tenant.getPhone());
    }
}
