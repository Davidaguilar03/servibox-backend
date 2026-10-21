package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.TaxType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaxTypeRepository extends JpaRepository<TaxType, Long> {

    List<TaxType> findByTenantIdOrderByNameAsc(Long tenantId);
}
