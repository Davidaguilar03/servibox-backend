package com.servibox.backend.registration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ventana fija, limpieza, tope de entradas, concurrencia y lectura de la IP, con reloj inyectado. */
class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));

    @Test
    void admiteElLimiteYLuegoDevuelveLosSegundosQueFaltan() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(3, Duration.ofMinutes(15), 100, clock);
        assertThat(l.acquire("a")).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(l.acquire("a")).isZero();
        assertThat(l.acquire("a")).isZero();
        assertThat(l.acquire("a")).isEqualTo(14 * 60);
        clock.advance(Duration.ofMillis(13 * 60 * 1000 + 30_500));
        assertThat(l.acquire("a")).isEqualTo(30); // 29,5 s redondea hacia arriba
        clock.advance(Duration.ofMillis(29_499));
        assertThat(l.acquire("a")).isEqualTo(1);
        clock.advance(Duration.ofMillis(1));
        assertThat(l.acquire("a")).as("la ventana vence justo en su limite").isZero();
    }

    @Test
    void cadaClaveTieneSuCuota() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(1, Duration.ofHours(1), 100, clock);
        assertThat(l.acquire("a")).isZero();
        assertThat(l.acquire("a")).isEqualTo(3600);
        assertThat(l.acquire("b")).isZero();
    }

    @Test
    void lasVentanasVencidasSeBorranSolas() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(5, Duration.ofMinutes(10), 100, clock);
        for (int i = 0; i < 50; i++) {
            l.acquire("ip-" + i);
        }
        assertThat(l.size()).isEqualTo(50);
        clock.advance(Duration.ofMinutes(10));
        l.acquire("otra");
        assertThat(l.size()).isEqualTo(1);
    }

    @Test
    void conElTopeLlenoUnaClaveNuevaSeRechazaYLasExistentesSiguen() {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(5, Duration.ofMinutes(10), 3, clock);
        l.acquire("a");
        l.acquire("b");
        l.acquire("c");
        assertThat(l.acquire("d")).as("falla cerrado").isEqualTo(600);
        assertThat(l.size()).isEqualTo(3);
        assertThat(l.acquire("a")).as("una clave ya registrada conserva su cuota").isZero();
        clock.advance(Duration.ofMinutes(10));
        assertThat(l.acquire("d")).isZero();
        assertThat(l.size()).isEqualTo(1);
    }

    @Test
    void configuracionInvalidaImpideArrancar() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofMinutes(1), 10, clock))
                .hasMessage("Configuracion del limite de tasa invalida");
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, Duration.ZERO, 10, clock))
                .hasMessage("Configuracion del limite de tasa invalida");
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, Duration.ofMinutes(1), 0, clock))
                .hasMessage("Configuracion del limite de tasa invalida");
    }

    @Test
    void muchosHilosSobreLaMismaClaveNoSuperanElLimite() throws Exception {
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(10, Duration.ofMinutes(15), 100, clock);
        AtomicInteger admitidas = new AtomicInteger();
        correrEnParalelo(64, i -> {
            if (l.acquire("misma") == 0) {
                admitidas.incrementAndGet();
            }
        });
        assertThat(admitidas.get()).isEqualTo(10);
    }

    @Test
    void muchasClavesEnParaleloNoDesbordanElTopeMasAllaDeLosHilos() throws Exception {
        int hilos = 32;
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(1, Duration.ofMinutes(15), 100, clock);
        correrEnParalelo(hilos, i -> {
            for (int j = 0; j < 50; j++) {
                l.acquire("h" + i + "-" + j);
            }
        });
        assertThat(l.size()).isLessThanOrEqualTo(100 + hilos);
    }

    @Test
    void sinCabeceraConfiguradaLaIpEsLaDelSocketAunqueLleguenCabecerasFalsas() {
        PublicRateLimiter p = limiter("");
        MockHttpServletRequest r = request("10.0.0.1");
        r.addHeader("X-Forwarded-For", "1.2.3.4");
        r.addHeader("X-Real-IP", "5.6.7.8");
        assertThat(p.clientIp(r)).isEqualTo("10.0.0.1");
    }

    @Test
    void conCabeceraConfiguradaSeTomaLaUltimaEntrada() {
        PublicRateLimiter p = limiter("X-Forwarded-For");
        MockHttpServletRequest r = request("10.0.0.1");
        r.addHeader("X-Forwarded-For", "1.2.3.4, 9.9.9.9");
        assertThat(p.clientIp(r)).isEqualTo("9.9.9.9");

        MockHttpServletRequest dos = request("10.0.0.1");
        dos.addHeader("X-Forwarded-For", "1.2.3.4");
        dos.addHeader("X-Forwarded-For", "2001:db8::1");
        assertThat(p.clientIp(dos)).isEqualTo("2001:db8::1");

        PublicRateLimiter real = limiter("X-Real-IP");
        MockHttpServletRequest tres = request("10.0.0.1");
        tres.addHeader("X-Real-IP", " 8.8.4.4 ");
        assertThat(real.clientIp(tres)).isEqualTo("8.8.4.4");
    }

    @Test
    void cabeceraAusenteVaciaOBasuraVuelveALaIpDelSocket() {
        PublicRateLimiter p = limiter("X-Forwarded-For");
        assertThat(p.clientIp(request("10.0.0.1"))).isEqualTo("10.0.0.1");
        for (String basura : List.of("", "1.2.3.4,", "<script>", "x".repeat(46), "1.2.3.4, unknown")) {
            MockHttpServletRequest r = request("10.0.0.1");
            r.addHeader("X-Forwarded-For", basura);
            assertThat(p.clientIp(r)).as(basura).isEqualTo("10.0.0.1");
        }
    }

    private PublicRateLimiter limiter(String header) {
        return new PublicRateLimiter(true, header, 100, 5, Duration.ofHours(1), 10, Duration.ofMinutes(15), clock);
    }

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/registration-requests");
        r.setRemoteAddr(ip);
        return r;
    }

    interface Tarea {
        void run(int i) throws Exception;
    }

    static void correrEnParalelo(int hilos, Tarea tarea) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                int n = i;
                futuros.add(pool.submit(() -> {
                    salida.await();
                    tarea.run(n);
                    return null;
                }));
            }
            salida.countDown();
            for (Future<?> f : futuros) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
