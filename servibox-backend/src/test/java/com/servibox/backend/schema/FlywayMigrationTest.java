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

    /** V2 sobre una base en V1 con un tenant y un usuario: las columnas nuevas quedan en null. */
    @Test
    void v2AplicaSobreV1ConDatosYDejaLasColumnasNuevasEnNull() throws SQLException {
        String url = "jdbc:h2:mem:flyway-v1-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway soloV1 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .target("1")
                .load();
        assertThat(soloV1.migrate().migrationsExecuted).isEqualTo(1);

        try (Connection conexion = DriverManager.getConnection(url, "sa", "")) {
            conexion.createStatement().executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, "
                    + "fecha_creacion_tenant) VALUES ('Taller V1', 'taller-v1', TRUE, CURRENT_TIMESTAMP)");
            conexion.createStatement().executeUpdate("INSERT INTO usuarios (id_tenant, username, password_hash, "
                    + "activo, rol, created_at, updated_at) SELECT id_tenant, 'admin', 'hash', TRUE, 'ADMIN', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM tenants");
        }

        MigrateResult resultado = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .target("2")
                .load()
                .migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.targetSchemaVersion).isEqualTo("2");
        try (Connection conexion = DriverManager.getConnection(url, "sa", "");
             ResultSet tenant = conexion.createStatement().executeQuery(
                     "SELECT slug_tenant, nit_tenant, telefono_tenant FROM tenants")) {
            assertThat(tenant.next()).isTrue();
            assertThat(tenant.getString("slug_tenant")).isEqualTo("taller-v1");
            assertThat(tenant.getString("nit_tenant")).isNull();
            assertThat(tenant.getString("telefono_tenant")).isNull();
            assertThat(tenant.next()).isFalse();

            ResultSet usuario = conexion.createStatement().executeQuery(
                    "SELECT username, nombre_completo FROM usuarios");
            assertThat(usuario.next()).isTrue();
            assertThat(usuario.getString("username")).isEqualTo("admin");
            assertThat(usuario.getString("nombre_completo")).isNull();
        }
    }

    /**
     * V3 sobre una base en V2 con compras de dos proveedores: las conserva, y la unicidad pasa
     * a ser por proveedor (el mismo numero vale para otro proveedor, no para el mismo).
     */
    @Test
    void v3AplicaSobreV2ConComprasYHaceElNumeroUnicoPorProveedor() throws SQLException {
        String url = "jdbc:h2:mem:flyway-v2-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway hastaV2 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .target("2")
                .load();
        assertThat(hastaV2.migrate().migrationsExecuted).isEqualTo(2);

        try (Connection conexion = DriverManager.getConnection(url, "sa", "")) {
            conexion.createStatement().executeUpdate("INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, "
                    + "fecha_creacion_tenant) VALUES ('Taller V2', 'taller-v2', TRUE, CURRENT_TIMESTAMP)");
            for (String[] proveedor : new String[][]{{"Llantas del Norte", "800000001"}, {"Lubricantes SAS", "800000002"}}) {
                conexion.createStatement().executeUpdate("INSERT INTO terceros (id_tenant, tipo_tercero, "
                        + "nombre_razon_social, tipo_documento, numero_documento, created_at, updated_at) SELECT "
                        + "id_tenant, 'PROVEEDOR', '" + proveedor[0] + "', 'NIT', '" + proveedor[1]
                        + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM tenants");
            }
            insertarCompra(conexion, "800000001", "FAC-1");
            insertarCompra(conexion, "800000002", "FAC-2");
        }

        MigrateResult resultado = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .target("3")
                .load()
                .migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        assertThat(resultado.targetSchemaVersion).isEqualTo("3");
        try (Connection conexion = DriverManager.getConnection(url, "sa", "")) {
            ResultSet compras = conexion.createStatement().executeQuery("SELECT c.numero_factura_compra, "
                    + "t.numero_documento, c.total_compra, c.estado_compra FROM compras c "
                    + "JOIN terceros t ON t.id_tercero = c.id_tercero ORDER BY c.numero_factura_compra");
            assertThat(compras.next()).isTrue();
            assertThat(compras.getString(1)).isEqualTo("FAC-1");
            assertThat(compras.getString(2)).isEqualTo("800000001");
            assertThat(compras.getBigDecimal(3)).isEqualByComparingTo("119000.00");
            assertThat(compras.getString(4)).isEqualTo("PENDIENTE");
            assertThat(compras.next()).isTrue();
            assertThat(compras.getString(1)).isEqualTo("FAC-2");
            assertThat(compras.getString(2)).isEqualTo("800000002");
            assertThat(compras.next()).isFalse();

            // El mismo numero para el otro proveedor ya cabe; para el mismo, la base lo frena.
            insertarCompra(conexion, "800000002", "FAC-1");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertarCompra(conexion, "800000001", "FAC-1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UK_COMPRA_TENANT_SUPPLIER_INVOICE_NUMBER");
            // La base distingue mayusculas: "fac-1" no choca alli (lo frena el service).
            insertarCompra(conexion, "800000001", "fac-1");
        }
    }

    /** Datos con el esquema hasta V3: un tenant, dos productos y una venta de dos lineas. */
    static final String[] DATOS_V3 = {
            "INSERT INTO tenants (nombre_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant) "
                    + "VALUES ('Taller V3', 'taller-v3', TRUE, CURRENT_TIMESTAMP)",
            "INSERT INTO categoria_productos (id_tenant, nombre_categoria_producto, color_categoria, "
                    + "stock_min_amarillo, stock_min_rojo, margen_utilidad, created_at, updated_at) SELECT "
                    + "id_tenant, 'LLANTAS', '#e74c3c', 5, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM tenants",
            "INSERT INTO productos (id_tenant, id_categoria_producto, codigo_producto, descripcion_producto, "
                    + "costo_compra, cantidad, iva_producto, precio_sugerido, created_at, updated_at) SELECT "
                    + "id_tenant, id_categoria_producto, 'LLA-1', 'Llanta', 12000, 8, 2280, 12000, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM categoria_productos",
            "INSERT INTO productos (id_tenant, id_categoria_producto, codigo_producto, descripcion_producto, "
                    + "costo_compra, cantidad, iva_producto, precio_sugerido, created_at, updated_at) SELECT "
                    + "id_tenant, id_categoria_producto, 'SRV-1', 'Alineacion', 0, 0, 0, 0, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM categoria_productos",
            "INSERT INTO terceros (id_tenant, tipo_tercero, nombre_razon_social, tipo_documento, numero_documento, "
                    + "created_at, updated_at) SELECT id_tenant, 'CLIENTE', 'Cliente V3', 'CC', '1000', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM tenants",
            "INSERT INTO ventas (id_tenant, id_tercero, numero_factura_venta, fecha_venta, fecha_vencimiento_venta, "
                    + "forma_pago_venta, estado_venta, subtotal_venta, iva_por_pagar_venta, total_venta, created_at, "
                    + "updated_at) SELECT id_tenant, id_tercero, 'FV-0001', CURRENT_DATE, CURRENT_DATE, 'CREDITO', "
                    + "'PENDIENTE', 80000, 7600, 95200, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM terceros",
            "INSERT INTO detalle_ventas (id_tenant, id_venta, id_producto, cantidad_venta, precio_venta, "
                    + "iva_generado_linea, diferencia_iva_linea, created_at, updated_at) SELECT v.id_tenant, "
                    + "v.id_venta, p.id_producto, 2, 15000, 5700, 1140, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                    + "FROM ventas v, productos p WHERE p.codigo_producto = 'LLA-1'",
            "INSERT INTO detalle_ventas (id_tenant, id_venta, id_producto, cantidad_venta, precio_venta, "
                    + "iva_generado_linea, diferencia_iva_linea, created_at, updated_at) SELECT v.id_tenant, "
                    + "v.id_venta, p.id_producto, 1, 50000, 9500, 9500, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                    + "FROM ventas v, productos p WHERE p.codigo_producto = 'SRV-1'"
    };

    /**
     * V4 sobre una base en V3 con una venta de dos lineas: cada linea queda con el costo actual
     * de su producto (12000 y 0), la columna es NOT NULL y el CHECK rechaza un costo negativo.
     */
    @Test
    void v4AplicaSobreV3ConVentasYRellenaElCostoConElDelProducto() throws SQLException {
        String url = "jdbc:h2:mem:flyway-v3-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway hastaV3 = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .target("3")
                .load();
        assertThat(hastaV3.migrate().migrationsExecuted).isEqualTo(3);
        try (Connection conexion = DriverManager.getConnection(url, "sa", "")) {
            for (String sql : DATOS_V3) {
                conexion.createStatement().executeUpdate(sql);
            }
        }

        MigrateResult resultado = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(flyway.getConfiguration().getLocations())
                .target("4")
                .load()
                .migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        assertThat(resultado.targetSchemaVersion).isEqualTo("4");
        try (Connection conexion = DriverManager.getConnection(url, "sa", "")) {
            ResultSet lineas = conexion.createStatement().executeQuery("SELECT p.codigo_producto, "
                    + "d.costo_unitario_linea FROM detalle_ventas d JOIN productos p ON p.id_producto = d.id_producto "
                    + "ORDER BY p.codigo_producto");
            assertThat(lineas.next()).isTrue();
            assertThat(lineas.getString(1)).isEqualTo("LLA-1");
            assertThat(lineas.getBigDecimal(2)).isEqualByComparingTo("12000.00");
            assertThat(lineas.next()).isTrue();
            assertThat(lineas.getString(1)).isEqualTo("SRV-1");
            assertThat(lineas.getBigDecimal(2)).isEqualByComparingTo("0.00");
            assertThat(lineas.next()).isFalse();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> conexion.createStatement().executeUpdate(
                            "UPDATE detalle_ventas SET costo_unitario_linea = -1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CK_DETALLE_VENTA_COSTO");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> conexion.createStatement().executeUpdate(
                            "UPDATE detalle_ventas SET costo_unitario_linea = NULL"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static void insertarCompra(Connection conexion, String documentoProveedor, String numero)
            throws SQLException {
        conexion.createStatement().executeUpdate("INSERT INTO compras (id_tenant, id_tercero, numero_factura_compra, "
                + "fecha_compra, forma_pago_compra, estado_compra, subtotal_compra, iva_total_compra, total_compra, "
                + "created_at, updated_at) SELECT id_tenant, id_tercero, '" + numero + "', CURRENT_DATE, 'CREDITO', "
                + "'PENDIENTE', 100000, 19000, 119000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM terceros "
                + "WHERE numero_documento = '" + documentoProveedor + "'");
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
