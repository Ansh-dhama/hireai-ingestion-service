package com.hireai_ingestion_service.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_audit_logs")
public class IngestionAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "candidate_id", length = 36)
    private UUID candidateId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_posting_id", length = 36, nullable = false)
    private UUID jobPostingId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "execution_time_ms", nullable = false)
    private long executionTimeMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IngestionAuditLog() {}

    public IngestionAuditLog(UUID candidateId, UUID jobPostingId, String action, String status, long executionTimeMs, String errorMessage) {
        this.candidateId = candidateId;
        this.jobPostingId = jobPostingId;
        this.action = action;
        this.status = status;
        this.executionTimeMs = executionTimeMs;
        this.errorMessage = errorMessage;
    }

    public UUID getId() { return id; }
    public UUID getCandidateId() { return candidateId; }
    public UUID getJobPostingId() { return jobPostingId; }
    public String getAction() { return action; }
    public String getStatus() { return status; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}