package com.servibox.backend.sales.repository;

import com.servibox.backend.sales.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find() y devuelve filas de otros tenants.
     * Ver 02-CONVENTIONS.md.
     */
    Optional<Customer> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Customer> findByDocumentAndTenantId(String document, Long tenantId);
}
