package com.servibox.backend.auth;

import com.servibox.backend.registration.FixedWindowRateLimiter;
import com.servibox.backend.registration.PublicRateLimiter;
import com.servibox.backend.registration.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Proteccion contra fuerza bruta de los dos logins (tenant y plataforma), en memoria y por
 * instancia. Solo cuentan los intentos fallidos (401), con tres cuotas en la misma ventana:
 * por IP + cuenta, por cuenta en cualquier IP y por IP en total. Cada intento reserva una
 * unidad en las tres antes de evaluar la contrasena, asi que intentos simultaneos no pasan
 * del limite; un exito limpia el par IP + cuenta y devuelve las otras dos unidades. Con una
 * cuota llena responde 429 sin evaluar la contrasena. Ver 03-DECISIONS.md y DEPLOYMENT.md.
 */
@Component
public class LoginThrottle {

    public static final String MENSAJE = "Demasiados intentos de inicio de sesion, espera unos minutos e intenta de nuevo";
    private static final int MAX_PART = 150;

    private final boolean enabled;
    private final PublicRateLimiter ipResolver;
    private final FixedWindowRateLimiter byIpAndAccount;
    private final FixedWindowRateLimiter byAccount;
    private final FixedWindowRateLimiter byIp;

    @Autowired
    public LoginThrottle(
            @Value("${servibox.login-throttle.enabled}") boolean enabled,
            @Value("${servibox.login-throttle.window}") Duration window,
            @Value("${servibox.login-throttle.ip-account-limit}") int ipAccountLimit,
            @Value("${servibox.login-throttle.account-limit}") int accountLimit,
            @Value("${servibox.login-throttle.ip-limit}") int ipLimit,
            @Value("${servibox.login-throttle.max-entries}") int maxEntries,
            PublicRateLimiter ipResolver) {
        this(enabled, window, ipAccountLimit, accountLimit, ipLimit, maxEntries, ipResolver, Clock.systemUTC());
    }

    LoginThrottle(boolean enabled, Duration window, int ipAccountLimit, int accountLimit, int ipLimit,
            int maxEntries, PublicRateLimiter ipResolver, Clock clock) {
        this.enabled = enabled;
        this.ipResolver = ipResolver;
        this.byIpAndAccount = new FixedWindowRateLimiter(ipAccountLimit, window, maxEntries, clock);
        this.byAccount = new FixedWindowRateLimiter(accountLimit, window, maxEntries, clock);
        this.byIp = new FixedWindowRateLimiter(ipLimit, window, maxEntries, clock);
    }

    /** Cuenta de tenant: codigo de serviteca + usuario, normalizados. */
    public static String tenantAccount(String tenantSlug, String username) {
        return "t\n" + normalize(tenantSlug) + "\n" + normalize(username);
    }

    /** Cuenta de plataforma: correo normalizado. */
    public static String platformAccount(String email) {
        return "p\n" + normalize(email);
    }

    /** Minusculas, sin espacios y acotado para que la memoria por clave no dependa del cliente. */
    static String normalize(String value) {
        String s = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return s.length() > MAX_PART ? s.substring(0, MAX_PART) : s;
    }

    /**
     * Ejecuta el login si ninguna cuota esta llena. InvalidCredentialsException deja contado
     * el fallo; cualquier otra excepcion (por ejemplo la base caida) no cuenta.
     */
    public <T> T attempt(HttpServletRequest request, String account, Supplier<T> login) {
        if (!enabled) {
            return login.get();
        }
        String ip = ipResolver.clientIp(request);
        String pair = ip + "\n" + account;
        long retryAfter = byIpAndAccount.reserve(pair);
        if (retryAfter > 0) {
            throw new RateLimitExceededException(MENSAJE, retryAfter);
        }
        retryAfter = byAccount.reserve(account);
        if (retryAfter > 0) {
            byIpAndAccount.release(pair);
            throw new RateLimitExceededException(MENSAJE, retryAfter);
        }
        retryAfter = byIp.reserve(ip);
        if (retryAfter > 0) {
            byIpAndAccount.release(pair);
            byAccount.release(account);
            throw new RateLimitExceededException(MENSAJE, retryAfter);
        }
        T result;
        try {
            result = login.get();
        } catch (InvalidCredentialsException e) {
            throw e;
        } catch (RuntimeException e) {
            byIpAndAccount.release(pair);
            byAccount.release(account);
            byIp.release(ip);
            throw e;
        }
        byIpAndAccount.reset(pair);
        byAccount.release(account);
        byIp.release(ip);
        return result;
    }
}
