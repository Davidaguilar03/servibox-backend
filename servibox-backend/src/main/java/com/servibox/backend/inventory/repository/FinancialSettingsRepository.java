package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.FinancialSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinancialSettingsRepository extends JpaRepository<FinancialSettings, Long> {

    Optional<FinancialSettings> findByTenantId(Long tenantId);
}
