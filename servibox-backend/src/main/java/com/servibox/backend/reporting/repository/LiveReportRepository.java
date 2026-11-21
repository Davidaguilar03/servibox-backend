package com.servibox.backend.reporting.repository;

import com.servibox.backend.inventory.entity.ItemType;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * Facturas de compra del rango (anuladas incluidas), por fecha y id. Con categoriaId, las que
     * tienen al menos una linea de un producto de esa categoria, y sus cifras son solo las de esas
     * lineas: subtotal (precio por cantidad), IVA (el de la linea) y total. Lo pagado se reparte en
     * proporcion al total de esas lineas sobre el de la factura, para que el saldo tambien sea el
     * de la categoria.
     */
    public List<FacturaFila> compras(LocalDate desde, LocalDate hasta, Long proveedorId, Long categoriaId,
                                     PurchaseStatus estado, int limite) {
        if (categoriaId == null) {
            StringBuilder jpql = new StringBuilder(SELECT_COMPRA + "WHERE p.invoiceDate BETWEEN :desde AND :hasta ");
            Map<String, Object> parametros = new HashMap<>(Map.of("desde", desde, "hasta", hasta));
            filtro(jpql, parametros, "AND c.id = :proveedorId ", "proveedorId", proveedorId);
            filtro(jpql, parametros, "AND p.status = :estado ", "estado", estado);
            jpql.append("ORDER BY p.invoiceDate, p.id");
            return facturas(jpql.toString(), parametros, limite);
        }
        StringBuilder jpql = new StringBuilder("SELECT p.id, p.invoiceNumber, p.invoiceDate, p.dueDate, c.id, c.name, "
                + "p.paymentType, p.status, SUM(d.price * d.quantity), SUM(d.ivaAmount), p.total, "
                + "(SELECT COALESCE(SUM(m.amount), 0) FROM Movement m WHERE m.purchase = p) "
                + "FROM PurchaseDetail d JOIN d.purchase p JOIN p.supplier c "
                + "WHERE p.invoiceDate BETWEEN :desde AND :hasta AND d.product.category.id = :categoriaId ");
        Map<String, Object> parametros = new HashMap<>(Map.of("desde", desde, "hasta", hasta,
                "categoriaId", categoriaId));
        filtro(jpql, parametros, "AND c.id = :proveedorId ", "proveedorId", proveedorId);
        filtro(jpql, parametros, "AND p.status = :estado ", "estado", estado);
        jpql.append("GROUP BY p.id, p.invoiceNumber, p.invoiceDate, p.dueDate, c.id, c.name, p.paymentType, "
                + "p.status, p.total ORDER BY p.invoiceDate, p.id");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        parametros.forEach(query::setParameter);
        query.setMaxResults(limite);
        return query.getResultList().stream().map(f -> {
            BigDecimal subtotal = centavos(numero(f[8]).doubleValue());
            BigDecimal iva = centavos(numero(f[9]).doubleValue());
            BigDecimal total = subtotal.add(iva);
            BigDecimal totalFactura = BigDecimal.valueOf(numero(f[10]).doubleValue());
            BigDecimal pagado = totalFactura.signum() == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(numero(f[11]).doubleValue()).multiply(total)
                    .divide(totalFactura, 2, RoundingMode.HALF_UP);
            return new FacturaFila((Long) f[0], (String) f[1], (LocalDate) f[2], (LocalDate) f[3], (Long) f[4],
                    (String) f[5], (Enum<?>) f[6], (Enum<?>) f[7], subtotal.doubleValue(), iva.doubleValue(),
                    total.doubleValue(), pagado.doubleValue());
        }).toList();
    }

    // ---- Inventario ----

    /** Un PRODUCTO con su stock, umbrales de categoria y ventas. */
    public record InventarioFila(Long productId, String code, String description, String categoryName,
                                 Integer yellowStockMin, Integer redStockMin, int stock, double unitCost,
                                 double salePrice, long unitsSold, LocalDate lastSaleDate) {
    }

    /**
     * Los items PRODUCTO (nunca SERVICIO) con las unidades vendidas en [desde, hasta] y la fecha de
     * su ultima venta, las dos sin facturas anuladas, como subconsultas: una sola sentencia.
     */
    public List<InventarioFila> inventario(LocalDate desde, LocalDate hasta, Long categoriaId) {
        StringBuilder jpql = new StringBuilder("SELECT p.id, p.code, p.description, c.name, c.yellowStockMin, "
                + "c.redStockMin, p.quantity, p.purchaseCost, p.suggestedPrice, "
                + "(SELECT COALESCE(SUM(d.quantity), 0) FROM SaleDetail d WHERE d.product = p "
                + "AND d.sale.status <> :anulada AND d.sale.invoiceDate BETWEEN :desde AND :hasta), "
                + "(SELECT MAX(d2.sale.invoiceDate) FROM SaleDetail d2 WHERE d2.product = p AND d2.sale.status <> :anulada) "
                + "FROM Product p JOIN p.category c WHERE p.itemType = :producto ");
        Map<String, Object> parametros = new HashMap<>(Map.of("anulada", SaleStatus.ANULADA, "desde", desde,
                "hasta", hasta, "producto", ItemType.PRODUCTO));
        filtro(jpql, parametros, "AND c.id = :categoriaId ", "categoriaId", categoriaId);
        jpql.append("ORDER BY p.code");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        parametros.forEach(query::setParameter);
        return query.getResultList().stream()
                .map(f -> new InventarioFila((Long) f[0], (String) f[1], (String) f[2], (String) f[3],
                        (Integer) f[4], (Integer) f[5], numero(f[6]).intValue(), numero(f[7]).doubleValue(),
                        numero(f[8]).doubleValue(), numero(f[9]).longValue(), (LocalDate) f[10]))
                .toList();
    }

    // ---- Movimientos de tesoreria ----

    /**
     * Movimientos activos agrupados por categoria, tipo y forma de pago de la venta o compra
     * enlazada (para separar contado de abonos y pagos), con cuantos son y cuanto suman. Activo es
     * la regla de MovementSpecifications.activo(): no enlazado a una venta ni compra ANULADA.
     */
    public record FlujoFila(MovementCategory category, MovementType type, Enum<?> salePaymentType,
                            Enum<?> purchasePaymentType, long count, double amount) {
    }

    public List<FlujoFila> flujoPorConcepto(LocalDate desde, LocalDate hasta, Long cuentaId) {
        StringBuilder jpql = new StringBuilder("SELECT m.category, m.type, s.paymentType, pu.paymentType, COUNT(m), "
                + "SUM(m.amount) FROM Movement m LEFT JOIN m.sale s LEFT JOIN m.purchase pu WHERE " + ACTIVO
                + "AND m.date BETWEEN :desde AND :hasta ");
        Map<String, Object> parametros = parametrosActivo(desde, hasta);
        filtro(jpql, parametros, "AND m.account.id = :cuentaId ", "cuentaId", cuentaId);
        jpql.append("GROUP BY m.category, m.type, s.paymentType, pu.paymentType");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        parametros.forEach(query::setParameter);
        return query.getResultList().stream()
                .map(f -> new FlujoFila((MovementCategory) f[0], (MovementType) f[1], (Enum<?>) f[2], (Enum<?>) f[3],
                        numero(f[4]).longValue(), numero(f[5]).doubleValue()))
                .toList();
    }

    /** Un dia y tipo con la suma de sus movimientos activos. */
    public record DiaFila(LocalDate date, MovementType type, double amount) {
    }

    /** Movimientos activos por dia y tipo; sin transferencias si `conTransferencias` es false. */
    public List<DiaFila> movimientosPorDia(LocalDate desde, LocalDate hasta, Long cuentaId, MovementType tipo,
                                           boolean conTransferencias) {
        StringBuilder jpql = new StringBuilder("SELECT m.date, m.type, SUM(m.amount) FROM Movement m "
                + "LEFT JOIN m.sale s LEFT JOIN m.purchase pu WHERE " + ACTIVO
                + "AND m.date BETWEEN :desde AND :hasta ");
        Map<String, Object> parametros = parametrosActivo(desde, hasta);
        filtro(jpql, parametros, "AND m.account.id = :cuentaId ", "cuentaId", cuentaId);
        filtro(jpql, parametros, "AND m.type = :tipo ", "tipo", tipo);
        if (!conTransferencias) {
            jpql.append("AND m.category <> :transferencia ");
            parametros.put("transferencia", MovementCategory.TRANSFERENCIA);
        }
        jpql.append("GROUP BY m.date, m.type ORDER BY m.date");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        parametros.forEach(query::setParameter);
        return query.getResultList().stream()
                .map(f -> new DiaFila((LocalDate) f[0], (MovementType) f[1], numero(f[2]).doubleValue()))
                .toList();
    }

    private static final String ACTIVO = "(s.id IS NULL OR s.status <> :ventaAnulada) "
            + "AND (pu.id IS NULL OR pu.status <> :compraAnulada) ";

    private static Map<String, Object> parametrosActivo(LocalDate desde, LocalDate hasta) {
        return new HashMap<>(Map.of("ventaAnulada", SaleStatus.ANULADA, "compraAnulada", PurchaseStatus.ANULADA,
                "desde", desde, "hasta", hasta));
    }

    // ---- Estado de resultados por dia ----

    /** Ventas sin IVA y costo de lo vendido por dia, sin facturas anuladas (las de /kpis). */
    public List<Object[]> ventasYCostoPorDia(LocalDate desde, LocalDate hasta) {
        return entityManager.createQuery("SELECT s.invoiceDate, SUM(d.price * d.quantity), SUM(d.unitCost * d.quantity) "
                        + "FROM SaleDetail d JOIN d.sale s WHERE s.status <> :anulada "
                        + "AND s.invoiceDate BETWEEN :desde AND :hasta GROUP BY s.invoiceDate", Object[].class)
                .setParameter("anulada", SaleStatus.ANULADA)
                .setParameter("desde", desde)
                .setParameter("hasta", hasta)
                .getResultList();
    }

    /** Gastos operativos e ingresos ocasionales activos por dia y categoria. */
    public List<Object[]> gastosEIngresosPorDia(LocalDate desde, LocalDate hasta) {
        return entityManager.createQuery("SELECT m.date, m.category, SUM(m.amount) FROM Movement m "
                        + "WHERE m.category IN :categorias AND m.date BETWEEN :desde AND :hasta "
                        + "GROUP BY m.date, m.category", Object[].class)
                .setParameter("categorias", List.of(MovementCategory.GASTO_OPERATIVO, MovementCategory.INGRESO_OCASIONAL))
                .setParameter("desde", desde)
                .setParameter("hasta", hasta)
                .getResultList();
    }

    // ---- IVA ----

    /** Una linea de venta con IVA, reducida a lo que necesita el reporte de IVA. */
    public record IvaFila(Long saleId, String categoryName, double base, double ivaAmount, double ivaDifference) {
    }

    /**
     * Lineas de venta con IVA generado mayor que cero del rango, sin facturas anuladas: las mismas
     * que suma buildReporteIva, sin cargar entidades.
     */
    public List<IvaFila> lineasConIva(LocalDate desde, LocalDate hasta) {
        return entityManager.createQuery("SELECT s.id, c.name, d.price * d.quantity, d.ivaAmount, d.ivaDifference "
                        + "FROM SaleDetail d JOIN d.sale s JOIN d.product p JOIN p.category c "
                        + "WHERE s.status <> :anulada AND s.invoiceDate BETWEEN :desde AND :hasta "
                        + "AND d.ivaAmount > 0", Object[].class)
                .setParameter("anulada", SaleStatus.ANULADA)
                .setParameter("desde", desde)
                .setParameter("hasta", hasta)
                .getResultList().stream()
                .map(f -> new IvaFila((Long) f[0], (String) f[1], numero(f[2]).doubleValue(),
                        numero(f[3]).doubleValue(), numero(f[4]).doubleValue()))
                .toList();
    }

    private static BigDecimal centavos(double valor) {
        return BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP);
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
