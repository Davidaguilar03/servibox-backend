package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.OccasionalIncome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OccasionalIncomeRepository extends JpaRepository<OccasionalIncome, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find(). Ver 02-CONVENTIONS.md.
     */
    Optional<OccasionalIncome> findByIdAndTenantId(Long id, Long tenantId);

    List<OccasionalIncome> findByDateBetween(LocalDate desde, LocalDate hasta);
}
