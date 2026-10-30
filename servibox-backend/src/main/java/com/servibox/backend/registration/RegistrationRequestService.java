package com.servibox.backend.registration;

import com.servibox.backend.auth.InvalidRegistrationException;
import com.servibox.backend.auth.TenantRegistrationService;
import com.servibox.backend.registration.dto.PlatformRegistrationRequestResponse;
import com.servibox.backend.registration.dto.RegistrationAcknowledgment;
import com.servibox.backend.registration.dto.RegistrationRequestBody;
import com.servibox.backend.registration.dto.RegistrationRequestPage;
import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.tenant.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Solicitudes de registro (tarea 4A): recibir con reserva de slug e idempotencia, y la
 * revision de la plataforma. Aprobar no crea tenant, no envia correo ni da acceso (4B).
 * Las reservas vencidas no se cancelan con un proceso: se liberan cuando otra solicitud pide
 * el mismo slug, y aprobar las rechaza. Ver 03-DECISIONS.md.
 */
@Service
public class RegistrationRequestService {

    private static final Pattern CLAVE = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private final RegistrationRequestRepository repository;
    private final TenantRepository tenantRepository;
    private final Duration pendingReservation;
    private final Duration approvedReservation;
    private final Duration idempotencyRetention;

    public RegistrationRequestService(
            RegistrationRequestRepository repository,
            TenantRepository tenantRepository,
            @Value("${servibox.registration-requests.pending-reservation}") Duration pendingReservation,
            @Value("${servibox.registration-requests.approved-reservation}") Duration approvedReservation,
            @Value("${servibox.registration-requests.idempotency-retention}") Duration idempotencyRetention) {
        if (!pendingReservation.isPositive() || !approvedReservation.isPositive() || !idempotencyRetention.isPositive()) {
            throw new IllegalStateException("Plazos de solicitudes de registro invalidos");
        }
        this.repository = repository;
        this.tenantRepository = tenantRepository;
        this.pendingReservation = pendingReservation;
        this.approvedReservation = approvedReservation;
        this.idempotencyRetention = idempotencyRetention;
    }

    @Transactional
    public RegistrationAcknowledgment submit(String key, RegistrationRequestBody body) {
        String hash = validar(key, body);
        LocalDateTime now = now();

        Optional<RegistrationRequest> previa = repository.findByIdempotencyKey(key);
        if (previa.isPresent()) {
            if (previa.get().getIdempotencyExpiresAt().isAfter(now)) {
                return repetir(previa.get(), hash);
            }
            // Clave vencida: se libera y la peticion cuenta como nueva.
            previa.get().setIdempotencyKey(null);
            repository.saveAndFlush(previa.get());
        }

        String slug = normalizarSlug(body.slug());
        if (tenantRepository.existsBySlugIgnoreCase(slug)) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.SLUG_NO_DISPONIBLE);
        }
        repository.lockByReservedSlug(slug).ifPresent(reserva -> {
            if (reserva.getReservationExpiresAt().isAfter(now)) {
                throw new RegistrationRequestConflictException(RegistrationRequestConflictException.SLUG_NO_DISPONIBLE);
            }
            reserva.setStatus(RegistrationRequestStatus.CANCELLED);
            reserva.setReservedSlug(null);
            repository.saveAndFlush(reserva);
        });

        RegistrationRequest r = new RegistrationRequest();
        r.setId(UUID.randomUUID().toString());
        r.setBusinessName(body.businessName().trim());
        r.setNit(body.nit().trim());
        r.setPhone(body.phone().trim());
        r.setContactName(body.adminFullName().trim());
        r.setContactEmail(body.adminEmail().trim());
        r.setNormalizedEmail(body.adminEmail().trim().toLowerCase(Locale.ROOT));
        r.setRequestedSlug(slug);
        r.setReservedSlug(slug);
        r.setStatus(RegistrationRequestStatus.PENDING);
        r.setIdempotencyKey(key);
        r.setBodyHash(hash);
        r.setIdempotencyExpiresAt(now.plus(idempotencyRetention));
        r.setCreatedAt(now);
        r.setReservationExpiresAt(now.plus(pendingReservation));
        // Dos solicitudes simultaneas con el mismo slug o la misma clave chocan aqui con
        // uk_solicitud_slug_reservado o uk_solicitud_clave_idempotencia; ver afterConflict.
        repository.saveAndFlush(r);
        return RegistrationAcknowledgment.of(r.getId());
    }

    /**
     * Despues de que submit choco con una restriccion unica y su transaccion se deshizo: si
     * la otra solicitud uso la misma clave, se responde como un reintento; si no, el slug
     * ya no esta disponible.
     */
    @Transactional(readOnly = true)
    public RegistrationAcknowledgment afterConflict(String key, RegistrationRequestBody body) {
        String hash = validar(key, body);
        return repository.findByIdempotencyKey(key)
                .filter(r -> r.getIdempotencyExpiresAt().isAfter(now()))
                .map(r -> repetir(r, hash))
                .orElseThrow(() -> new RegistrationRequestConflictException(
                        RegistrationRequestConflictException.SLUG_NO_DISPONIBLE));
    }

    @Transactional(readOnly = true)
    public RegistrationRequestPage list(RegistrationRequestStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRegistrationException("Paginacion invalida");
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<RegistrationRequest> result = status == null
                ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
        LocalDateTime now = now();
        return new RegistrationRequestPage(
                result.getContent().stream().map(r -> PlatformRegistrationRequestResponse.from(r, now, null)).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PlatformRegistrationRequestResponse detail(String id) {
        return detalle(repository.findById(id).orElseThrow(RegistrationRequestService::noEncontrada));
    }

    @Transactional
    public PlatformRegistrationRequestResponse approve(String id, Long expectedVersion, Long adminId) {
        RegistrationRequest r = bloquear(id, expectedVersion);
        if (esReintento(r, RegistrationRequestStatus.APPROVED, expectedVersion, adminId)) {
            return detalle(r);
        }
        LocalDateTime now = now();
        exigirVersionYPendiente(r, expectedVersion);
        if (!r.getReservationExpiresAt().isAfter(now)) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.ESTADO_INVALIDO);
        }
        if (tenantRepository.existsBySlugIgnoreCase(r.getRequestedSlug())) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.SLUG_NO_DISPONIBLE);
        }
        r.setStatus(RegistrationRequestStatus.APPROVED);
        r.setReviewedAt(now);
        r.setReviewerId(adminId);
        r.setReservationExpiresAt(now.plus(approvedReservation));
        repository.saveAndFlush(r);
        return detalle(r);
    }

    /** Solo desde PENDING, aunque su reserva haya vencido. Libera el slug. */
    @Transactional
    public PlatformRegistrationRequestResponse reject(String id, Long expectedVersion, String reason, Long adminId) {
        String motivo = reason == null ? "" : reason.trim();
        if (motivo.isEmpty()) {
            throw new InvalidRegistrationException("El motivo de rechazo es obligatorio");
        }
        if (motivo.length() > 500) {
            throw new InvalidRegistrationException("El motivo de rechazo no puede superar 500 caracteres");
        }
        RegistrationRequest r = bloquear(id, expectedVersion);
        if (esReintento(r, RegistrationRequestStatus.REJECTED, expectedVersion, adminId)
                && motivo.equals(r.getRejectionReason())) {
            return detalle(r);
        }
        exigirVersionYPendiente(r, expectedVersion);
        r.setStatus(RegistrationRequestStatus.REJECTED);
        r.setReservedSlug(null);
        r.setReviewedAt(now());
        r.setReviewerId(adminId);
        r.setRejectionReason(motivo);
        repository.saveAndFlush(r);
        return detalle(r);
    }

    private RegistrationRequest bloquear(String id, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new InvalidRegistrationException("La version esperada es obligatoria");
        }
        return repository.lockById(id).orElseThrow(RegistrationRequestService::noEncontrada);
    }

    /** La misma decision ya aplicada por el mismo administrador desde esa version: no se repite. */
    private static boolean esReintento(RegistrationRequest r, RegistrationRequestStatus destino,
            Long expectedVersion, Long adminId) {
        return r.getStatus() == destino && r.getVersion() == expectedVersion + 1 && adminId.equals(r.getReviewerId());
    }

    private static void exigirVersionYPendiente(RegistrationRequest r, Long expectedVersion) {
        if (!r.getVersion().equals(expectedVersion)) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.VERSION_DISTINTA);
        }
        if (r.getStatus() != RegistrationRequestStatus.PENDING) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.ESTADO_INVALIDO);
        }
    }

    private PlatformRegistrationRequestResponse detalle(RegistrationRequest r) {
        return PlatformRegistrationRequestResponse.from(r, now(), new PlatformRegistrationRequestResponse.Matches(
                repository.countByNitAndIdNot(r.getNit(), r.getId()),
                repository.countByNormalizedEmailAndIdNot(r.getNormalizedEmail(), r.getId()),
                tenantRepository.countByNit(r.getNit())));
    }

    private static RegistrationAcknowledgment repetir(RegistrationRequest r, String hash) {
        if (!r.getBodyHash().equals(hash)) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.CLAVE_REUTILIZADA);
        }
        return RegistrationAcknowledgment.of(r.getId());
    }

    /** Las reglas del registro actual, sin usuario ni contrasena. Devuelve el hash del cuerpo. */
    private static String validar(String key, RegistrationRequestBody body) {
        if (key == null || key.isBlank()) {
            throw new InvalidRegistrationException("La clave de reintento es obligatoria");
        }
        if (!CLAVE.matcher(key).matches()) {
            throw new InvalidRegistrationException("La clave de reintento no es valida");
        }
        if (body.password() != null) {
            throw new InvalidRegistrationException("La solicitud no admite contrasena");
        }
        if (!Boolean.TRUE.equals(body.acceptTerms())) {
            throw new InvalidRegistrationException("Debe aceptar los terminos y condiciones");
        }
        TenantRegistrationService.validarNombreComercial(body.businessName());
        TenantRegistrationService.validarNit(body.nit());
        TenantRegistrationService.validarTelefono(body.phone());
        TenantRegistrationService.validarSlug(normalizarSlug(body.slug()));
        TenantRegistrationService.validarResponsable(body.adminFullName(), body.adminEmail());
        return hash(body);
    }

    private static String normalizarSlug(String slug) {
        return slug == null ? null : slug.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256 del cuerpo recibido, campo por campo y sin normalizar. */
    private static String hash(RegistrationRequestBody b) {
        StringBuilder canonico = new StringBuilder();
        for (Object campo : new Object[]{b.businessName(), b.nit(), b.phone(), b.slug(), b.adminFullName(),
                b.adminEmail(), b.acceptTerms()}) {
            canonico.append(campo == null ? "\u0001" : campo.toString()).append('\u0000');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonico.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ResourceNotFoundException noEncontrada() {
        return new ResourceNotFoundException("Solicitud no encontrada");
    }

    /** En microsegundos, la precision de TIMESTAMP: la respuesta coincide con lo que se relee de la base. */
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
