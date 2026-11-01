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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 sobre una base en V13 con solicitudes: las filas siguen intactas, con consentimiento nulo, y
 * se pueden revisar. Por defecto en una H2 nueva; con -Dservibox.migration-test.url (y .user/.password)
 * sobre una base externa VACIA, por ejemplo una PostgreSQL 16 recien creada.
 */
class V14RegistrationConsentMigrationTest {

    private static final String URL = System.getProperty("servibox.migration-test.url",
            "jdbc:h2:mem:flyway-v14-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    private static final String USER = System.getProperty("servibox.migration-test.user", "sa");
    private static final String PASSWORD = System.getProperty("servibox.migration-test.password", "");
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    private final SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, USER, PASSWORD, true);

    @AfterEach
    void cerrar() {
        dataSource.destroy();
    }

    @Test
    void v14ConservaLasFilasConNulosYExigeElParCompleto() throws SQLException {
        assertThat(flyway(dataSource, "13").migrate().targetSchemaVersion).isEqualTo("13");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            sembrar(s);
        }

        MigrateResult resultado = flyway(dataSource, "14").migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            comprobarHistorica(s);

            // Se puede revisar: pasar a rechazada no exige consentimiento.
            assertThat(s.executeUpdate("UPDATE solicitudes_registro SET estado = 'REJECTED', slug_reservado = NULL, "
                    + "motivo_rechazo = 'Prueba', version = version + 1 WHERE id_solicitud = '" + ID + "'")).isEqualTo(1);

            assertThatThrownBy(() -> s.executeUpdate("UPDATE solicitudes_registro SET version_politica = '2026-11-01'"))
                    .as("version sin fecha").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate(
                    "UPDATE solicitudes_registro SET fecha_aceptacion_politica = CURRENT_TIMESTAMP"))
                    .as("fecha sin version").isInstanceOf(SQLException.class);
            assertThat(s.executeUpdate("UPDATE solicitudes_registro SET version_politica = '2026-11-01', "
                    + "fecha_aceptacion_politica = CURRENT_TIMESTAMP")).isEqualTo(1);
        }
    }

    /** Sobre una base H2 de archivo reabierta (dev con datos). */
    @Test
    void v14AplicaEnH2DeArchivoReabierta(@TempDir Path carpeta) throws SQLException {
        String url = "jdbc:h2:file:" + carpeta.resolve("v14").toAbsolutePath() + ";MODE=PostgreSQL";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            flyway(new SingleConnectionDataSource(c, true), "13").migrate();
            sembrar(s);
            s.execute("SHUTDOWN");
        }
        MigrateResult resultado = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("14").load().migrate();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            comprobarHistorica(s);
            s.execute("SHUTDOWN");
        }
    }

    private static void sembrar(Statement s) throws SQLException {
        s.executeUpdate("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nombre_comercial_busqueda, "
                + "nit, telefono, nombre_responsable, nombre_responsable_busqueda, correo_responsable, correo_normalizado, "
                + "slug_solicitado, slug_reservado, estado, hash_cuerpo, idempotencia_expira_en, fecha_creacion, "
                + "reserva_expira_en) VALUES ('" + ID + "', 'Historica', 'historica', '900123456-7', '3001234567', 'Ana', "
                + "'ana', 'a@example.invalid', 'a@example.invalid', 'historica', 'historica', 'PENDING', '"
                + "0".repeat(64) + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private static void comprobarHistorica(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT nombre_comercial, correo_responsable, estado, version_politica, "
                + "fecha_aceptacion_politica FROM solicitudes_registro")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Historica");
            assertThat(rs.getString(2)).isEqualTo("a@example.invalid");
            assertThat(rs.getString(3)).isEqualTo("PENDING");
            assertThat(rs.getString(4)).isNull();
            assertThat(rs.getTimestamp(5)).isNull();
            assertThat(rs.next()).isFalse();
        }
    }

    private static Flyway flyway(javax.sql.DataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }
}
