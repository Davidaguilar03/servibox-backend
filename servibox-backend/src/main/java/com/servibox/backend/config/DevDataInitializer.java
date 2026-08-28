package com.servibox.backend.config;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Datos minimos para probar el login en local. Solo perfil dev.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer implements CommandLineRunner {

    private static final String TENANT_SLUG = "demo";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (tenantRepository.count() > 0) {
            return;
        }

        Tenant tenant = new Tenant();
        tenant.setName("ServiBox Demo");
        tenant.setSlug(TENANT_SLUG);
        tenant.setActive(Boolean.TRUE);
        Tenant creado = tenantRepository.save(tenant);

        // TenantAwareEntity asigna tenantId en @PrePersist desde TenantContext, hay que
        // poblarlo antes de guardar el usuario.
        TenantContext.setTenantId(creado.getId());
        try {
            User admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
            admin.setEmail("admin@demo.local");
            admin.setActive(true);
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);
        } finally {
            TenantContext.clear();
        }

        log.info("Perfil dev: tenant [{}] y usuario [{}] creados para pruebas locales",
                TENANT_SLUG, ADMIN_USERNAME);
    }
}
