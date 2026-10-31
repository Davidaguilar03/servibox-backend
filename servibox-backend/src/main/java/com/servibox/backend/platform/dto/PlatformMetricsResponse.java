package com.servibox.backend.platform.dto;

import java.util.Map;

/** Solo conteos agregados por estado; sin datos de ningun tenant, usuario o solicitud. */
public record PlatformMetricsResponse(
        Map<String, Long> tenants,
        Map<String, Long> users,
        Map<String, Long> registrationRequests,
        Map<String, Long> outboxMessages) {
}
