package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {

    List<Movement> findByAccountIdOrderByDateDesc(Long accountId);
}
