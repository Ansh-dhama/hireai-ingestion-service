package com.hireai_ingestion_service.domain.repository;

import com.hireai_ingestion_service.domain.entity.IngestionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestionAuditLogRepository extends JpaRepository<IngestionAuditLog, UUID> {
}