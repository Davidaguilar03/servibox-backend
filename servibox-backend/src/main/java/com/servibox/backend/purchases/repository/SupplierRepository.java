package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find(). Ver 02-CONVENTIONS.md.
     */
    Optional<Supplier> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Supplier> findByDocumentAndTenantId(String document, Long tenantId);
}
