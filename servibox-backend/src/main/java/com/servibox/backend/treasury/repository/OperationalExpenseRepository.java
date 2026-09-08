package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.OperationalExpense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperationalExpenseRepository extends JpaRepository<OperationalExpense, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find(). Ver 02-CONVENTIONS.md.
     */
    Optional<OperationalExpense> findByIdAndTenantId(Long id, Long tenantId);
}
