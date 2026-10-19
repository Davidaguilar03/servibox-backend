package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

/** Consultas de MOVIMIENTOS que Spring Data no deriva solo. */
public interface MovementRepositoryCustom {

    /**
     * SUM(monto) de los movimientos que cumplen el filtro, calculada en la base; 0 si no hay
     * ninguno. Recibe una Specification para reutilizar los mismos predicados que el
     * listado, empezando por MovementSpecifications.activo().
     */
    double sumarMonto(Specification<Movement> filtro);

    /**
     * Un movimiento para el reporte de movimientos, ya plano: el nombre de la cuenta y el
     * numero de la factura de venta o de compra vienen en la misma consulta.
     */
    record FilaReporte(Long id, LocalDate date, String accountName, MovementType type,
                       MovementCategory category, String concept, double amount,
                       String saleInvoiceNumber, String purchaseInvoiceNumber) {
    }

    /**
     * Los movimientos que cumplen el filtro, del mas antiguo al mas reciente y por id, como
     * proyeccion en una sola consulta con join a cuenta, venta y compra: no carga entidades,
     * asi que no dispara las cargas EAGER de cada movimiento. A lo sumo `maximo` filas.
     */
    List<FilaReporte> listarParaReporte(Specification<Movement> filtro, int maximo);
}
