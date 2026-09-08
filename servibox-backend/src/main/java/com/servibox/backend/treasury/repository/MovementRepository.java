package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {

    List<Movement> findByAccountIdOrderByDateDesc(Long accountId);

    /**
     * Los movimientos que genero un registro concreto: la venta y sus abonos, la compra y
     * sus pagos, la transferencia, el ingreso ocasional.
     *
     * Reemplaza a findBySourceSaleId / findBySourcePurchaseId /
     * findBySourceOccasionalIncomeId, que eran el mismo metodo repetido por cada origen.
     * El tenantId va explicito ademas del @Filter, mismo criterio que findByIdAndTenantId:
     * sourceId no es una clave foranea, asi que aqui el where es la unica garantia de que
     * no se cruce un id de otro tenant.
     */
    List<Movement> findBySourceTypeAndSourceIdAndTenantId(
            MovementSourceType sourceType, Long sourceId, Long tenantId);
}
