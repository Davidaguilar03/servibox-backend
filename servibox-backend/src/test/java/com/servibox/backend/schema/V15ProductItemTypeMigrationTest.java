package com.servibox.backend.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V15 sobre una base en V14 con un producto: queda como PRODUCTO y el CHECK rechaza otro valor. */
class V15ProductItemTypeMigrationTest {

    private Flyway flyway(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, "sa", "")
                .schemas(schema).defaultSchema(schema).target(target).load();
    }

    @Test
    void migracionDesdeV14DejaLosProductosExistentesComoProducto() throws Exception {
        String url = "jdbc:h2:mem:v15-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        String schema = "v15_" + UUID.randomUUID().toString().replace("-", "");
        Flyway old = flyway(url, schema, "14");
        assertThat(old.migrate().migrationsExecuted).isEqualTo(14);
        try (Connection connection = old.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            connection.createStatement().executeUpdate("INSERT INTO tenants (id_tenant, nombre_tenant, nombre_busqueda_tenant, slug_tenant, activo_tenant, fecha_creacion_tenant) VALUES (1, 'Taller', 'taller', 'taller', TRUE, CURRENT_TIMESTAMP)");
            connection.createStatement().executeUpdate("INSERT INTO categoria_productos (id_categoria_producto, id_tenant, nombre_categoria_producto, color_categoria, stock_min_amarillo, stock_min_rojo, margen_utilidad, created_at, updated_at) VALUES (1, 1, 'LLANTAS', '#e74c3c', 5, 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            connection.createStatement().executeUpdate("INSERT INTO productos (id_producto, id_tenant, id_categoria_producto, codigo_producto, descripcion_producto, costo_compra, cantidad, iva_producto, precio_sugerido, created_at, updated_at) VALUES (1, 1, 1, 'LLA-001', 'Llanta', 100, 7, 19, 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        Flyway current = flyway(url, schema, "15");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = current.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(schema);
            try (ResultSet result = connection.createStatement().executeQuery(
                    "SELECT item_type, cantidad, costo_compra FROM productos WHERE id_producto = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("PRODUCTO");
                assertThat(result.getInt(2)).isEqualTo(7);
                assertThat(result.getBigDecimal(3)).isEqualByComparingTo("100");
            }
            assertThatThrownBy(() -> connection.createStatement()
                    .executeUpdate("UPDATE productos SET item_type = 'OTRO' WHERE id_producto = 1"))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
    }
}
