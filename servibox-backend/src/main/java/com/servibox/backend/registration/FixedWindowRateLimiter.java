package com.servibox.backend.registration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ventana fija por clave (IP), en memoria y por instancia. Cada clave admite `limit`
 * peticiones por ventana; la ventana empieza con la primera peticion de la clave.
 * Las ventanas vencidas se borran solas (purga perezosa una vez por ventana y al llegar al
 * tope). Con el tope de claves lleno y sin nada vencido, una clave nueva se rechaza: el
 * limite falla cerrado en vez de dejar crecer la memoria. Ver 03-DECISIONS.md.
 */
final class FixedWindowRateLimiter {

    private record Window(Instant start, int count) {
    }

    private final int limit;
    private final Duration window;
    private final int maxEntries;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile Instant lastPurge = Instant.MIN;

    FixedWindowRateLimiter(int limit, Duration window, int maxEntries, Clock clock) {
        if (limit < 1 || window == null || !window.isPositive() || maxEntries < 1) {
            throw new IllegalStateException("Configuracion del limite de tasa invalida");
        }
        this.limit = limit;
        this.window = window;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** Cuenta una peticion de la clave. Devuelve 0 si se admite, o los segundos de Retry-After. */
    long acquire(String key) {
        Instant now = clock.instant();
        if (!now.isBefore(lastPurge.plus(window))) {
            purge(now);
        }
        if (!windows.containsKey(key) && windows.size() >= maxEntries) {
            purge(now);
            if (windows.size() >= maxEntries) {
                return seconds(window);
            }
        }
        Window w = windows.compute(key, (k, prev) -> prev == null || expired(prev, now)
                ? new Window(now, 1)
                : new Window(prev.start(), Math.min(prev.count() + 1, limit + 1)));
        if (w.count() <= limit) {
            return 0;
        }
        return seconds(Duration.between(now, w.start().plus(window)));
    }

    int size() {
        return windows.size();
    }

    private void purge(Instant now) {
        lastPurge = now;
        // removeIf de la vista de ConcurrentHashMap borra solo si el valor no cambio entretanto.
        windows.values().removeIf(w -> expired(w, now));
    }

    private boolean expired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(window));
    }

    private static long seconds(Duration d) {
        long millis = d.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
