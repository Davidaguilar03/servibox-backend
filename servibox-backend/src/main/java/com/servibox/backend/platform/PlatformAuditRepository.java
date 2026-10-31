package com.servibox.backend.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformAuditRepository extends JpaRepository<PlatformAuditEntry, Long> {

    List<PlatformAuditEntry> findByTenantIdOrderByIdAsc(Long tenantId);
}
