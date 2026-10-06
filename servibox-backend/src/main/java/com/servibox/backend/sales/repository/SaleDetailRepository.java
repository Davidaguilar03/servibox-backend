package com.servibox.backend.sales.repository;

import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SaleDetailRepository extends JpaRepository<SaleDetail, Long> {

    List<SaleDetail> findBySaleId(Long saleId);

    /** Lineas de las facturas del rango, sin las del estado excluido. Para el Reporte de IVA. */
    List<SaleDetail> findBySale_InvoiceDateBetweenAndSale_StatusNot(LocalDate desde, LocalDate hasta,
                                                                    SaleStatus excluido);
}
