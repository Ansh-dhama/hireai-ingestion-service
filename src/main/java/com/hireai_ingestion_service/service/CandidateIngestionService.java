package com.hireai_ingestion_service.service;

import com.hireai_ingestion_service.domain.entity.*;
import com.hireai_ingestion_service.domain.repository.*;
import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.dto.response.GitHubVerificationResult;
import com.hireai_ingestion_service.exception.DuplicateApplicationException;
import com.hireai_ingestion_service.exception.InvalidDocumentException;
import com.hireai_ingestion_service.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Map;

@Service
public class CandidateIngestionService {

    private final JobPostingRepository jobPostingRepository;
    private final CandidateRepository candidateRepository;
    private final ConsentEventRepository consentEventRepository;
    private final IngestionAuditLogRepository auditLogRepository;
    private final ResumeExtractionService resumeExtractionService;
    private final GitHubVerificationService gitHubVerificationService;
    private final TransactionTemplate transactionTemplate;

    public CandidateIngestionService(
            JobPostingRepository jobPostingRepository,
            CandidateRepository candidateRepository,
            ConsentEventRepository consentEventRepository,
            IngestionAuditLogRepository auditLogRepository,
            ResumeExtractionService resumeExtractionService,
            GitHubVerificationService gitHubVerificationService,
            TransactionTemplate transactionTemplate) {
        this.jobPostingRepository = jobPostingRepository;
        this.candidateRepository = candidateRepository;
        this.consentEventRepository = consentEventRepository;
        this.auditLogRepository = auditLogRepository;
        this.resumeExtractionService = resumeExtractionService;
        this.gitHubVerificationService = gitHubVerificationService;
        this.transactionTemplate = transactionTemplate;
    }

    // F07 Fix: NO @Transactional on outer method so GitHub HTTP and Tika parsing do not hold a DB connection
    public CandidateApplyResponse processCandidateApplication(CandidateApplyRequest request) {
        long startTimeMs = System.currentTimeMillis();

        if (!request.consentGiven()) {
            throw new InvalidDocumentException("Explicit candidate consent is required before processing.");
        }

        // F08 Fix: Normalize email and GitHub handle ONCE using Locale.ROOT before any lookup
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String cleanFullName = request.fullName().trim();

        try {
            // 1. Fast read check before doing heavy work
            JobPosting jobPosting = jobPostingRepository.findById(request.jobPostingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Target Job Posting not found: " + request.jobPostingId()));

            if (!"OPEN".equalsIgnoreCase(jobPosting.getStatus())) {
                throw new InvalidDocumentException("This Job Posting is closed for applications.");
            }

            if (candidateRepository.existsByJobPostingIdAndEmail(request.jobPostingId(), normalizedEmail)) {
                throw new DuplicateApplicationException("An application with this email already exists for this job posting.");
            }

            // 2. External I/O & CPU work executed OUTSIDE the database transaction (F07 Fix)
            GitHubVerificationResult gitHubResult = gitHubVerificationService.verifyCandidateProfile(request.githubUsername());
            ResumeExtractionService.ExtractionResult extractionResult = resumeExtractionService.extractAndSanitize(request.resumeFile());

            // 3. Short atomic database transaction for persistence (Candidate + Consent + Audit)
            Candidate savedCandidate = transactionTemplate.execute(status -> {
                if (candidateRepository.existsByJobPostingIdAndEmail(request.jobPostingId(), normalizedEmail)) {
                    throw new DuplicateApplicationException("An application with this email already exists for this job posting.");
                }

                Candidate candidate = new Candidate(
                        jobPosting,
                        cleanFullName,
                        normalizedEmail,
                        gitHubResult.username(),
                        extractionResult.text(),
                        request.resumeFile().getOriginalFilename(),
                        request.resumeFile().getSize()
                );

                // F11 Fix: Record accurate provenance flags
                candidate.setExtractedMetadata(Map.of(
                        "mimeType", extractionResult.mimeType(),
                        "publicReposCount", gitHubResult.publicRepoCount(),
                        "publicEvidenceAvailable", gitHubResult.publicRepoCount() > 0,
                        "ownershipConfirmed", false,
                        "evidenceRoute", gitHubResult.publicRepoCount() > 0 ? "PUBLIC_GITHUB" : "ALTERNATIVE_REQUIRED",
                        "gitHubUrl", gitHubResult.profileUrl()
                ));

                Candidate persisted = candidateRepository.saveAndFlush(candidate);

                // F06 Fix: Save explicit consent event
                ConsentEvent consent = new ConsentEvent(
                        persisted,
                        jobPosting.getId(),
                        true,
                        request.noticeVersion(),
                        request.clientIp()
                );
                consentEventRepository.save(consent);

                // F06 Fix: Save durable audit log
                long elapsed = System.currentTimeMillis() - startTimeMs;
                auditLogRepository.save(new IngestionAuditLog(
                        persisted.getId(),
                        jobPosting.getId(),
                        "CANDIDATE_INGESTION",
                        "SUCCESS",
                        elapsed,
                        null
                ));

                return persisted;
            });

            // F05 Fix: Return truthful status message matching actual persisted state
            return new CandidateApplyResponse(
                    savedCandidate.getId(),
                    savedCandidate.getFullName(),
                    savedCandidate.getEmail(),
                    savedCandidate.getStatus().name(),
                    "Application received and verified. Ready for evidence workflow."
            );

        } catch (DataIntegrityViolationException ex) {
            // F08 Fix: Race-safe translation of DB unique key constraint violation to 409 Conflict
            throw new DuplicateApplicationException("An application with this email already exists for this job posting.");
        } catch (RuntimeException ex) {
            recordFailureAuditSafely(request.jobPostingId(), startTimeMs, ex.getMessage());
            throw ex;
        }
    }

    private void recordFailureAuditSafely( java.util.UUID jobPostingId, long startTimeMs, String errorMsg) {
        try {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            auditLogRepository.save(new IngestionAuditLog(
                    null, jobPostingId, "CANDIDATE_INGESTION", "FAILED", elapsed, errorMsg
            ));
        } catch (Exception ignored) {
        }
    }
}