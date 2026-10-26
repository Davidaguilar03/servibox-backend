package com.servibox.backend.sales.repository;

import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface SaleDetailRepository extends JpaRepository<SaleDetail, Long> {

    @Query("SELECT d FROM SaleDetail d WHERE d.sale.id = :saleId ORDER BY d.id")
    List<SaleDetail> findBySaleId(Long saleId);

    /** Si el producto ya salio en alguna factura de venta, anuladas incluidas. */
    boolean existsByProductId(Long productId);

    /** Lineas de las facturas del rango, sin las del estado excluido. Para el Reporte de IVA. */
    List<SaleDetail> findBySale_InvoiceDateBetweenAndSale_StatusNot(LocalDate desde, LocalDate hasta,
                                                                    SaleStatus excluido);

    /**
     * Costo de lo vendido en todo el historico: costo unitario congelado por cantidad de cada
     * linea, sin las lineas de facturas en el estado excluido (ANULADA). Una sola consulta.
     */
    @Query("SELECT COALESCE(SUM(d.unitCost * d.quantity), 0) FROM SaleDetail d WHERE d.sale.status <> :excluido")
    double sumCostByStatusNot(SaleStatus excluido);

    /** El mismo costo de lo vendido, por fecha de factura dentro de [desde, hasta]. */
    @Query("SELECT COALESCE(SUM(d.unitCost * d.quantity), 0) FROM SaleDetail d WHERE d.sale.status <> :excluido "
            + "AND d.sale.invoiceDate BETWEEN :desde AND :hasta")
    double sumCostByStatusNotAndInvoiceDateBetween(SaleStatus excluido, LocalDate desde, LocalDate hasta);
}
