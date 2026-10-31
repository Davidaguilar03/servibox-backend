package com.servibox.backend.platform;

import com.servibox.backend.auth.JwtAuthenticationFilter;
import com.servibox.backend.auth.JwtService;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformFailureTest {
    private static final String TENANT_KEY = "unit-tenant-key-2026-never-in-production";
    private static final String PLATFORM_KEY = "unit-platform-key-2026-never-in-production";

    @Test
    void falloBasePlataformaNoAutenticaYLimpiaContextos() throws Exception {
        PlatformAdminRepository repository = mock(PlatformAdminRepository.class);
        when(repository.findById(1L)).thenThrow(new DataAccessResourceFailureException("offline"));
        PlatformJwtService jwt = new PlatformJwtService(PLATFORM_KEY, 900000, TENANT_KEY);
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(admin));
        TenantContext.setTenantId(99L);
        new PlatformAuthenticationFilter(jwt, repository).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantContext.getTenantId()).isNull();
        });
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void falloBaseTenantNoAutenticaYLimpiaContextos() throws Exception {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByUsernameAndTenantId("fixture", 2L)).thenThrow(new DataAccessResourceFailureException("offline"));
        JwtService jwt = new JwtService(TENANT_KEY, 900000);
        User user = new User();
        user.setTenantId(2L);
        user.setUsername("fixture");
        user.setRole(Role.ADMIN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(user));
        new JwtAuthenticationFilter(jwt, repository, mock(TenantRepository.class)).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantContext.getTenantId()).isNull();
        });
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rechazaDuracionInvalidaYClaveCompartida() {
        for (long duration : new long[]{0, -1, 86400001}) {
            assertThatThrownBy(() -> new PlatformJwtService(PLATFORM_KEY, duration, TENANT_KEY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PlatformJwtService(TENANT_KEY, 900000, TENANT_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
