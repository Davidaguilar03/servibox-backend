package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.OperationalExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OperationalExpenseRepository extends JpaRepository<OperationalExpense, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find(). Ver 02-CONVENTIONS.md.
     */
    Optional<OperationalExpense> findByIdAndTenantId(Long id, Long tenantId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM OperationalExpense e")
    double sumAmount();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM OperationalExpense e WHERE e.date BETWEEN :desde AND :hasta")
    double sumAmountByDateBetween(LocalDate desde, LocalDate hasta);

    List<OperationalExpense> findByDateBetween(LocalDate desde, LocalDate hasta);
}
