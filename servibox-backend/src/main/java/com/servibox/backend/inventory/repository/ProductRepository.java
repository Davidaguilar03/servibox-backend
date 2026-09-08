package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCodeIgnoreCase(String code);

    List<Product> findByCategoryId(Long categoryId);

    Optional<Product> findByCodeAndTenantId(String code, Long tenantId);

    /**
     * Busqueda por id que si respeta el tenant. El @Filter de Hibernate NO se aplica a
     * EntityManager.find(), asi que el findById heredado de JpaRepository devuelve el
     * producto aunque sea de otro tenant. Las consultas derivadas como esta si pasan por
     * el filtro, y ademas el tenant va explicito en el where.
     */
    Optional<Product> findByIdAndTenantId(Long id, Long tenantId);
}
