package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findByOriginAccountIdOrDestinationAccountIdOrderByDateDesc(
            Long originAccountId, Long destinationAccountId);
}
