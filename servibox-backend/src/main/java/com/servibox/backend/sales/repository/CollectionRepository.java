package com.servibox.backend.sales.repository;

import com.servibox.backend.sales.entity.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

    List<Collection> findBySaleId(Long saleId);
}
