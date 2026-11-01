package com.servibox.backend.shared;

import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Textos para el log sin datos personales (tarea 52). Los mensajes de excepciones de librerias y
 * de la base pueden traer valores de entrada (en PostgreSQL un duplicado incluye el valor de la
 * columna), asi que se registra la clase, el SQLState y el lugar del codigo, nunca el mensaje.
 */
public final class SafeLog {

    private static final Pattern CORREO = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+)");
    /** Secuencias largas tipo token (Base64 URL, hex): el token de activacion tiene 43. */
    private static final Pattern SECRETO = Pattern.compile("[A-Za-z0-9_-]{32,}");
    private static final int MAX_CAUSAS = 5;
    private static final int MAX_TEXTO = 300;

    private SafeLog() {
    }

    /**
     * Cadena de causas con clase, SQLState si lo hay y el primer marco del codigo propio (o el
     * primero a secas). Ejemplo: "DataIntegrityViolationException at c.s.b.X.m(X.java:10) <- PSQLException[23505]".
     */
    public static String describe(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        for (int i = 0; t != null && i < MAX_CAUSAS; i++, t = t.getCause() == t ? null : t.getCause()) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                sb.append('[').append(sql.getSQLState()).append(']');
            }
            StackTraceElement lugar = lugar(t);
            if (lugar != null) {
                sb.append(" at ").append(lugar);
            }
        }
        return sb.toString();
    }

    /**
     * Para textos de terceros (respuesta SMTP): correos como j***@dominio, secuencias tipo token
     * de 32 o mas caracteres como [oculto], en una linea y recortado a 300 caracteres.
     */
    public static String mask(String texto) {
        if (texto == null) {
            return "";
        }
        String limpio = CORREO.matcher(texto.replaceAll("[\\r\\n]+", " ")).replaceAll("$1***@$2");
        limpio = SECRETO.matcher(limpio).replaceAll("[oculto]");
        return limpio.length() > MAX_TEXTO ? limpio.substring(0, MAX_TEXTO) : limpio;
    }

    private static StackTraceElement lugar(Throwable t) {
        StackTraceElement[] pila = t.getStackTrace();
        for (StackTraceElement e : pila) {
            if (e.getClassName().startsWith("com.servibox.")) {
                return e;
            }
        }
        return pila.length > 0 ? pila[0] : null;
    }
}
