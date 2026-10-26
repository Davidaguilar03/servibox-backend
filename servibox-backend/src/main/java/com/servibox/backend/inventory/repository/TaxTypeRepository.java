package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.TaxType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TaxTypeRepository extends JpaRepository<TaxType, Long> {

    @Query("SELECT t FROM TaxType t WHERE t.tenantId = :tenantId ORDER BY t.name, t.id")
    List<TaxType> findByTenantIdOrderByNameAsc(Long tenantId);
}
