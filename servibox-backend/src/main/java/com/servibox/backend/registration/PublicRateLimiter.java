package com.servibox.backend.registration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Limite de tasa por IP de las dos rutas publicas de solicitudes de registro, cada una con
 * su cuota. La IP es la del socket salvo que se configure la cabecera del proxy de
 * confianza; en ese caso se toma la ultima entrada de esa cabecera (la que agrega el proxy),
 * nunca las que pudo mandar el cliente antes. Ver 03-DECISIONS.md y DEPLOYMENT.md.
 */
@Component
public class PublicRateLimiter {

    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f.:]{2,45}");

    private final boolean enabled;
    private final String clientIpHeader;
    private final FixedWindowRateLimiter submissions;
    private final FixedWindowRateLimiter activations;

    @Autowired
    public PublicRateLimiter(
            @Value("${servibox.rate-limit.enabled}") boolean enabled,
            @Value("${servibox.rate-limit.client-ip-header}") String clientIpHeader,
            @Value("${servibox.rate-limit.max-entries}") int maxEntries,
            @Value("${servibox.rate-limit.registration-requests.limit}") int submissionLimit,
            @Value("${servibox.rate-limit.registration-requests.window}") Duration submissionWindow,
            @Value("${servibox.rate-limit.activation.limit}") int activationLimit,
            @Value("${servibox.rate-limit.activation.window}") Duration activationWindow) {
        this(enabled, clientIpHeader, maxEntries, submissionLimit, submissionWindow, activationLimit,
                activationWindow, Clock.systemUTC());
    }

    PublicRateLimiter(boolean enabled, String clientIpHeader, int maxEntries, int submissionLimit,
            Duration submissionWindow, int activationLimit, Duration activationWindow, Clock clock) {
        this.enabled = enabled;
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
        this.submissions = new FixedWindowRateLimiter(submissionLimit, submissionWindow, maxEntries, clock);
        this.activations = new FixedWindowRateLimiter(activationLimit, activationWindow, maxEntries, clock);
    }

    public void checkSubmission(HttpServletRequest request) {
        check(submissions, request, RateLimitExceededException.SOLICITUDES);
    }

    public void checkActivation(HttpServletRequest request) {
        check(activations, request, RateLimitExceededException.ACTIVACION);
    }

    private void check(FixedWindowRateLimiter limiter, HttpServletRequest request, String message) {
        if (!enabled) {
            return;
        }
        long retryAfter = limiter.acquire(clientIp(request));
        if (retryAfter > 0) {
            throw new RateLimitExceededException(message, retryAfter);
        }
    }

    String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (clientIpHeader.isEmpty()) {
            return remote;
        }
        List<String> entries = new ArrayList<>();
        for (String value : Collections.list(request.getHeaders(clientIpHeader))) {
            for (String part : value.split(",", -1)) {
                entries.add(part.trim());
            }
        }
        if (entries.isEmpty()) {
            return remote;
        }
        String last = entries.getLast();
        return IP.matcher(last).matches() ? last : remote;
    }
}
