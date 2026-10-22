package com.servibox.backend.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    /** Para el registro: un slug nuevo no puede coincidir con otro ni en mayusculas. */
    boolean existsBySlugIgnoreCase(String slug);
}
