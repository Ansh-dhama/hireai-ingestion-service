package com.hireai_ingestion_service.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_events")
public class ConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_posting_id", length = 36, nullable = false, updatable = false)
    private UUID jobPostingId;

    @Column(name = "consent_given", nullable = false, updatable = false)
    private boolean consentGiven;

    @Column(name = "purpose", nullable = false, length = 100, updatable = false)
    private String purpose = "GITHUB_AND_RESUME_EVALUATION";

    @Column(name = "notice_version", nullable = false, length = 50, updatable = false)
    private String noticeVersion;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConsentEvent() {}

    public ConsentEvent(Candidate candidate, UUID jobPostingId, boolean consentGiven, String noticeVersion, String ipAddress) {
        this.candidate = candidate;
        this.jobPostingId = jobPostingId;
        this.consentGiven = consentGiven;
        this.noticeVersion = noticeVersion;
        this.ipAddress = ipAddress;
    }

    public UUID getId() { return id; }
    public Candidate getCandidate() { return candidate; }
    public UUID getJobPostingId() { return jobPostingId; }
    public boolean isConsentGiven() { return consentGiven; }
    public String getPurpose() { return purpose; }
    public String getNoticeVersion() { return noticeVersion; }
    public String getIpAddress() { return ipAddress; }
    public Instant getCreatedAt() { return createdAt; }
}