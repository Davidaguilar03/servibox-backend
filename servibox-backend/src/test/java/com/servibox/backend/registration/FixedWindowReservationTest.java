package com.servibox.backend.registration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** reserve, release y reset de la ventana fija (uso de los logins), con reloj controlado. */
class FixedWindowReservationTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));

    @Test
    void reservaHastaElLimiteYLuegoBloqueaSinContar() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(3, Duration.ofMinutes(15), 100, clock);
        for (int i = 0; i < 3; i++) {
            assertThat(l.reserve("k")).isZero();
        }
        assertThat(l.reserve("k")).isEqualTo(900);
        clock.advance(Duration.ofMinutes(10));
        assertThat(l.reserve("k")).isEqualTo(300);
        // El rechazo no cuenta: devolver una unidad deja entrar exactamente una mas.
        l.release("k");
        assertThat(l.reserve("k")).isZero();
        assertThat(l.reserve("k")).isEqualTo(300);
    }

    @Test
    void laVentanaVencidaEmpiezaDeCero() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(2, Duration.ofMinutes(15), 100, clock);
        l.reserve("k");
        l.reserve("k");
        clock.advance(Duration.ofMinutes(15));
        assertThat(l.reserve("k")).isZero();
        assertThat(l.reserve("k")).isZero();
        assertThat(l.reserve("k")).isPositive();
    }

    @Test
    void releaseBorraLaClaveEnCeroYResetLaLimpia() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(2, Duration.ofMinutes(15), 100, clock);
        l.reserve("a");
        l.release("a");
        assertThat(l.size()).isZero();
        l.release("no-existe");
        assertThat(l.size()).isZero();
        l.reserve("b");
        l.reserve("b");
        assertThat(l.reserve("b")).isPositive();
        l.reset("b");
        assertThat(l.size()).isZero();
        assertThat(l.reserve("b")).isZero();
    }

    @Test
    void topeLlenoFallaCerradoParaClavesNuevasYSeRecupera() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(5, Duration.ofMinutes(15), 2, clock);
        l.reserve("a");
        l.reserve("b");
        assertThat(l.reserve("c")).isEqualTo(900);
        assertThat(l.reserve("a")).as("una clave registrada conserva su cuota").isZero();
        assertThat(l.size()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(15));
        assertThat(l.reserve("c")).isZero();
        assertThat(l.size()).as("la purga borra las vencidas").isEqualTo(1);
    }

    @Test
    void reservasSimultaneasNoPasanDelLimite() throws Exception {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(5, Duration.ofMinutes(15), 100, clock);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return l.reserve("k");
            }));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Long> f : results) {
            if (f.get(10, TimeUnit.SECONDS) == 0) {
                admitted++;
            }
        }
        pool.shutdown();
        assertThat(admitted).isEqualTo(5);
    }
}
