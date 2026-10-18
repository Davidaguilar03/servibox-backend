package com.servibox.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * El "hoy" de negocio (fecha por defecto de ventas, compras, movimientos, abonos y pagos)
 * en la zona del taller, no en la del servidor. La auditoria (created_at, updated_at) sigue
 * en UTC y no usa este Clock, ver JpaAuditingConfiguration y 03-DECISIONS.md.
 */
@Configuration
public class BusinessClockConfiguration {

    @Bean
    public Clock businessClock(@Value("${servibox.business.time-zone}") ZoneId zona) {
        return Clock.system(zona);
    }
}
