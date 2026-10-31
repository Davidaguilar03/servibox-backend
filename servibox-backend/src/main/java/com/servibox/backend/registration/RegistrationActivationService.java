package com.servibox.backend.registration;

import com.servibox.backend.auth.DuplicateTenantSlugException;
import com.servibox.backend.auth.TenantRegistrationService;
import com.servibox.backend.auth.dto.RegisterRequest;
import com.servibox.backend.mail.MailOutbox;
import com.servibox.backend.mail.OutboxTemplate;
import com.servibox.backend.registration.dto.ActivationAcknowledgment;
import com.servibox.backend.registration.dto.ActivationBody;
import com.servibox.backend.shared.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Activacion de cuentas (tarea 4B). Aprobar y reenviar emiten un token opaco de 32 bytes,
 * guardan solo su SHA-256 y encolan el correo en la misma transaccion; nada se envia aqui.
 * Activar consume el token y crea tenant y ADMIN con el provisionamiento del registro, en
 * una transaccion con la solicitud bloqueada. Ver 03-DECISIONS.md.
 */
@Service
public class RegistrationActivationService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

    private final RegistrationRequestRepository requests;
    private final RegistrationActivationRepository activations;
    private final TenantRegistrationService provisioning;
    private final MailOutbox outbox;
    private final String baseUrl;
    private final Duration tokenTtl;
    private final Duration resendCooldown;
    private final int resendDailyLimit;
    private final ZoneId zone;
    private final SecureRandom random = new SecureRandom();

    public RegistrationActivationService(
            RegistrationRequestRepository requests,
            RegistrationActivationRepository activations,
            TenantRegistrationService provisioning,
            MailOutbox outbox,
            @Value("${servibox.activation.base-url}") String baseUrl,
            @Value("${servibox.registration-requests.activation-token-ttl}") Duration tokenTtl,
            @Value("${servibox.registration-requests.resend-cooldown}") Duration resendCooldown,
            @Value("${servibox.registration-requests.resend-daily-limit}") int resendDailyLimit,
            @Value("${servibox.business.time-zone}") ZoneId zone) {
        if (!tokenTtl.isPositive() || resendCooldown.isNegative() || resendDailyLimit < 1 || !baseValida(baseUrl)) {
            throw new IllegalStateException("Configuracion de activacion de cuentas invalida");
        }
        this.requests = requests;
        this.activations = activations;
        this.provisioning = provisioning;
        this.outbox = outbox;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenTtl = tokenTtl;
        this.resendCooldown = resendCooldown;
        this.resendDailyLimit = resendDailyLimit;
        this.zone = zone;
    }

    /** http o https con host, sin query ni fragmento. prod exige https (ProductionConfiguration). */
    static boolean baseValida(String url) {
        if (url == null || url.isBlank() || url.length() > 200) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Emite la generacion indicada y encola su correo. Con la solicitud bloqueada, en su transaccion. */
    @Transactional
    public void issue(RegistrationRequest r, int generation, LocalDateTime now) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RegistrationActivation a = new RegistrationActivation();
        a.setRequestId(r.getId());
        a.setGeneration(generation);
        a.setTokenDigest(digest(token));
        a.setCreatedAt(now);
        a.setExpiresAt(now.plus(tokenTtl));
        a = activations.saveAndFlush(a);

        outbox.enqueue(a.getId(), r.getContactEmail(), OutboxTemplate.ACTIVATION,
                cuerpo(r, baseUrl + "/activar#token=" + token, a.getExpiresAt()), now, a.getExpiresAt());
    }

    /** Revoca lo vigente y emite una generacion nueva. No crea tenant ni usuario. */
    @Transactional
    public void resend(String id) {
        RegistrationRequest r = requests.lockById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        LocalDateTime now = now();
        if (r.getStatus() != RegistrationRequestStatus.APPROVED || !r.getReservationExpiresAt().isAfter(now)) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.ESTADO_INVALIDO);
        }
        List<RegistrationActivation> previas = activations.findByRequestIdOrderByGenerationDesc(id);
        if (!previas.isEmpty() && previas.getFirst().getCreatedAt().plus(resendCooldown).isAfter(now)) {
            throw new ResendLimitException(ResendLimitException.ESPERA);
        }
        // Ventana movil de 24 h. La generacion 1 es la de aprobar: no cuenta como reenvio.
        long reenvios = previas.stream()
                .filter(a -> a.getGeneration() > 1 && a.getCreatedAt().isAfter(now.minusDays(1))).count();
        if (reenvios >= resendDailyLimit) {
            throw new ResendLimitException(ResendLimitException.CUPO);
        }
        List<RegistrationActivation> abiertas = previas.stream()
                .filter(a -> a.getConsumedAt() == null && a.getRevokedAt() == null).toList();
        abiertas.forEach(a -> a.setRevokedAt(now));
        activations.saveAllAndFlush(abiertas);
        outbox.cancelPending(abiertas.stream().map(RegistrationActivation::getId).toList(), "REVOCADO");
        issue(r, previas.isEmpty() ? 1 : previas.getFirst().getGeneration() + 1, now);
    }

    /**
     * Bloquea la solicitud y luego lee la activacion (el mismo orden que reenviar). Dos
     * activaciones con el mismo token: la segunda espera el bloqueo y ve el token consumido.
     * Si algo falla no queda nada y el token sigue sirviendo hasta vencer.
     */
    @Transactional
    public ActivationAcknowledgment activate(ActivationBody body) {
        if (body.token() == null || !TOKEN.matcher(body.token()).matches()) {
            throw ActivationException.noValido();
        }
        TenantRegistrationService.validarUsuario(body.username());
        TenantRegistrationService.validarContrasena(body.password());

        String digest = digest(body.token());
        String requestId = activations.findRequestIdByDigest(digest).orElseThrow(ActivationException::noValido);
        RegistrationRequest r = requests.lockById(requestId).orElseThrow(ActivationException::noValido);
        RegistrationActivation a = activations.findByTokenDigest(digest).orElseThrow(ActivationException::noValido);
        LocalDateTime now = now();
        if (a.getConsumedAt() != null) {
            throw ActivationException.usado();
        }
        if (a.getRevokedAt() != null || !a.getExpiresAt().isAfter(now)) {
            throw ActivationException.vencido();
        }
        if (r.getStatus() != RegistrationRequestStatus.APPROVED || !r.getReservationExpiresAt().isAfter(now)) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.ESTADO_INVALIDO);
        }

        TenantRegistrationService.Provisioned creado;
        try {
            creado = provisioning.provisionar(new RegisterRequest(r.getBusinessName(), r.getNit(), r.getPhone(),
                    r.getRequestedSlug(), r.getContactName(), r.getContactEmail(), body.username(), body.password(),
                    Boolean.TRUE));
        } catch (DuplicateTenantSlugException ex) {
            throw new RegistrationRequestConflictException(RegistrationRequestConflictException.SLUG_NO_DISPONIBLE);
        }

        r.setStatus(RegistrationRequestStatus.ACTIVATED);
        r.setReservedSlug(null);
        r.setActivatedTenantId(creado.tenant().getId());
        requests.saveAndFlush(r);
        a.setConsumedAt(now);
        activations.saveAndFlush(a);
        outbox.cancelPending(List.of(a.getId()), "CONSUMIDO");
        return ActivationAcknowledgment.of(r.getRequestedSlug());
    }

    private String cuerpo(RegistrationRequest r, String enlace, LocalDateTime expira) {
        String vence = expira.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).format(FECHA);
        return """
                Hola %s:

                La solicitud de %s para usar ServiBox fue aprobada. Para crear el usuario \
                administrador y su contraseña, abre este enlace:

                %s

                El enlace sirve una sola vez y vence el %s (%s). Si vence, pide a ServiBox que lo reenvíe.

                Si no solicitaste esta cuenta, ignora este mensaje.
                """.formatted(r.getContactName(), r.getBusinessName(), enlace, vence, zone.getId());
    }

    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
