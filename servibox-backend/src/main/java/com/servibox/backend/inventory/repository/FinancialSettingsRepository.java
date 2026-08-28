package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.FinancialSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialSettingsRepository extends JpaRepository<FinancialSettings, Long> {
}
