package com.servibox.backend.config;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Datos minimos para probar en local. Solo perfil dev.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer implements CommandLineRunner {

    private static final String TENANT_SLUG = "demo";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    /** Las 8 categorias por defecto de Autollantas, con su color de chip. */
    private static final Map<String, String> CATEGORIAS_POR_DEFECTO = Map.of(
            "LLANTAS", "#e74c3c",
            "FILTROS DE AIRE", "#e67e22",
            "ACEITES", "#f1c40f",
            "BATERIAS", "#2ecc71",
            "FILTROS DE ACEITE", "#1abc9c",
            "PLUMILLAS", "#3498db",
            "ELECTRICOS", "#9b59b6",
            "OTROS", "#95a5a6"
    );

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InventoryService inventoryService;

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
        // poblarlo antes de guardar cualquier entidad de negocio.
        TenantContext.setTenantId(creado.getId());
        try {
            sembrarAdmin();
            TaxType iva = sembrarIva();
            sembrarCategorias(iva);
        } finally {
            TenantContext.clear();
        }

        log.info("Perfil dev: tenant [{}], usuario [{}], IVA y {} categorias creados",
                TENANT_SLUG, ADMIN_USERNAME, CATEGORIAS_POR_DEFECTO.size());
    }

    private void sembrarAdmin() {
        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setEmail("admin@demo.local");
        admin.setActive(true);
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
    }

    private TaxType sembrarIva() {
        TaxType iva = new TaxType();
        iva.setName("IVA");
        iva.setRate(0.19);
        iva.setDescription("Impuesto al Valor Agregado");
        iva.setAppliesToTransaction(Boolean.TRUE);
        iva.setIsVat(Boolean.TRUE);
        return inventoryService.saveTaxType(iva);
    }

    private void sembrarCategorias(TaxType iva) {
        CATEGORIAS_POR_DEFECTO.forEach((nombre, color) -> {
            ProductCategory categoria = new ProductCategory();
            categoria.setName(nombre);
            categoria.setColor(color);
            categoria.setTaxTypes(List.of(iva));
            inventoryService.saveCategory(categoria);
        });
    }
}
