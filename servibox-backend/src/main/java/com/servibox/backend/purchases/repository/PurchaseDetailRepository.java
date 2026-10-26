package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.PurchaseDetail;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PurchaseDetailRepository extends JpaRepository<PurchaseDetail, Long> {

    @Query("SELECT d FROM PurchaseDetail d WHERE d.purchase.id = :purchaseId ORDER BY d.id")
    List<PurchaseDetail> findByPurchaseId(Long purchaseId);

    /** Si el producto ya salio en alguna factura de compra, anuladas incluidas. */
    boolean existsByProductId(Long productId);

    /** Una linea de compra reducida a lo que necesita el costo del producto. */
    interface LineaDeCosto {
        Long getProductId();

        Long getPurchaseId();

        Long getDetailId();

        Double getPrice();
    }

    /**
     * Por cada producto, las lineas de su compra mas reciente (mayor id de compra) que no este
     * en el estado excluido (ANULADA) ni sea la compra `compraExcluida`, en orden de id de
     * linea: la ultima de cada producto es la que fijaria su costo. Una sola consulta para
     * todos los productos. Ver "Costo del producto al anular y recuperar una compra" en
     * 03-DECISIONS.md.
     */
    @Query("SELECT d.product.id AS productId, d.purchase.id AS purchaseId, d.id AS detailId, d.price AS price "
            + "FROM PurchaseDetail d WHERE d.tenantId = :tenantId AND d.product.id IN :productIds "
            + "AND d.purchase.id = (SELECT MAX(d2.purchase.id) FROM PurchaseDetail d2 "
            + "WHERE d2.tenantId = :tenantId AND d2.product.id = d.product.id "
            + "AND d2.purchase.status <> :excluido AND d2.purchase.id <> :compraExcluida) "
            + "ORDER BY d.id")
    List<LineaDeCosto> findLineasDeLaUltimaCompra(Collection<Long> productIds, Long compraExcluida,
                                                  PurchaseStatus excluido, Long tenantId);
}
