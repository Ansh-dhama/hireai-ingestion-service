package com.hireai_ingestion_service.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "candidates", uniqueConstraints = {
        @UniqueConstraint(name = "uq_candidate_per_job", columnNames = {"job_posting_id", "email"})
})
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "github_username", nullable = false, length = 100)
    private String githubUsername;

    @Column(name = "raw_resume_text", nullable = false, columnDefinition = "TEXT")
    private String rawResumeText;

    @Column(name = "resume_file_name", nullable = false)
    private String resumeFileName;

    @Column(name = "resume_file_size_bytes", nullable = false)
    private Long resumeFileSizeBytes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> extractedMetadata = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CandidateStatus status = CandidateStatus.RECEIVED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Candidate() {}

    public Candidate(JobPosting jobPosting, String fullName, String email,
                     String githubUsername, String rawResumeText,
                     String resumeFileName, Long resumeFileSizeBytes) {
        this.jobPosting = jobPosting;
        this.fullName = fullName;
        this.email = email;
        this.githubUsername = githubUsername;
        this.rawResumeText = rawResumeText;
        this.resumeFileName = resumeFileName;
        this.resumeFileSizeBytes = resumeFileSizeBytes;
        this.status = CandidateStatus.RECEIVED;
    }

    public UUID getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getGithubUsername() { return githubUsername; }
    public String getRawResumeText() { return rawResumeText; }
    public String getResumeFileName() { return resumeFileName; }
    public Long getResumeFileSizeBytes() { return resumeFileSizeBytes; }
    public Map<String, Object> getExtractedMetadata() { return extractedMetadata; }
    public void setExtractedMetadata(Map<String, Object> extractedMetadata) { this.extractedMetadata = extractedMetadata; }
    public CandidateStatus getStatus() { return status; }
    public void setStatus(CandidateStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}