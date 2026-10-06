package com.servibox.backend.counterparties.repository;

import com.servibox.backend.counterparties.entity.Counterparty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find() y devuelve filas de otros tenants.
     * Ver 02-CONVENTIONS.md.
     */
    Optional<Counterparty> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Counterparty> findByDocumentNumberAndTenantId(String documentNumber, Long tenantId);
}
