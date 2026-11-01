package com.servibox.backend.tenant;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    /** Para el registro: un slug nuevo no puede coincidir con otro ni en mayusculas. */
    boolean existsBySlugIgnoreCase(String slug);

    /** Coincidencia indicativa para la revision de solicitudes de registro; el NIT no es unico. */
    long countByNit(String nit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> lockById(@Param("id") Long id);

    /** Panel de plataforma: estados en `actives`, `pattern` normalizado (SearchText) con % y escape '\'. */
    @Query("select t from Tenant t where t.active in :actives"
            + " and (t.nameSearch like :pattern escape '\\' or lower(t.slug) like :pattern escape '\\')")
    Page<Tenant> search(@Param("actives") Collection<Boolean> actives, @Param("pattern") String pattern,
            Pageable pageable);

    long countByActive(Boolean active);
}
