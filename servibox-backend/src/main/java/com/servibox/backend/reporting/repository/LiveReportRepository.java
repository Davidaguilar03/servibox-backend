package com.servibox.backend.reporting.repository;

import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consultas de los reportes en vivo. Cada una es una sola sentencia que proyecta escalares,
 * sin cargar entidades (Sale y Purchase traen el tercero EAGER): una serviteca puede tener
 * miles de facturas. Lo abonado o pagado es una subconsulta correlacionada sobre los
 * movimientos de la factura, la misma suma que saldoPendiente de Sales y Purchases.
 *
 * Viven aqui y no en el modulo de cada entidad porque cruzan factura, tercero, producto y
 * movimientos. JPQL armado solo con los filtros presentes: un (:x IS NULL OR ...) da
 * problemas de tipado en PostgreSQL. El tenant lo pone el @Filter de Hibernate, que aplica a
 * las consultas JPQL de la sesion, subconsultas incluidas.
 */
@Repository
public class LiveReportRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Una factura de venta o de compra con lo abonado o pagado. iva: el de la factura. */
    public record FacturaFila(Long id, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate,
                              Long counterpartyId, String counterpartyName, Enum<?> paymentType, Enum<?> status,
                              double subtotal, double iva, double total, double paid) {
    }

    /** Lo vendido de un producto o servicio en el rango. */
    public record ProductoFila(Long productId, String code, String description, ItemType itemType,
                               String categoryName, long units, double revenue, double cost) {
    }

    /** Facturas de venta del rango (anuladas incluidas), por fecha y id. */
    public List<FacturaFila> ventas(LocalDate desde, LocalDate hasta, Long clienteId,
                                    PaymentType formaPago, SaleStatus estado,
                                    int limite) {
        StringBuilder jpql = new StringBuilder(SELECT_VENTA + "WHERE s.invoiceDate BETWEEN :desde AND :hasta ");
        Map<String, Object> parametros = new HashMap<>(Map.of("desde", desde, "hasta", hasta));
        filtro(jpql, parametros, "AND c.id = :clienteId ", "clienteId", clienteId);
        filtro(jpql, parametros, "AND s.paymentType = :formaPago ", "formaPago", formaPago);
        filtro(jpql, parametros, "AND s.status = :estado ", "estado", estado);
        jpql.append("ORDER BY s.invoiceDate, s.id");
        return facturas(jpql.toString(), parametros, limite);
    }

    /** Facturas de venta PENDIENTE, por cliente, fecha e id. */
    public List<FacturaFila> ventasPendientes(Long clienteId, int limite) {
        StringBuilder jpql = new StringBuilder(SELECT_VENTA + "WHERE s.status = :estado ");
        Map<String, Object> parametros = new HashMap<>(Map.of("estado", SaleStatus.PENDIENTE));
        filtro(jpql, parametros, "AND c.id = :clienteId ", "clienteId", clienteId);
        jpql.append("ORDER BY c.name, s.invoiceDate, s.id");
        return facturas(jpql.toString(), parametros, limite);
    }

    /**
     * Facturas de compra del rango (anuladas incluidas), por fecha y id. Con categoriaId,
     * las que tienen al menos una linea de un producto de esa categoria.
     */
    public List<FacturaFila> compras(LocalDate desde, LocalDate hasta, Long proveedorId, Long categoriaId,
                                     PurchaseStatus estado, int limite) {
        StringBuilder jpql = new StringBuilder(SELECT_COMPRA + "WHERE p.invoiceDate BETWEEN :desde AND :hasta ");
        Map<String, Object> parametros = new HashMap<>(Map.of("desde", desde, "hasta", hasta));
        filtro(jpql, parametros, "AND c.id = :proveedorId ", "proveedorId", proveedorId);
        filtro(jpql, parametros, "AND EXISTS (SELECT 1 FROM PurchaseDetail d WHERE d.purchase = p "
                + "AND d.product.category.id = :categoriaId) ", "categoriaId", categoriaId);
        filtro(jpql, parametros, "AND p.status = :estado ", "estado", estado);
        jpql.append("ORDER BY p.invoiceDate, p.id");
        return facturas(jpql.toString(), parametros, limite);
    }

    /** Facturas de compra PENDIENTE, por proveedor, fecha e id. */
    public List<FacturaFila> comprasPendientes(Long proveedorId, int limite) {
        StringBuilder jpql = new StringBuilder(SELECT_COMPRA + "WHERE p.status = :estado ");
        Map<String, Object> parametros = new HashMap<>(Map.of("estado", PurchaseStatus.PENDIENTE));
        filtro(jpql, parametros, "AND c.id = :proveedorId ", "proveedorId", proveedorId);
        jpql.append("ORDER BY c.name, p.invoiceDate, p.id");
        return facturas(jpql.toString(), parametros, limite);
    }

    /**
     * Unidades, ingresos sin IVA (precio por cantidad) y costo congelado (costo unitario de la
     * linea por cantidad) por producto, sin facturas anuladas, de mayor a menor ingreso. La
     * categoria es la actual del producto.
     */
    public List<ProductoFila> productosVendidos(LocalDate desde, LocalDate hasta, ItemType itemType,
                                                Long categoriaId, Long productoId) {
        StringBuilder jpql = new StringBuilder("SELECT pr.id, pr.code, pr.description, pr.itemType, c.name, "
                + "SUM(d.quantity), SUM(d.price * d.quantity), SUM(d.unitCost * d.quantity) "
                + "FROM SaleDetail d JOIN d.sale s JOIN d.product pr JOIN pr.category c "
                + "WHERE s.status <> :anulada AND s.invoiceDate BETWEEN :desde AND :hasta ");
        Map<String, Object> parametros = new HashMap<>(Map.of("anulada", SaleStatus.ANULADA,
                "desde", desde, "hasta", hasta));
        filtro(jpql, parametros, "AND pr.itemType = :itemType ", "itemType", itemType);
        filtro(jpql, parametros, "AND c.id = :categoriaId ", "categoriaId", categoriaId);
        filtro(jpql, parametros, "AND pr.id = :productoId ", "productoId", productoId);
        jpql.append("GROUP BY pr.id, pr.code, pr.description, pr.itemType, c.name "
                + "ORDER BY SUM(d.price * d.quantity) DESC, pr.code");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        parametros.forEach(query::setParameter);
        return query.getResultList().stream()
                .map(f -> new ProductoFila((Long) f[0], (String) f[1], (String) f[2], (ItemType) f[3],
                        (String) f[4], numero(f[5]).longValue(), numero(f[6]).doubleValue(),
                        numero(f[7]).doubleValue()))
                .toList();
    }

    private static final String SELECT_VENTA = "SELECT s.id, s.invoiceNumber, s.invoiceDate, s.dueDate, c.id, c.name, "
            + "s.paymentType, s.status, s.subtotal, s.total - s.subtotal, s.total, "
            + "(SELECT COALESCE(SUM(m.amount), 0) FROM Movement m WHERE m.sale = s) "
            + "FROM Sale s JOIN s.customer c ";

    private static final String SELECT_COMPRA = "SELECT p.id, p.invoiceNumber, p.invoiceDate, p.dueDate, c.id, c.name, "
            + "p.paymentType, p.status, p.subtotal, p.ivaTotal, p.total, "
            + "(SELECT COALESCE(SUM(m.amount), 0) FROM Movement m WHERE m.purchase = p) "
            + "FROM Purchase p JOIN p.supplier c ";

    private static void filtro(StringBuilder jpql, Map<String, Object> parametros, String condicion, String nombre,
                               Object valor) {
        if (valor != null) {
            jpql.append(condicion);
            parametros.put(nombre, valor);
        }
    }

    /** Hasta `limite` filas: quien llama pide una de mas para saber si se paso del tope. */
    private List<FacturaFila> facturas(String jpql, Map<String, Object> parametros, int limite) {
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class);
        parametros.forEach(query::setParameter);
        query.setMaxResults(limite);
        return query.getResultList().stream()
                .map(f -> new FacturaFila((Long) f[0], (String) f[1], (LocalDate) f[2], (LocalDate) f[3],
                        (Long) f[4], (String) f[5], (Enum<?>) f[6], (Enum<?>) f[7], numero(f[8]).doubleValue(), numero(f[9]).doubleValue(),
                        numero(f[10]).doubleValue(), numero(f[11]).doubleValue()))
                .toList();
    }

    private static Number numero(Object valor) {
        return valor == null ? 0 : (Number) valor;
    }
}
