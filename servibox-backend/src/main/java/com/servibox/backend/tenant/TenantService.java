package com.servibox.backend.tenant;

import com.servibox.backend.auth.TenantRegistrationService;
import com.servibox.backend.tenant.dto.TenantProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Perfil del tenant del token. TENANTS no es una entidad por tenant (el filtro de Hibernate
 * no aplica), asi que siempre va por el id de TenantContext.
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public Tenant actual() {
        return tenantRepository.findById(TenantContext.getTenantId())
                .orElseThrow(() -> new IllegalStateException("No hay un tenant activo"));
    }

    /**
     * Mismas reglas y mensajes que el registro, salvo que nit y phone son opcionales: en
     * blanco se guardan como null.
     */
    @Transactional
    public Tenant actualizar(TenantProfileRequest request) {
        TenantRegistrationService.validarNombreComercial(request.name());
        String nit = enBlancoANull(request.nit());
        if (nit != null) {
            TenantRegistrationService.validarNit(nit);
        }
        String telefono = enBlancoANull(request.phone());
        if (telefono != null) {
            TenantRegistrationService.validarTelefono(telefono);
        }

        Tenant tenant = actual();
        tenant.setName(request.name().trim());
        tenant.setNit(nit);
        tenant.setPhone(telefono);
        return tenantRepository.save(tenant);
    }

    private static String enBlancoANull(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
