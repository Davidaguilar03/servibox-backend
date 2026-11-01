package com.servibox.backend.shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarea 52. Antes, todo IllegalArgumentException llegaba al cliente con su mensaje; ahora solo
 * DomainValidationException. Esta prueba lee src/main/java y comprueba: (1) cada mensaje del mapa
 * del frontend (servibox-frontend, src/api/errorMessages.ts, revisado el 2026-11-01) que nacia de
 * un IllegalArgumentException se lanza ahora como DomainValidationException con el mismo texto
 * inicial; (2) no queda ningun "new IllegalArgumentException(" fuera del bootstrap por consola y
 * de la configuracion JWT, que nunca responden por HTTP. Asi un IllegalArgumentException nuevo
 * con mensaje para el cliente no puede pasar sin decidir si es de dominio.
 */
class DomainMessagesSourceTest {

    private static final Path MAIN = Path.of("src/main/java/com/servibox/backend");

    /** Inicio literal de cada mensaje mapeado por el frontend que sale de una validacion de argumento. */
    private static final List<String> MAPEADOS = List.of(
            "El codigo del producto admite hasta ",
            "La descripcion del producto admite hasta ",
            "El costo de compra no puede ser negativo",
            "La cantidad no puede ser negativa",
            "El nombre de la categoria admite hasta ",
            "El umbral amarillo no puede ser negativo",
            "El umbral rojo no puede ser negativo",
            "El umbral rojo no puede superar al amarillo",
            "El margen debe estar entre 0 y menos de 100%",
            "Impuesto no encontrado: ",
            "Categoria no encontrada: ",
            "Producto no encontrado: ",
            "Cuenta no encontrada: ",
            "Compra no encontrada: ",
            "Factura no encontrada: ",
            "La busqueda no puede superar 100 caracteres",
            "El motivo de suspension es obligatorio",
            "La version esperada es obligatoria",
            "El motivo no puede superar 500 caracteres",
            "Paginacion invalida",
            "El rango de un reporte no puede pasar de ",
            "El reporte tiene mas de ",
            "El rango necesita fecha desde y fecha hasta",
            "La fecha desde no puede ser posterior a la fecha hasta",
            "El tipo de una cuenta no se puede cambiar",
            "El color debe tener el formato #RRGGBB",
            "El movimiento necesita una cuenta",
            "El monto del movimiento debe ser mayor a cero",
            "El concepto admite hasta ",
            "Las notas admiten hasta ");

    /** Solo consola o arranque, nunca una respuesta HTTP. */
    private static final Set<String> SIN_HTTP = Set.of("PlatformBootstrap.java", "PlatformBootstrapService.java",
            "PlatformJwtService.java");

    private static String fuentes() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(p).replaceAll("\\s+", " ").replace("( \"", "(\"")).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void cadaMensajeMapeadoSeLanzaComoDomainValidationException() throws IOException {
        String todo = fuentes();
        List<String> faltan = new ArrayList<>();
        for (String m : MAPEADOS) {
            if (!todo.contains("new DomainValidationException(\"" + m)) {
                faltan.add(m);
            }
        }
        assertThat(faltan).as("mensajes mapeados que ya no salen como DomainValidationException").isEmpty();
    }

    @Test
    void noQuedanIllegalArgumentExceptionConMensajeEnCodigoQueRespondeHttp() throws IOException {
        List<String> encontrados = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!SIN_HTTP.contains(p.getFileName().toString())
                        && Files.readString(p).contains("new IllegalArgumentException(")) {
                    encontrados.add(p.getFileName().toString());
                }
            }
        }
        assertThat(encontrados).isEmpty();
    }
}
