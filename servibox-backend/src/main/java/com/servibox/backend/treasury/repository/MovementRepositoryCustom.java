package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import org.springframework.data.jpa.domain.Specification;

/** Consultas de MOVIMIENTOS que Spring Data no deriva solo. */
public interface MovementRepositoryCustom {

    /**
     * SUM(monto) de los movimientos que cumplen el filtro, calculada en la base; 0 si no hay
     * ninguno. Recibe una Specification para reutilizar los mismos predicados que el
     * listado, empezando por MovementSpecifications.activo().
     */
    double sumarMonto(Specification<Movement> filtro);
}
