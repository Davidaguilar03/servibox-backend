package com.servibox.backend.schema;

import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * El esquema lo crea Flyway y Hibernate solo lo valida (ddl-auto=validate). Las migraciones
 * aplican limpias sobre una base vacia, Hibernate acepta el esquema que dejan, y la relacion
 * de solo lectura a Tenant de TenantAwareEntity no carga TENANTS.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private SalesFixture fixture;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void lasMigracionesAplicanLimpiasSobreUnaBaseVacia() throws SQLException {
        String url = "jdbc:h2:mem:flyway-vacia-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway vacia = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .load();

        MigrateResult resultado = vacia.migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(vacia.info().all().length);
        assertThat(vacia.info().pending()).isEmpty();
        try (Connection conexion = DriverManager.getConnection(url, "sa", "");
             ResultSet rs = conexion.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            int tablas = 0;
            while (rs.next()) {
                if (!rs.getString("TABLE_NAME").equalsIgnoreCase("flyway_schema_history")) {
                    tablas++;
                }
            }
            assertThat(tablas).isEqualTo(14);
        }
    }

    @Test
    void laBaseDelContextoQuedaMigradaYHibernateValidaElEsquema() {
        MigrationInfo[] aplicadas = flyway.info().applied();
        assertThat(aplicadas).isNotEmpty();
        assertThat(Arrays.stream(aplicadas).allMatch(m -> m.getState().isApplied() && !m.getState().isFailed()))
                .isTrue();
        assertThat(flyway.info().pending()).isEmpty();

        assertThatCode(() -> entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager()
                .validateMappedObjects()).doesNotThrowAnyException();
    }

    @Test
    void cargarUnaEntidadNoCargaSuTenant() {
        Long tenantId = fixture.crearTenant();
        fixture.usarTenant(tenantId);
        Long cuentaId = fixture.crearCuenta("Caja Lazy").getId();

        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            Account cuenta = entityManager.find(Account.class, cuentaId);

            assertThat(cuenta).isNotNull();
            assertThat(cuenta.getTenantId()).isEqualTo(tenantId);
            assertThat(entityManagerFactory.getPersistenceUnitUtil().isLoaded(cuenta, "tenant")).isFalse();
            // Solo la cuenta esta en el contexto de persistencia: el Tenant no se leyo.
            assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount()).isEqualTo(1);
        } finally {
            entityManager.close();
        }
    }
}
