package com.hireai_ingestion_service.domain.repository;

import com.hireai_ingestion_service.domain.entity.ConsentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConsentEventRepository extends JpaRepository<ConsentEvent, UUID> {
}