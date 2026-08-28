package com.servibox.backend.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resuelve el tenant del request. Por ahora lee la cabecera temporal X-Tenant-Id;
 * cuando exista JWT esta lectura se reemplaza por el claim del token.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(TENANT_HEADER);
            if (header != null && !header.isBlank()) {
                try {
                    TenantContext.setTenantId(Long.valueOf(header.trim()));
                } catch (NumberFormatException ex) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Cabecera X-Tenant-Id invalida");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
