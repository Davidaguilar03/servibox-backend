package com.servibox.backend.platform;

import com.servibox.backend.shared.DomainValidationException;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.mail.OutboxStatus;
import com.servibox.backend.platform.dto.PlatformMetricsResponse;
import com.servibox.backend.platform.dto.PlatformPage;
import com.servibox.backend.platform.dto.PlatformTenantDetailResponse;
import com.servibox.backend.platform.dto.PlatformTenantResponse;
import com.servibox.backend.platform.dto.PlatformTenantUserResponse;
import com.servibox.backend.platform.dto.TenantSessionsRevokedResponse;
import com.servibox.backend.registration.RegistrationRequestStatus;
import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.tenant.TenantStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gobierno de tenants desde la plataforma. Corre sin TenantContext (la cadena de plataforma
 * lo limpia), asi que el filtro de Hibernate no esta activo: toda consulta sobre USUARIOS
 * lleva el id_tenant explicito.
 */
@Service
@RequiredArgsConstructor
public class PlatformTenantService {

    private static final int MAX_MOTIVO = 500;
    private static final int MAX_BUSQUEDA = 100;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PlatformAuditRepository auditRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public PlatformPage<PlatformTenantResponse> list(TenantStatus status, String q, int page, int size) {
        validarPaginacion(page, size);
        String busqueda = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (busqueda.length() > MAX_BUSQUEDA) {
            throw new DomainValidationException("La busqueda no puede superar 100 caracteres");
        }
        String pattern = "%" + busqueda.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Boolean> actives = status == null ? List.of(true, false) : List.of(status.active());
        Page<Tenant> result = tenantRepository.search(actives, pattern,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new PlatformPage<>(result.getContent().stream().map(PlatformTenantResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PlatformTenantDetailResponse detail(Long id) {
        Tenant tenant = tenantRepository.findById(id).orElseThrow(PlatformTenantService::tenantNoEncontrado);
        return detalle(tenant);
    }

    @Transactional(readOnly = true)
    public PlatformPage<PlatformTenantUserResponse> users(Long tenantId, int page, int size) {
        validarPaginacion(page, size);
        exigirTenant(tenantId);
        Page<User> result = userRepository.findByTenantId(tenantId,
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("id"))));
        return new PlatformPage<>(result.getContent().stream().map(PlatformTenantUserResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Suspende el tenant y sube la version de sesion de todos sus usuarios: los JWT ya
     * emitidos dejan de servir, y no reviven al reactivar.
     */
    @Transactional
    public PlatformTenantDetailResponse suspend(Long id, Long expectedVersion, String reason, Long adminId) {
        String motivo = motivo(reason);
        if (motivo == null) {
            throw new DomainValidationException("El motivo de suspension es obligatorio");
        }
        Tenant tenant = bloquear(id, expectedVersion, TenantStatus.SUSPENDED);
        LocalDateTime now = now();
        tenant.setActive(false);
        tenant.setSuspendedAt(now);
        tenant.setSuspensionReason(motivo);
        tenant.setVersion(tenant.getVersion() + 1);
        tenantRepository.saveAndFlush(tenant);
        userRepository.revokeAllByTenantId(id);
        auditar(PlatformAuditAction.SUSPEND_TENANT, adminId, id, null, motivo, now);
        return detail(id);
    }

    @Transactional
    public PlatformTenantDetailResponse reactivate(Long id, Long expectedVersion, String reason, Long adminId) {
        String motivo = motivo(reason);
        Tenant tenant = bloquear(id, expectedVersion, TenantStatus.ACTIVE);
        tenant.setActive(true);
        tenant.setSuspendedAt(null);
        tenant.setSuspensionReason(null);
        tenant.setVersion(tenant.getVersion() + 1);
        tenantRepository.saveAndFlush(tenant);
        auditar(PlatformAuditAction.REACTIVATE_TENANT, adminId, id, null, motivo, now());
        return detail(id);
    }

    /**
     * Idempotente: repetirla responde igual y deja invalidos los mismos tokens (todo lo
     * emitido antes). Cada llamada sube la version y queda auditada.
     */
    @Transactional
    public PlatformTenantUserResponse revokeUser(Long tenantId, Long userId, String reason, Long adminId) {
        String motivo = motivo(reason);
        exigirTenant(tenantId);
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        userRepository.revokeByIdAndTenantId(userId, tenantId);
        auditar(PlatformAuditAction.REVOKE_USER_SESSIONS, adminId, tenantId, userId, motivo, now());
        return PlatformTenantUserResponse.from(user);
    }

    /** Misma pieza que revokeUser, para todos los usuarios del tenant. Idempotente igual. */
    @Transactional
    public TenantSessionsRevokedResponse revokeTenant(Long tenantId, String reason, Long adminId) {
        String motivo = motivo(reason);
        exigirTenant(tenantId);
        int revocados = userRepository.revokeAllByTenantId(tenantId);
        auditar(PlatformAuditAction.REVOKE_TENANT_SESSIONS, adminId, tenantId, null, motivo, now());
        return new TenantSessionsRevokedResponse(tenantId, revocados);
    }

    @Transactional(readOnly = true)
    public PlatformMetricsResponse metrics() {
        Map<String, Long> tenants = new LinkedHashMap<>();
        tenants.put(TenantStatus.ACTIVE.name(), tenantRepository.countByActive(true));
        tenants.put(TenantStatus.SUSPENDED.name(), tenantRepository.countByActive(false));
        Map<String, Long> users = new LinkedHashMap<>();
        long activos = userRepository.countByActive(true);
        long inactivos = userRepository.countByActive(false);
        users.put("total", activos + inactivos);
        users.put("active", activos);
        users.put("inactive", inactivos);
        Map<String, Long> solicitudes = conteoPorEstado(RegistrationRequestStatus.values(),
                "select r.status, count(r) from RegistrationRequest r group by r.status");
        Map<String, Long> correos = conteoPorEstado(OutboxStatus.values(),
                "select m.status, count(m) from OutboxMessage m group by m.status");
        return new PlatformMetricsResponse(tenants, users, solicitudes, correos);
    }

    private Map<String, Long> conteoPorEstado(Enum<?>[] estados, String jpql) {
        Map<String, Long> conteo = new LinkedHashMap<>();
        for (Enum<?> estado : estados) {
            conteo.put(estado.name(), 0L);
        }
        for (Object[] fila : entityManager.createQuery(jpql, Object[].class).getResultList()) {
            conteo.put(((Enum<?>) fila[0]).name(), (Long) fila[1]);
        }
        return conteo;
    }

    private PlatformTenantDetailResponse detalle(Tenant tenant) {
        List<PlatformTenantDetailResponse.UserCount> conteos = userRepository.countByRoleAndActive(tenant.getId())
                .stream()
                .map(f -> new PlatformTenantDetailResponse.UserCount((Role) f[0], (Boolean) f[1], (Long) f[2]))
                .toList();
        long total = conteos.stream().mapToLong(PlatformTenantDetailResponse.UserCount::count).sum();
        PlatformTenantDetailResponse.OriginRequest origen = entityManager.createQuery(
                        "select new com.servibox.backend.platform.dto.PlatformTenantDetailResponse$OriginRequest("
                                + "r.id, r.status, r.createdAt, r.reviewedAt) from RegistrationRequest r"
                                + " where r.activatedTenantId = :id",
                        PlatformTenantDetailResponse.OriginRequest.class)
                .setParameter("id", tenant.getId())
                .getResultStream().findFirst().orElse(null);
        return new PlatformTenantDetailResponse(PlatformTenantResponse.from(tenant), total, conteos, origen);
    }

    /** Bloqueo pesimista de la fila, luego version y transicion, en ese orden. */
    private Tenant bloquear(Long id, Long expectedVersion, TenantStatus destino) {
        if (expectedVersion == null) {
            throw new DomainValidationException("La version esperada es obligatoria");
        }
        Tenant tenant = tenantRepository.lockById(id).orElseThrow(PlatformTenantService::tenantNoEncontrado);
        if (!tenant.getVersion().equals(expectedVersion)) {
            throw new PlatformTenantConflictException(PlatformTenantConflictException.VERSION_DISTINTA);
        }
        if (!TenantStatus.of(tenant).canTransitionTo(destino)) {
            throw new PlatformTenantConflictException(destino == TenantStatus.SUSPENDED
                    ? PlatformTenantConflictException.YA_SUSPENDIDO : PlatformTenantConflictException.YA_ACTIVO);
        }
        return tenant;
    }

    private void exigirTenant(Long id) {
        if (!tenantRepository.existsById(id)) {
            throw tenantNoEncontrado();
        }
    }

    private void auditar(PlatformAuditAction action, Long adminId, Long tenantId, Long userId, String motivo,
            LocalDateTime fecha) {
        PlatformAuditEntry entry = new PlatformAuditEntry();
        entry.setAction(action);
        entry.setAdminId(adminId);
        entry.setTenantId(tenantId);
        entry.setUserId(userId);
        entry.setReason(motivo);
        entry.setCreatedAt(fecha);
        auditRepository.save(entry);
    }

    /** Recortado; null si viene vacio. Se valida antes de bloquear nada. */
    private static String motivo(String reason) {
        String motivo = reason == null ? "" : reason.trim();
        if (motivo.length() > MAX_MOTIVO) {
            throw new DomainValidationException("El motivo no puede superar 500 caracteres");
        }
        return motivo.isEmpty() ? null : motivo;
    }

    private static void validarPaginacion(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainValidationException("Paginacion invalida");
        }
    }

    private static ResourceNotFoundException tenantNoEncontrado() {
        return new ResourceNotFoundException("Tenant no encontrado");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
