package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.TaxType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxTypeRepository extends JpaRepository<TaxType, Long> {
}
