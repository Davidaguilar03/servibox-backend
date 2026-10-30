package com.servibox.backend.platform;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PlatformBootstrapService {
    private final PlatformAdminRepository repository;
    private final EntityManager entityManager;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void create(String email, char[] password) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 150 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Email de plataforma invalido");
        }
        String raw = password == null ? "" : new String(password);
        if (raw.length() < 12 || raw.isBlank() || raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("La clave de plataforma requiere al menos 12 caracteres y como maximo 72 bytes UTF-8");
        }
        if (repository.count() != 0) {
            throw new IllegalStateException("El bootstrap de plataforma ya fue ejecutado");
        }
        PlatformAdmin admin = new PlatformAdmin();
        // ID reservado para bootstrap. persist fuerza INSERT y evita reemplazos concurrentes.
        admin.setId(1L);
        admin.setEmail(normalized);
        admin.setPasswordHash(passwordEncoder.encode(raw));
        entityManager.persist(admin);
        entityManager.flush();
    }
}
