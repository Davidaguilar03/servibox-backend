package com.servibox.backend.auth;

import com.servibox.backend.registration.PublicRateLimiter;
import com.servibox.backend.registration.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conteo de LoginThrottle con reloj controlado: limites por par, cuenta e IP, exito y ventana. */
class LoginThrottleUnitTest {

    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T12:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final PublicRateLimiter ips = new PublicRateLimiter(false, "", 100, 1, Duration.ofHours(1), 1, Duration.ofMinutes(1));
    private final AtomicInteger evaluations = new AtomicInteger();

    /** par 3, cuenta 5, IP 8 en 15 minutos. */
    private LoginThrottle throttle(int maxEntries) {
        return new LoginThrottle(true, Duration.ofMinutes(15), 3, 5, 8, maxEntries, ips, clock);
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    private void fail(LoginThrottle t, String ip, String account) {
        assertThatThrownBy(() -> t.attempt(from(ip), account, () -> {
            evaluations.incrementAndGet();
            throw new InvalidCredentialsException();
        })).isInstanceOf(InvalidCredentialsException.class);
    }

    private String ok(LoginThrottle t, String ip, String account) {
        return t.attempt(from(ip), account, () -> {
            evaluations.incrementAndGet();
            return "token";
        });
    }

    private long blocked(LoginThrottle t, String ip, String account) {
        int before = evaluations.get();
        Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> ok(t, ip, account));
        assertThat(e).isInstanceOf(RateLimitExceededException.class).hasMessage(LoginThrottle.MENSAJE);
        assertThat(evaluations.get()).as("bloqueado no evalua la contrasena").isEqualTo(before);
        return ((RateLimitExceededException) e).getRetryAfterSeconds();
    }

    @Test
    void cuentasNormalizadasYAcotadas() {
        assertThat(LoginThrottle.tenantAccount("  Taller-A ", "Ca Jero")).isEqualTo(LoginThrottle.tenantAccount("taller-a", "cajero"));
        assertThat(LoginThrottle.platformAccount(" OPS@Example.invalid ")).isEqualTo(LoginThrottle.platformAccount("ops@example.invalid"));
        assertThat(LoginThrottle.tenantAccount("a", "b")).isNotEqualTo(LoginThrottle.platformAccount("a\nb"));
        assertThat(LoginThrottle.tenantAccount("ab", "c")).isNotEqualTo(LoginThrottle.tenantAccount("a", "bc"));
        assertThat(LoginThrottle.tenantAccount("t", "x".repeat(100_000)).length()).isLessThan(400);
    }

    @Test
    void trasTresFallosDelParBloqueaConRetryAfterYVenceConLaVentana() {
        LoginThrottle t = throttle(100);
        for (int i = 0; i < 3; i++) {
            fail(t, "10.0.0.1", "t\na\nb");
        }
        assertThat(blocked(t, "10.0.0.1", "t\na\nb")).isEqualTo(900);
        clock.advance(Duration.ofMinutes(14).plusSeconds(30));
        assertThat(blocked(t, "10.0.0.1", "t\na\nb")).isEqualTo(30);
        clock.advance(Duration.ofSeconds(30));
        assertThat(ok(t, "10.0.0.1", "t\na\nb")).isEqualTo("token");
    }

    @Test
    void elExitoLimpiaElParPeroNoLaIpYNoCuenta() {
        LoginThrottle t = throttle(100);
        fail(t, "10.0.0.2", "a");
        fail(t, "10.0.0.2", "a");
        ok(t, "10.0.0.2", "a");
        // Par limpio: tres fallos mas antes del bloqueo.
        for (int i = 0; i < 3; i++) {
            fail(t, "10.0.0.2", "a");
        }
        blocked(t, "10.0.0.2", "a");
        // La IP lleva 5 fallos (el exito y el bloqueo no cuentan): 3 mas y se llena.
        fail(t, "10.0.0.2", "b");
        fail(t, "10.0.0.2", "c");
        fail(t, "10.0.0.2", "d");
        blocked(t, "10.0.0.2", "e");
    }

    @Test
    void laCuentaSeBloqueaDesdeCualquierIp() {
        LoginThrottle t = throttle(100);
        for (int i = 1; i <= 5; i++) {
            fail(t, "10.1.0." + i, "victima");
        }
        blocked(t, "10.1.0.99", "victima");
        assertThat(ok(t, "10.1.0.99", "otra")).isEqualTo("token");
    }

    @Test
    void otroErrorNoCuentaYApagadoNoLimita() {
        LoginThrottle t = throttle(100);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> t.attempt(from("10.0.0.3"), "a", () -> {
                throw new IllegalStateException("base caida");
            })).isInstanceOf(IllegalStateException.class);
        }
        fail(t, "10.0.0.3", "a");
        LoginThrottle off = new LoginThrottle(false, Duration.ofMinutes(15), 1, 1, 1, 1, ips, clock);
        for (int i = 0; i < 5; i++) {
            fail(off, "10.0.0.4", "a");
        }
    }

    @Test
    void topeLlenoFallaCerradoParaClavesNuevas() {
        LoginThrottle t = throttle(2);
        fail(t, "10.0.0.5", "a");
        fail(t, "10.0.0.6", "b");
        blocked(t, "10.0.0.7", "c");
        clock.advance(Duration.ofMinutes(15));
        assertThat(ok(t, "10.0.0.7", "c")).isEqualTo("token");
    }

    @Test
    void fallosSimultaneosNoPasanDelLimite() throws Exception {
        LoginThrottle t = throttle(100);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        AtomicInteger limited = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    t.attempt(from("10.0.0.8"), "a", () -> {
                        evaluations.incrementAndGet();
                        sleep();
                        throw new InvalidCredentialsException();
                    });
                } catch (RateLimitExceededException e) {
                    limited.incrementAndGet();
                } catch (InvalidCredentialsException e) {
                    // fallo contado
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : results) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(evaluations.get()).isEqualTo(3);
        assertThat(limited.get()).isEqualTo(37);
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
