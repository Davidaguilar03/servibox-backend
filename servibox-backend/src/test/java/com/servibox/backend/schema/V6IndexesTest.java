package com.servibox.backend.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V6IndexesTest {
    private static final Map<String, List<String>> EXPECTED = Map.ofEntries(
            Map.entry("ix_categoria_tenant_nombre", List.of("id_tenant", "nombre_categoria_producto", "id_categoria_producto")),
            Map.entry("ix_impuesto_tenant_nombre", List.of("id_tenant", "nombre_impuesto", "id_impuesto")),
            Map.entry("ix_producto_tenant_descripcion", List.of("id_tenant", "descripcion_producto", "id_producto")),
            Map.entry("ix_tercero_tenant_nombre", List.of("id_tenant", "nombre_razon_social", "id_tercero")),
            Map.entry("ix_producto_categoria", List.of("id_categoria_producto")),
            Map.entry("ix_categoria_impuesto_impuesto", List.of("id_impuesto")),
            Map.entry("ix_venta_tenant_fecha", List.of("id_tenant", "fecha_venta", "id_venta")),
            Map.entry("ix_compra_tenant_fecha", List.of("id_tenant", "fecha_compra", "id_compra")),
            Map.entry("ix_venta_tercero", List.of("id_tercero")),
            Map.entry("ix_compra_tercero", List.of("id_tercero")),
            Map.entry("ix_detalle_venta_documento", List.of("id_venta", "id_tenant", "id_detalle_venta")),
            Map.entry("ix_detalle_compra_documento", List.of("id_compra", "id_tenant", "id_detalle_compra")),
            Map.entry("ix_detalle_venta_producto", List.of("id_producto", "id_tenant")),
            Map.entry("ix_detalle_compra_producto", List.of("id_producto", "id_tenant", "id_compra", "id_detalle_compra")),
            Map.entry("ix_movimiento_tenant_fecha", List.of("id_tenant", "fecha_movimiento", "id_movimiento")),
            Map.entry("ix_movimiento_cuenta_fecha", List.of("id_cuenta", "id_tenant", "fecha_movimiento", "id_movimiento")),
            Map.entry("ix_movimiento_venta", List.of("id_venta", "id_tenant", "id_movimiento")),
            Map.entry("ix_movimiento_compra", List.of("id_compra", "id_tenant", "id_movimiento")));

    /** Con URL externa se usa solo un esquema desechable propio; nunca clean ni DROP DATABASE. */
    private String url() {
        return System.getProperty("servibox.test.postgres-url", "jdbc:h2:mem:v6-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    private Flyway migrate(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, System.getProperty("servibox.test.postgres-user", "sa"), "")
                .schemas(schema).defaultSchema(schema).target(target).load();
    }

    @Test
    void desdeCeroAplicaV1AV6YLosIndicesNoDuplicanPrefijosDeUnicas() throws Exception {
        String url = url();
        String schema = "v6_" + UUID.randomUUID().toString().replace("-", "");
        Flyway flyway = migrate(url, schema, "6");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            verificarIndices(connection, schema);
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void actualizarV5AV6ConservaLosImportesHistoricosYAgregaLosMismosIndices() throws Exception {
        String url = url();
        String schema = "v5_" + UUID.randomUUID().toString().replace("-", "");
        Flyway before = migrate(url, schema, "3");
        assertThat(before.migrate().migrationsExecuted).isEqualTo(3);
        try (Connection connection = before.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            for (String sql : FlywayMigrationTest.DATOS_V3) connection.createStatement().executeUpdate(sql);
        }
        assertThat(migrate(url, schema, "5").migrate().migrationsExecuted).isEqualTo(2);
        try (Connection connection = before.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            connection.createStatement().executeUpdate("UPDATE ventas SET total_venta = 0.07, subtotal_venta = 0.06");
            connection.createStatement().executeUpdate("UPDATE detalle_ventas SET iva_generado_linea = 0.01");
        }
        Flyway after = migrate(url, schema, "6");
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = after.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            verificarIndices(connection, schema);
            try (ResultSet result = connection.createStatement().executeQuery("SELECT total_venta FROM ventas")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBigDecimal(1)).isEqualByComparingTo("0.07");
            }
            try (ResultSet result = connection.createStatement().executeQuery(
                    "SELECT SUM(iva_generado_linea) FROM detalle_ventas")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBigDecimal(1)).isEqualByComparingTo("0.02");
            }
        }
    }

    private void verificarIndices(Connection connection, String schema) throws Exception {
        var metadata = connection.getMetaData();
        String actualSchema = schema;
        Map<String, List<String>> found = new LinkedHashMap<>();
        int tables = 0;
        try (ResultSet table = metadata.getTables(null, actualSchema, "%", new String[]{"TABLE"})) {
            while (table.next()) {
                String name = table.getString("TABLE_NAME");
                if (name.equalsIgnoreCase("flyway_schema_history")) continue;
                tables++;
                Map<String, List<String>> unique = new LinkedHashMap<>();
                Map<String, List<String>> indexes = new LinkedHashMap<>();
                try (ResultSet index = metadata.getIndexInfo(null, actualSchema, name, false, false)) {
                    while (index.next()) {
                        String column = index.getString("COLUMN_NAME");
                        if (column == null) continue;
                        String indexName = index.getString("INDEX_NAME").toLowerCase(Locale.ROOT);
                        var target = index.getBoolean("NON_UNIQUE") ? indexes : unique;
                        target.computeIfAbsent(indexName, ignored -> new ArrayList<>()).add(column.toLowerCase(Locale.ROOT));
                    }
                }
                indexes.forEach((index, columns) -> {
                    if (!index.startsWith("ix_")) return; // H2 agrega ademas indices automaticos para FK.
                    found.put(index, columns);
                    unique.values().forEach(key -> assertThat(columns.size() >= key.size()
                            && columns.subList(0, key.size()).equals(key)).as(index + " duplica una unica").isFalse());
                });
            }
        }
        assertThat(tables).isEqualTo(14);
        assertThat(found).containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }
}
