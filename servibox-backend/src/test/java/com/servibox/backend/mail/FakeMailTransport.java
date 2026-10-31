package com.servibox.backend.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transporte falso para pruebas (se registra con @Import): guarda lo aceptado en memoria,
 * cuenta envios y puede fallar o tardar a pedido. Nunca abre red.
 */
public class FakeMailTransport implements MailTransport {

    public record Sent(String to, String subject, String body) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int failures;
    private volatile boolean permanent;
    private volatile long delayMs;

    @Override
    public void send(String to, String subject, String body) throws MailTransportException {
        calls.incrementAndGet();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (failures > 0) {
                failures--;
                throw new MailTransportException(permanent, new IllegalStateException("fallo simulado"));
            }
            sent.add(new Sent(to, subject, body));
        }
    }

    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    public int calls() {
        return calls.get();
    }

    public synchronized void failNext(int n, boolean permanent) {
        this.failures = n;
        this.permanent = permanent;
    }

    public void delay(long ms) {
        this.delayMs = ms;
    }

    public synchronized void reset() {
        sent.clear();
        calls.set(0);
        failures = 0;
        delayMs = 0;
    }
}
