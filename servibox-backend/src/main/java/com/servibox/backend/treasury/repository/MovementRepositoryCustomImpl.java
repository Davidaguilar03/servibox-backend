package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

/**
 * Agregado por Criteria. El tenant lo pone el @Filter de Hibernate, que tambien aplica a
 * las consultas Criteria de la sesion.
 */
class MovementRepositoryCustomImpl implements MovementRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public double sumarMonto(Specification<Movement> filtro) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Double> consulta = cb.createQuery(Double.class);
        Root<Movement> raiz = consulta.from(Movement.class);
        consulta.select(cb.coalesce(cb.sum(raiz.<Double>get("amount")), 0.0))
                .where(filtro.toPredicate(raiz, consulta, cb));
        return entityManager.createQuery(consulta).getSingleResult();
    }
}
