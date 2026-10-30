package com.servibox.backend.config;

import com.servibox.backend.auth.JwtAuthenticationFilter;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformAuthenticationFilter;
import com.servibox.backend.platform.PlatformJwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnWebApplication
    @Order(1)
    public SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, PlatformJwtService jwtService,
            PlatformAdminRepository repository, JsonAuthenticationEntryPoint entryPoint) throws Exception {
        return http.securityMatcher("/api/platform/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/platform/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/platform/auth/me").hasAuthority("PLATFORM_IDENTITY")
                        .requestMatchers(HttpMethod.GET, "/api/platform/registration-requests",
                                "/api/platform/registration-requests/*").hasAuthority("PLATFORM_IDENTITY")
                        .requestMatchers(HttpMethod.POST, "/api/platform/registration-requests/*/approve",
                                "/api/platform/registration-requests/*/reject").hasAuthority("PLATFORM_IDENTITY")
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Acceso denegado\",\"status\":403}");
                        }))
                .addFilterBefore(new PlatformAuthenticationFilter(jwtService, repository), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> tenantFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register",
                                "/api/registration-requests").permitAll()
                        .requestMatchers("/api/tenant", "/api/inventory/**", "/api/treasury/**",
                                "/api/counterparties/**", "/api/sales/**", "/api/purchases/**", "/api/reporting/**")
                        .hasAuthority("TENANT_IDENTITY")
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Acceso denegado\",\"status\":403}");
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
