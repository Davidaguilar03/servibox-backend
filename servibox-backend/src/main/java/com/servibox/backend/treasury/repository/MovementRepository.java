package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {

    List<Movement> findByAccountIdOrderByDateDesc(Long accountId);

    /** Los movimientos que genero una venta: el ingreso del contado y los de sus abonos. */
    List<Movement> findBySourceSaleId(Long saleId);

    /** El movimiento que genero un ingreso ocasional, para poder revertirlo al eliminarlo. */
    List<Movement> findBySourceOccasionalIncomeId(Long occasionalIncomeId);
}
