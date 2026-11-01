package db.migration;

import com.servibox.backend.shared.SearchText;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Columnas de busqueda sin acentos ni mayusculas. Es Java porque el backfill debe dar exactamente
 * lo mismo que la aplicacion (SearchText) en H2 y PostgreSQL, y ningun SQL comun a los dos motores
 * hace NFD. VARCHAR(450): 150 caracteres que NFD descompone sin marcas (hangul) ocupan hasta tres.
 * Sin indice: la busqueda es "contiene" y un B-tree no sirve con comodin inicial.
 */
public class V13__search_normalized_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        agregar(connection, "tenants", "id_tenant", "nombre_tenant", "nombre_busqueda_tenant");
        agregar(connection, "solicitudes_registro", "id_solicitud", "nombre_comercial", "nombre_comercial_busqueda");
        agregar(connection, "solicitudes_registro", "id_solicitud", "nombre_responsable",
                "nombre_responsable_busqueda");
    }

    private static void agregar(Connection connection, String tabla, String id, String origen, String destino)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tabla + " ADD COLUMN " + destino + " VARCHAR(450)");
            try (ResultSet filas = statement.executeQuery("SELECT " + id + ", " + origen + " FROM " + tabla);
                    PreparedStatement update = connection.prepareStatement(
                            "UPDATE " + tabla + " SET " + destino + " = ? WHERE " + id + " = ?")) {
                while (filas.next()) {
                    update.setString(1, SearchText.normalize(filas.getString(2)));
                    update.setObject(2, filas.getObject(1));
                    update.addBatch();
                }
                update.executeBatch();
            }
            statement.execute("ALTER TABLE " + tabla + " ALTER COLUMN " + destino + " SET NOT NULL");
        }
    }
}
