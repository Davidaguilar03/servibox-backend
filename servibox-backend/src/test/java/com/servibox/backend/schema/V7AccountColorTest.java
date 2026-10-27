package com.servibox.backend.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V7AccountColorTest {

    private Flyway flyway(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, "sa", "")
                .schemas(schema).defaultSchema(schema).target(target).load();
    }

    @Test
    void esquemaNuevoIncluyeColorNullableDeSieteCaracteres() throws Exception {
        String url = "jdbc:h2:mem:v7-new-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        String schema = "v7_" + UUID.randomUUID().toString().replace("-", "");
        Flyway current = flyway(url, schema, "7");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(7);
        try (Connection connection = current.getConfiguration().getDataSource().getConnection();
             ResultSet columns = connection.getMetaData().getColumns(null, schema, "CUENTAS", "COLOR_CUENTA")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(7);
            assertThat(columns.getInt("NULLABLE")).isEqualTo(1);
        }
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void migracionDesdeV6ConservaCuentaAntiguaSinColor() throws Exception {
        String url = "jdbc:h2:mem:v7-old-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        String schema = "v6_" + UUID.randomUUID().toString().replace("-", "");
        Flyway old = flyway(url, schema, "6");
        assertThat(old.migrate().migrationsExecuted).isEqualTo(6);
        try (Connection connection = old.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            connection.createStatement().executeUpdate("INSERT INTO tenants (id_tenant, nombre_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant) VALUES (1, 'Taller', 'taller', TRUE, CURRENT_TIMESTAMP)");
            connection.createStatement().executeUpdate("INSERT INTO cuentas (id_cuenta, id_tenant, nombre_cuenta, balance_inicial, saldo_actual, tipo_cuenta, created_at, updated_at) VALUES (1, 1, 'Caja', 100, 80, 'EFECTIVO', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        Flyway current = flyway(url, schema, "7");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = current.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            try (ResultSet result = connection.createStatement().executeQuery(
                    "SELECT color_cuenta, balance_inicial, saldo_actual FROM cuentas WHERE id_cuenta = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
                assertThat(result.getBigDecimal(2)).isEqualByComparingTo("100");
                assertThat(result.getBigDecimal(3)).isEqualByComparingTo("80");
            }
        }
    }
}
