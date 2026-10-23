package com.servibox.backend.tenant;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.tenant.dto.TenantProfileRequest;
import com.servibox.backend.tenant.dto.TenantProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {

    private static final String ROL_ADMIN = "ROLE_" + Role.ADMIN.name();

    private final TenantService tenantService;

    @GetMapping
    public TenantProfileResponse perfil() {
        return TenantProfileResponse.from(tenantService.actual());
    }

    /**
     * Solo ADMIN. Es la primera regla por rol del proyecto: se mira la authority que pone
     * JwtAuthenticationFilter (sale del rol en base), sin @PreAuthorize ni reglas en
     * SecurityConfig. Ver 03-DECISIONS.md.
     */
    @PutMapping
    public TenantProfileResponse actualizar(@RequestBody TenantProfileRequest request, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> ROL_ADMIN.equals(a.getAuthority()));
        if (!admin) {
            throw new AdminRequiredException();
        }
        return TenantProfileResponse.from(tenantService.actualizar(request));
    }
}
