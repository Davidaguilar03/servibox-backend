package com.servibox.backend.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12 sobre una base en V11 que ya tiene tenants, usuarios, administrador, solicitud y filas de
 * auditoria de V11: las conserva intactas, admite RESEND_ACTIVATION con solicitud y sin tenant,
 * y su CHECK rechaza objetivos mezclados. Por defecto corre en una H2 nueva; con
 * -Dservibox.migration-test.url (y .user/.password) sobre una base externa VACIA, por ejemplo
 * una PostgreSQL 16 recien creada. Una sola conexion, por lo mismo que V11PlatformGovernanceMigrationTest.
 */
class V12ResendAuditMigrationTest {

    private static final String URL = System.getProperty("servibox.migration-test.url",
            "jdbc:h2:mem:flyway-v12-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    private static final String USER = System.getProperty("servibox.migration-test.user", "sa");
    private static final String PASSWORD = System.getProperty("servibox.migration-test.password", "");
    private static final String SOLICITUD = "11111111-2222-3333-4444-555555555555";

    private final SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, USER, PASSWORD, true);

    @AfterEach
    void cerrar() {
        dataSource.destroy();
    }

    @Test
    void v12AplicaSobreV11ConAuditoriaExistente() throws SQLException {
        assertThat(flyway("11").migrate().targetSchemaVersion).isEqualTo("11");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant, "
                    + "version_tenant, fecha_suspension_tenant, motivo_suspension_tenant) "
                    + "VALUES ('Suspendido V11', 'suspendido-v11', FALSE, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 'Mora')");
            s.executeUpdate("INSERT INTO usuarios (id_tenant, username, password_hash, activo, rol, created_at, "
                    + "updated_at, auth_version) SELECT id_tenant, 'admin', 'hash', TRUE, 'ADMIN', CURRENT_TIMESTAMP, "
                    + "CURRENT_TIMESTAMP, 2 FROM tenants");
            s.executeUpdate("INSERT INTO administradores_plataforma (id_administrador, email, password_hash) "
                    + "VALUES (1, 'v12@example.invalid', 'hash')");
            s.executeUpdate("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono, "
                    + "nombre_responsable, correo_responsable, correo_normalizado, slug_solicitado, slug_reservado, "
                    + "estado, hash_cuerpo, idempotencia_expira_en, fecha_creacion, reserva_expira_en, fecha_revision, "
                    + "id_revisor) VALUES ('" + SOLICITUD + "', 'Taller V12', '900123456-7', '3001234567', 'Ana', "
                    + "'ana@example.invalid', 'ana@example.invalid', 'taller-v12', 'taller-v12', 'APPROVED', '"
                    + "0".repeat(64) + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)");
            s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, id_tenant_objetivo, "
                    + "id_usuario_objetivo, motivo, fecha_auditoria) SELECT 'SUSPEND_TENANT', 1, id_tenant, NULL, 'Mora', "
                    + "CURRENT_TIMESTAMP FROM tenants");
            s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, id_tenant_objetivo, "
                    + "id_usuario_objetivo, motivo, fecha_auditoria) SELECT 'REVOKE_USER_SESSIONS', 1, id_tenant, "
                    + "id_usuario, NULL, CURRENT_TIMESTAMP FROM usuarios");
        }

        MigrateResult resultado = flyway("12").migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        assertThat(resultado.targetSchemaVersion).isEqualTo("12");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT accion, id_tenant_objetivo, id_usuario_objetivo, motivo, "
                    + "id_solicitud_objetivo FROM auditoria_plataforma ORDER BY id_auditoria")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("SUSPEND_TENANT");
                assertThat(rs.getLong(2)).isPositive();
                assertThat(rs.getObject(3)).isNull();
                assertThat(rs.getString(4)).isEqualTo("Mora");
                assertThat(rs.getObject(5)).isNull();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("REVOKE_USER_SESSIONS");
                assertThat(rs.getLong(3)).isPositive();
                assertThat(rs.getObject(5)).isNull();
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM tenants WHERE version_tenant = 1 "
                    + "AND motivo_suspension_tenant = 'Mora'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
            try (ResultSet rs = s.executeQuery("SELECT estado, id_revisor FROM solicitudes_registro")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("APPROVED");
                assertThat(rs.getLong(2)).isEqualTo(1);
            }

            assertThat(s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                    + "id_solicitud_objetivo, fecha_auditoria) VALUES ('RESEND_ACTIVATION', 1, '" + SOLICITUD
                    + "', CURRENT_TIMESTAMP)")).isEqualTo(1);
            // Sin FK a la solicitud, como el tenant de V11: sobrevive a un borrado futuro.
            assertThat(s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                    + "id_solicitud_objetivo, fecha_auditoria) VALUES ('RESEND_ACTIVATION', 1, 'ya-no-existe', "
                    + "CURRENT_TIMESTAMP)")).isEqualTo(1);
            // Reenvio sin solicitud, reenvio con tenant o usuario, accion de tenant sin tenant o con solicitud.
            for (String valores : new String[]{
                    "('RESEND_ACTIVATION', 1, NULL, NULL, NULL, CURRENT_TIMESTAMP)",
                    "('RESEND_ACTIVATION', 1, 1, NULL, '" + SOLICITUD + "', CURRENT_TIMESTAMP)",
                    "('RESEND_ACTIVATION', 1, NULL, 1, '" + SOLICITUD + "', CURRENT_TIMESTAMP)",
                    "('SUSPEND_TENANT', 1, NULL, NULL, NULL, CURRENT_TIMESTAMP)",
                    "('SUSPEND_TENANT', 1, 1, NULL, '" + SOLICITUD + "', CURRENT_TIMESTAMP)",
                    "('DELETE_TENANT', 1, 1, NULL, NULL, CURRENT_TIMESTAMP)",
                    "('RESEND_ACTIVATION', 99, NULL, NULL, '" + SOLICITUD + "', CURRENT_TIMESTAMP)"}) {
                assertThatThrownBy(() -> s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                        + "id_tenant_objetivo, id_usuario_objetivo, id_solicitud_objetivo, fecha_auditoria) VALUES "
                        + valores)).as(valores).isInstanceOf(SQLException.class);
            }
            assertThat(s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                    + "id_tenant_objetivo, fecha_auditoria) VALUES ('REACTIVATE_TENANT', 1, 987654, CURRENT_TIMESTAMP)"))
                    .isEqualTo(1);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).load();
    }
}
