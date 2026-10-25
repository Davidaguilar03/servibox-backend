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
     * Mismas reglas y mensajes que el registro: nombre, NIT y telefono obligatorios, en ese
     * orden, y recortados. Un tenant antiguo con NIT o telefono null tiene que mandar los dos
     * para editar su perfil. En la base las columnas siguen admitiendo null (03-DECISIONS.md).
     */
    @Transactional
    public Tenant actualizar(TenantProfileRequest request) {
        TenantRegistrationService.validarNombreComercial(request.name());
        TenantRegistrationService.validarNit(request.nit());
        TenantRegistrationService.validarTelefono(request.phone());

        Tenant tenant = actual();
        tenant.setName(request.name().trim());
        tenant.setNit(request.nit().trim());
        tenant.setPhone(request.phone().trim());
        return tenantRepository.save(tenant);
    }
}
