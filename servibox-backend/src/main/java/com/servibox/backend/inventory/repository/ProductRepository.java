package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCodeIgnoreCase(String code);

    List<Product> findByCategoryId(Long categoryId);

    Optional<Product> findByCodeAndTenantId(String code, Long tenantId);
}
