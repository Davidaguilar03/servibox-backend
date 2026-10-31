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
 * V11 sobre una base en V10 que ya tiene tenants (uno activo y uno inactivo de antes de
 * V11) y usuarios: conserva las filas, deja version 0 y sin suspension, y sus CHECK rechazan
 * datos incoherentes. Por defecto corre en una H2 nueva; con -Dservibox.migration-test.url
 * (y .user/.password) corre sobre una base externa que debe estar VACIA, por ejemplo una
 * PostgreSQL 16 recien creada.
 *
 * Una sola conexion para Flyway y las consultas: H2 2.4 deja de evaluar un CHECK con lista
 * IN (ck_auditoria_accion, y tambien los de V9 y V10) si la sesion que lo creo se cerro
 * ("Check constraint invalid"). En la app no pasa porque Flyway migra con el pool.
 */
class V11PlatformGovernanceMigrationTest {

    private static final String URL = System.getProperty("servibox.migration-test.url",
            "jdbc:h2:mem:flyway-v11-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    private static final String USER = System.getProperty("servibox.migration-test.user", "sa");
    private static final String PASSWORD = System.getProperty("servibox.migration-test.password", "");

    private final SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, USER, PASSWORD, true);

    @AfterEach
    void cerrar() {
        dataSource.destroy();
    }

    @Test
    void v11AplicaSobreV10ConTenantsYUsuariosExistentes() throws SQLException {
        assertThat(flyway("10").migrate().targetSchemaVersion).isEqualTo("10");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant) "
                    + "VALUES ('Activo V10', 'activo-v10', TRUE, CURRENT_TIMESTAMP)");
            s.executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant) "
                    + "VALUES ('Inactivo V10', 'inactivo-v10', FALSE, CURRENT_TIMESTAMP)");
            s.executeUpdate("INSERT INTO usuarios (id_tenant, username, password_hash, activo, rol, created_at, "
                    + "updated_at) SELECT id_tenant, 'admin', 'hash', TRUE, 'ADMIN', CURRENT_TIMESTAMP, "
                    + "CURRENT_TIMESTAMP FROM tenants");
            s.executeUpdate("INSERT INTO administradores_plataforma (id_administrador, email, password_hash) "
                    + "VALUES (1, 'v11@example.invalid', 'hash')");
        }

        MigrateResult resultado = flyway("11").migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        assertThat(resultado.targetSchemaVersion).isEqualTo("11");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT slug_tenant, activo_tenant, version_tenant, "
                    + "fecha_suspension_tenant, motivo_suspension_tenant FROM tenants ORDER BY slug_tenant")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("activo-v10");
                assertThat(rs.getBoolean(2)).isTrue();
                assertThat(rs.getLong(3)).isZero();
                assertThat(rs.getObject(4)).isNull();
                assertThat(rs.getObject(5)).isNull();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("inactivo-v10");
                assertThat(rs.getBoolean(2)).isFalse();
                assertThat(rs.getLong(3)).isZero();
                assertThat(rs.getObject(5)).isNull();
                assertThat(rs.next()).isFalse();
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*), MIN(auth_version), MAX(auth_version) FROM usuarios")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
                assertThat(rs.getLong(2)).isZero();
                assertThat(rs.getLong(3)).isZero();
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM auditoria_plataforma")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }

            assertThatThrownBy(() -> s.executeUpdate("UPDATE tenants SET motivo_suspension_tenant = 'x' "
                    + "WHERE slug_tenant = 'activo-v10'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE tenants SET version_tenant = -1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE usuarios SET auth_version = -1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                    + "id_tenant_objetivo, fecha_auditoria) VALUES ('DELETE_TENANT', 1, 1, CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                    + "id_tenant_objetivo, fecha_auditoria) VALUES ('SUSPEND_TENANT', 99, 1, CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            // Sin FK al tenant: la auditoria admite un id que ya no existe (sobrevive al borrado permanente).
            assertThat(s.executeUpdate("INSERT INTO auditoria_plataforma (accion, id_administrador, "
                    + "id_tenant_objetivo, fecha_auditoria) VALUES ('SUSPEND_TENANT', 1, 987654, CURRENT_TIMESTAMP)"))
                    .isEqualTo(1);
            assertThat(s.executeUpdate("UPDATE tenants SET activo_tenant = FALSE, motivo_suspension_tenant = 'Mora', "
                    + "fecha_suspension_tenant = CURRENT_TIMESTAMP, version_tenant = 1 WHERE slug_tenant = 'activo-v10'"))
                    .isEqualTo(1);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).load();
    }
}
