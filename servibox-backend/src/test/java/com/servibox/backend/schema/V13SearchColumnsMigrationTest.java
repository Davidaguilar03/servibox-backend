package com.servibox.backend.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13 sobre una base en V12 con datos: rellena las columnas de busqueda con la misma regla de la
 * aplicacion, no toca los originales y deja las columnas NOT NULL. Por defecto corre en una H2
 * nueva; con -Dservibox.migration-test.url (y .user/.password) sobre una base externa VACIA, por
 * ejemplo una PostgreSQL 16 recien creada.
 */
class V13SearchColumnsMigrationTest {

    private static final String URL = System.getProperty("servibox.migration-test.url",
            "jdbc:h2:mem:flyway-v13-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    private static final String USER = System.getProperty("servibox.migration-test.user", "sa");
    private static final String PASSWORD = System.getProperty("servibox.migration-test.password", "");
    private static final String NOMBRE_LARGO = "한".repeat(150);

    private final SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, USER, PASSWORD, true);

    @AfterEach
    void cerrar() {
        dataSource.destroy();
    }

    @Test
    void v13RellenaLasFilasExistentesYQuedaObligatoria() throws SQLException {
        assertThat(flyway("12").migrate().targetSchemaVersion).isEqualTo("12");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            sembrar(s);
        }

        MigrateResult resultado = flyway("13").migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThat(columna(s, "SELECT nombre_tenant FROM tenants ORDER BY slug_tenant")).containsExactly(
                    "Baterías  Niña", "Camión Rápido", "SERVITECA 100% ÑANDÚ", NOMBRE_LARGO);
            assertThat(columna(s, "SELECT nombre_busqueda_tenant FROM tenants ORDER BY slug_tenant")).containsExactly(
                    "baterias nina", "camion rapido", "serviteca 100% nandu",
                    Normalizer.normalize(NOMBRE_LARGO, Normalizer.Form.NFD));
            assertThat(columna(s, "SELECT nombre_comercial FROM solicitudes_registro")).containsExactly("Niño  Feliz");
            assertThat(columna(s, "SELECT nombre_comercial_busqueda FROM solicitudes_registro"))
                    .containsExactly("nino feliz");
            assertThat(columna(s, "SELECT nombre_responsable FROM solicitudes_registro"))
                    .containsExactly("MARÍA Gómez");
            assertThat(columna(s, "SELECT nombre_responsable_busqueda FROM solicitudes_registro"))
                    .containsExactly("maria gomez");
            // El LIKE de la aplicacion encuentra las filas viejas.
            assertThat(columna(s, "SELECT slug_tenant FROM tenants WHERE nombre_busqueda_tenant LIKE '%camion%'"))
                    .containsExactly("b-camion");
            assertThat(columna(s, "SELECT slug_tenant FROM tenants WHERE nombre_busqueda_tenant LIKE '%100\\%%' "
                    + "ESCAPE '\\'")).containsExactly("c-serviteca");

            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, "
                    + "fecha_creacion_tenant) VALUES ('Sin columna', 'sin-columna', TRUE, CURRENT_TIMESTAMP)"))
                    .as("nombre_busqueda_tenant es obligatoria").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE solicitudes_registro SET nombre_responsable_busqueda = NULL"))
                    .isInstanceOf(SQLException.class);
        }
    }

    /** Sobre una base H2 de archivo reabierta (dev con datos), no solo en la sesion que creo los CHECK. */
    @Test
    void v13AplicaEnH2DeArchivoReabierta(@TempDir Path carpeta) throws SQLException {
        String url = "jdbc:h2:file:" + carpeta.resolve("v13").toAbsolutePath() + ";MODE=PostgreSQL";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            Flyway.configure().dataSource(new SingleConnectionDataSource(c, true))
                    .locations("classpath:db/migration").target("12").load().migrate();
            sembrar(s);
            s.execute("SHUTDOWN");
        }
        MigrateResult resultado = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("13").load().migrate();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            assertThat(columna(s, "SELECT nombre_comercial_busqueda FROM solicitudes_registro"))
                    .containsExactly("nino feliz");
            s.execute("SHUTDOWN");
        }
    }

    private static void sembrar(Statement s) throws SQLException {
        for (String[] t : new String[][]{{"Baterías  Niña", "a-baterias"}, {"Camión Rápido", "b-camion"},
                {"SERVITECA 100% ÑANDÚ", "c-serviteca"}, {NOMBRE_LARGO, "d-largo"}}) {
            s.executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant) "
                    + "VALUES ('" + t[0] + "', '" + t[1] + "', TRUE, CURRENT_TIMESTAMP)");
        }
        s.executeUpdate("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono, "
                + "nombre_responsable, correo_responsable, correo_normalizado, slug_solicitado, slug_reservado, "
                + "estado, hash_cuerpo, idempotencia_expira_en, fecha_creacion, reserva_expira_en) VALUES ("
                + "'11111111-2222-3333-4444-555555555555', 'Niño  Feliz', '900123456-7', '3001234567', 'MARÍA Gómez', "
                + "'m@example.invalid', 'm@example.invalid', 'nino-feliz', 'nino-feliz', 'PENDING', '"
                + "0".repeat(64) + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private static List<String> columna(Statement s, String sql) throws SQLException {
        List<String> valores = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                valores.add(rs.getString(1));
            }
        }
        return valores;
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }
}
