package com.servibox.backend.auth;

import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reemplaza a la antigua cabecera X-Tenant-Id: el tenant ahora sale de un claim firmado
 * del JWT, asi el cliente no puede elegir a que tenant entra.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                autenticar(token);
            }
            chain.doFilter(request, response);
        } finally {
            // El pool de hilos del servidor se reutiliza entre requests: un tenant que
            // quede pegado aqui se filtraria al siguiente request atendido por este hilo.
            TenantContext.clear();
        }
    }

    private void autenticar(String token) {
        try {
            Claims claims = jwtService.validateAndParse(token);
            Long tenantId = jwtService.extractTenantId(claims);
            String username = jwtService.extractUsername(claims);

            // El tenant va antes de la consulta: asi el filtro de Hibernate ya esta
            // activo y la busqueda del usuario no puede cruzar de tenant.
            TenantContext.setTenantId(tenantId);

            // La firma del token solo prueba que se emitio; no dice nada del estado
            // actual del usuario. Sin esta consulta, desactivar a alguien no surtiria
            // efecto hasta que su token expirara.
            Optional<User> user = userRepository.findByUsernameAndTenantId(username, tenantId);
            if (user.isEmpty() || !user.get().isActive()) {
                rechazar();
                return;
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.get().getRole().name()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            // Token invalido o vencido: se sigue sin autenticar y la cadena de seguridad
            // responde 401 en la ruta protegida.
            rechazar();
        }
    }

    private void rechazar() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
