package com.servibox.backend.shared;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Forma de busqueda de un texto: sin marcas diacriticas (NFD), en minusculas, con los espacios
 * recortados y colapsados. La ñ queda como n. Se aplica igual a lo guardado y a lo buscado.
 * Si esta regla cambia, los datos existentes necesitan una migracion nueva que los recalcule.
 */
public final class SearchText {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("(?U)\\s+");

    private SearchText() {
    }

    public static String normalize(String texto) {
        String sinMarcas = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return ESPACIOS.matcher(sinMarcas.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
