package com.servibox.backend.platform;

import com.servibox.backend.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class PlatformAuthenticationFilter extends OncePerRequestFilter {
    private final PlatformJwtService jwtService;
    private final PlatformAdminRepository repository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims = jwtService.validateAndParse(header.substring(7).trim());
                    repository.findById(Long.parseLong(claims.getSubject()))
                            .filter(a -> a.isActive() && a.getAuthVersion() == ((Number) claims.get("authVersion")).longValue())
                            .ifPresent(a -> SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(a.getId(), null,
                                            List.of(new SimpleGrantedAuthority("PLATFORM_IDENTITY")))));
                } catch (JwtException | IllegalArgumentException | DataAccessException ex) {
                    SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
