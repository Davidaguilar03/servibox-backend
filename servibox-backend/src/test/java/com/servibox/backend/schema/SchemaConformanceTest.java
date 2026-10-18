package com.servibox.backend.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.fail;

/**
 * El esquema real de la base de pruebas (el que crea Flyway con db/migration y Hibernate
 * valida) tiene que coincidir con docs/ai-context/04-DATA-MODEL.md: las 14 tablas y ninguna
 * otra (flyway_schema_history no cuenta), sus columnas exactas, tipos, nulabilidad, longitud
 * de los VARCHAR, precision y escala de los NUMERIC, claves primarias, claves foraneas con
 * ON DELETE RESTRICT y restricciones unicas. Se lee el esquema por DatabaseMetaData, sin
 * distinguir mayusculas.
 *
 * Reglas del parseo del diccionario: una columna con UK es unica por si sola, salvo que su
 * descripcion diga que la UK va "con id_tenant" (el diccionario lo escribe "UK compuesta
 * con id_tenant" y, en TERCEROS, "UK con id_tenant"): entonces la unica es (id_tenant,
 * columna). PK en dos columnas de la misma tabla es clave compuesta. Las FK salen de la
 * seccion "Claves foraneas".
 *
 * Los tipos se comparan por familia. El diccionario usa BIGINT, INTEGER, VARCHAR, NUMERIC,
 * DATE, TIMESTAMP y BOOLEAN; del lado de la base las variantes de nombre de esas mismas
 * familias valen igual (CHARACTER VARYING es VARCHAR, INT8 es BIGINT, BOOL es BOOLEAN), y
 * la precision y escala de NUMERIC se comparan aparte. DOUBLE y ENUM no estan en el
 * diccionario y salen como diferencia. Una diferencia de tipo aceptada a proposito iria en
 * DESVIACIONES_CONOCIDAS; desde el paso 6 la lista esta vacia. La prueba falla por una
 * desviacion que no esta en la lista y tambien por una entrada de la lista que ya no ocurre.
 *
 * Cuando falla, lista todas las diferencias juntas.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaConformanceTest {

    private static final Path DICCIONARIO = Path.of("docs", "ai-context", "04-DATA-MODEL.md");

    /** Tabla de control de Flyway: no es del modelo de datos. */
    private static final String HISTORIAL_FLYWAY = "FLYWAY_SCHEMA_HISTORY";

    /** tabla.columna -> desviacion de tipo aceptada. Vacia desde el paso 6 (Flyway). */
    private static final Map<String, Desviacion> DESVIACIONES_CONOCIDAS = new LinkedHashMap<>();

    record Desviacion(String tipoReal, String motivo) {
    }

    // ---- Diccionario ----

    record ColumnaDiccionario(String nombre, String tipo, Integer longitud, Integer escala, boolean nula,
                              boolean pk, boolean uk, boolean ukConTenant) {
    }

    record Fk(String columna, String tablaReferenciada) implements Comparable<Fk> {
        @Override
        public int compareTo(Fk otra) {
            return (columna + ">" + tablaReferenciada).compareTo(otra.columna + ">" + otra.tablaReferenciada);
        }

        @Override
        public String toString() {
            return columna + " -> " + tablaReferenciada;
        }
    }

    private static Map<String, Map<String, ColumnaDiccionario>> tablas;
    private static Map<String, Set<Fk>> fks;

    @BeforeAll
    static void leerDiccionario() throws IOException {
        tablas = new TreeMap<>();
        fks = new TreeMap<>();
        String seccion = "";
        String tabla = null;
        for (String linea : Files.readAllLines(DICCIONARIO, StandardCharsets.UTF_8)) {
            if (linea.startsWith("## ")) {
                seccion = linea.substring(3).trim();
                tabla = null;
                continue;
            }
            if (seccion.equals("Entidades") && linea.startsWith("### ")) {
                tabla = mayus(linea.substring(4));
                tablas.put(tabla, new LinkedHashMap<>());
                continue;
            }
            if (!linea.startsWith("| ") || linea.startsWith("| Campo") || linea.startsWith("| Tabla Dependiente")) {
                continue;
            }
            String[] celdas = linea.split("\\|");
            if (seccion.equals("Entidades") && tabla != null) {
                String nombre = mayus(celdas[1]);
                String tipo = mayus(celdas[2]).replaceAll("\\s*\\(.*\\)", "");
                String clave = mayus(celdas[5]);
                String descripcion = celdas[6].toLowerCase(Locale.ROOT);
                // VARCHAR: longitud. NUMERIC: "precision,escala". Los demas tipos, en bytes: no cuenta.
                Integer longitud = null;
                Integer escala = null;
                if (tipo.equals("VARCHAR")) {
                    longitud = Integer.valueOf(celdas[3].trim());
                } else if (tipo.equals("NUMERIC")) {
                    String[] precisionEscala = celdas[3].trim().split(",");
                    longitud = Integer.valueOf(precisionEscala[0].trim());
                    escala = Integer.valueOf(precisionEscala[1].trim());
                }
                tablas.get(tabla).put(nombre, new ColumnaDiccionario(nombre, tipo, longitud, escala,
                        mayus(celdas[4]).equals("NULL"), clave.contains("PK"), clave.contains("UK"),
                        descripcion.contains("con id_tenant")));
            } else if (seccion.equals("Claves foraneas")) {
                fks.computeIfAbsent(mayus(celdas[1]), t -> new TreeSet<>())
                        .add(new Fk(mayus(celdas[2]), mayus(celdas[3])));
            }
        }
    }

    // ---- Base real ----

    @Autowired
    private DataSource dataSource;

    @Test
    void elEsquemaCoincideConElDiccionario() throws SQLException {
        List<String> diferencias = new ArrayList<>();
        Map<String, String> desviacionesVistas = new TreeMap<>();

        if (tablas.size() != 14) {
            diferencias.add("El diccionario deberia tener 14 tablas de entidades y el parseo encontro "
                    + tablas.size() + ": " + tablas.keySet());
        }

        try (Connection conexion = dataSource.getConnection()) {
            DatabaseMetaData meta = conexion.getMetaData();
            String esquema = conexion.getSchema();

            Set<String> tablasReales = new TreeSet<>();
            try (ResultSet rs = meta.getTables(null, esquema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tablasReales.add(mayus(rs.getString("TABLE_NAME")));
                }
            }
            tablasReales.remove(HISTORIAL_FLYWAY);
            for (String sobra : diferencia(tablasReales, tablas.keySet())) {
                diferencias.add("Tabla que no esta en el diccionario: " + sobra);
            }
            for (String falta : diferencia(tablas.keySet(), tablasReales)) {
                diferencias.add("Tabla del diccionario que no existe: " + falta);
            }

            for (String tabla : tablas.keySet()) {
                if (tablasReales.contains(tabla)) {
                    compararTabla(meta, esquema, tabla, diferencias, desviacionesVistas);
                }
            }
        }

        for (Map.Entry<String, Desviacion> conocida : DESVIACIONES_CONOCIDAS.entrySet()) {
            if (!desviacionesVistas.containsKey(conocida.getKey())) {
                diferencias.add("Desviacion de tipo conocida que ya no ocurre, quitala de la lista: "
                        + conocida.getKey() + " (" + conocida.getValue().tipoReal() + ")");
            }
        }

        System.out.println("SchemaConformanceTest: " + tablas.size() + " tablas comparadas; "
                + desviacionesVistas.size() + " desviaciones de tipo conocidas vigentes"
                + (desviacionesVistas.isEmpty() ? " (lista vacia)" : ":"));
        desviacionesVistas.forEach((columna, detalle) -> System.out.println("  " + columna + ": " + detalle));

        if (!diferencias.isEmpty()) {
            fail(diferencias.size() + " diferencias entre el esquema y 04-DATA-MODEL.md:\n  - "
                    + String.join("\n  - ", diferencias));
        }
    }

    private void compararTabla(DatabaseMetaData meta, String esquema, String tabla, List<String> diferencias,
                               Map<String, String> desviacionesVistas) throws SQLException {
        String nombreReal = nombreReal(meta, esquema, tabla);
        Map<String, ColumnaDiccionario> esperadas = tablas.get(tabla);

        // Columnas: nombre, tipo, nulabilidad y longitud.
        Map<String, String[]> reales = new LinkedHashMap<>();
        try (ResultSet rs = meta.getColumns(null, esquema, nombreReal, "%")) {
            while (rs.next()) {
                reales.put(mayus(rs.getString("COLUMN_NAME")), new String[]{
                        mayus(rs.getString("TYPE_NAME")), rs.getString("COLUMN_SIZE"), rs.getString("IS_NULLABLE"),
                        rs.getString("DECIMAL_DIGITS")});
            }
        }
        for (String sobra : diferencia(reales.keySet(), esperadas.keySet())) {
            diferencias.add(tabla + ": columna que no esta en el diccionario: " + sobra);
        }
        for (String falta : diferencia(esperadas.keySet(), reales.keySet())) {
            diferencias.add(tabla + ": falta la columna " + falta);
        }
        for (ColumnaDiccionario esperada : esperadas.values()) {
            String[] real = reales.get(esperada.nombre());
            if (real == null) {
                continue;
            }
            String donde = tabla + "." + esperada.nombre();
            String tipoReal = familia(real[0]);

            Desviacion conocida = DESVIACIONES_CONOCIDAS.get(clave(tabla, esperada.nombre()));
            if (!tipoReal.equals(esperada.tipo())) {
                if (conocida != null && conocida.tipoReal().equals(tipoReal)) {
                    desviacionesVistas.put(clave(tabla, esperada.nombre()),
                            tipoReal + " contra " + esperada.tipo() + " (" + conocida.motivo() + ")");
                } else {
                    diferencias.add(donde + ": tipo " + real[0] + " (" + tipoReal + ") y el diccionario dice "
                            + esperada.tipo() + "; no esta en DESVIACIONES_CONOCIDAS");
                }
            }

            boolean nulaReal = "YES".equals(real[2]);
            if (nulaReal != esperada.nula()) {
                diferencias.add(donde + ": " + (nulaReal ? "admite" : "no admite") + " nulos y el diccionario dice "
                        + (esperada.nula() ? "NULL" : "NOT NULL"));
            }

            if (esperada.longitud() != null && tipoReal.equals("VARCHAR")
                    && !String.valueOf(esperada.longitud()).equals(real[1])) {
                diferencias.add(donde + ": longitud " + real[1] + " y el diccionario dice " + esperada.longitud());
            }

            if (esperada.escala() != null && tipoReal.equals("NUMERIC")
                    && (!String.valueOf(esperada.longitud()).equals(real[1])
                    || !String.valueOf(esperada.escala()).equals(real[3]))) {
                diferencias.add(donde + ": NUMERIC(" + real[1] + "," + real[3] + ") y el diccionario dice NUMERIC("
                        + esperada.longitud() + "," + esperada.escala() + ")");
            }
        }

        // Clave primaria.
        Set<String> pkEsperada = new TreeSet<>();
        esperadas.values().stream().filter(ColumnaDiccionario::pk).forEach(c -> pkEsperada.add(c.nombre()));
        Set<String> pkReal = new TreeSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, esquema, nombreReal)) {
            while (rs.next()) {
                pkReal.add(mayus(rs.getString("COLUMN_NAME")));
            }
        }
        if (!pkReal.equals(pkEsperada)) {
            diferencias.add(tabla + ": clave primaria " + pkReal + " y el diccionario dice " + pkEsperada);
        }

        // Claves foraneas, todas con ON DELETE RESTRICT.
        Set<Fk> fkEsperadas = fks.getOrDefault(tabla, Set.of());
        Set<Fk> fkReales = new TreeSet<>();
        try (ResultSet rs = meta.getImportedKeys(null, esquema, nombreReal)) {
            while (rs.next()) {
                Fk fk = new Fk(mayus(rs.getString("FKCOLUMN_NAME")), mayus(rs.getString("PKTABLE_NAME")));
                fkReales.add(fk);
                short reglaBorrado = rs.getShort("DELETE_RULE");
                if (reglaBorrado != DatabaseMetaData.importedKeyRestrict) {
                    diferencias.add(tabla + ": la FK " + fk + " no es ON DELETE RESTRICT (DELETE_RULE "
                            + reglaBorrado + ")");
                }
            }
        }
        for (Fk sobra : diferencia(fkReales, fkEsperadas)) {
            diferencias.add(tabla + ": FK que no esta en el diccionario: " + sobra);
        }
        for (Fk falta : diferencia(fkEsperadas, fkReales)) {
            diferencias.add(tabla + ": falta la FK " + falta);
        }

        // Restricciones unicas, como conjuntos de columnas; el indice de la PK no cuenta.
        Set<Set<String>> ukEsperadas = new HashSet<>();
        for (ColumnaDiccionario columna : esperadas.values()) {
            if (columna.uk()) {
                ukEsperadas.add(columna.ukConTenant()
                        ? new TreeSet<>(Set.of("ID_TENANT", columna.nombre()))
                        : new TreeSet<>(Set.of(columna.nombre())));
            }
        }
        Map<String, Set<String>> indices = new HashMap<>();
        try (ResultSet rs = meta.getIndexInfo(null, esquema, nombreReal, true, false)) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME") != null) {
                    indices.computeIfAbsent(rs.getString("INDEX_NAME"), i -> new TreeSet<>())
                            .add(mayus(rs.getString("COLUMN_NAME")));
                }
            }
        }
        Set<Set<String>> ukReales = new HashSet<>(indices.values());
        ukReales.remove(pkReal);
        for (Set<String> sobra : diferencia(ukReales, ukEsperadas)) {
            diferencias.add(tabla + ": restriccion unica que no esta en el diccionario: " + sobra);
        }
        for (Set<String> falta : diferencia(ukEsperadas, ukReales)) {
            diferencias.add(tabla + ": falta la restriccion unica " + falta);
        }
    }

    /**
     * Tipo de la base llevado al vocabulario del diccionario. Ver el comentario de la clase:
     * DOUBLE y ENUM quedan con su nombre para que salgan como diferencias.
     */
    private static String familia(String tipo) {
        if (tipo.startsWith("ENUM")) {
            return "ENUM";  // H2 lo informa con sus valores: ENUM('A', 'B')
        }
        return switch (tipo) {
            case "INTEGER", "INT", "INT4", "SMALLINT" -> "INTEGER";
            case "BIGINT", "INT8", "BIGSERIAL" -> "BIGINT";
            case "CHARACTER VARYING", "VARCHAR" -> "VARCHAR";
            case "NUMERIC", "DECIMAL" -> "NUMERIC";
            case "DOUBLE PRECISION", "DOUBLE", "FLOAT", "FLOAT8", "REAL" -> "DOUBLE";
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> "TIMESTAMP";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            default -> tipo;
        };
    }

    private static String nombreReal(DatabaseMetaData meta, String esquema, String tabla) throws SQLException {
        try (ResultSet rs = meta.getTables(null, esquema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (mayus(rs.getString("TABLE_NAME")).equals(tabla)) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        return tabla;
    }

    private static String clave(String tabla, String columna) {
        return mayus(tabla) + "." + mayus(columna);
    }

    private static String mayus(String texto) {
        return texto.trim().toUpperCase(Locale.ROOT);
    }

    private static <T> Set<T> diferencia(Set<T> a, Set<T> b) {
        Set<T> resultado = new java.util.LinkedHashSet<>(a);
        resultado.removeAll(b);
        return resultado;
    }
}
