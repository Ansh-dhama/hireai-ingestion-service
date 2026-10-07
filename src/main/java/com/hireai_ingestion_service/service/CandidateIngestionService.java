package com.hireai_ingestion_service.service;

import com.hireai_ingestion_service.domain.entity.Candidate;
import com.hireai_ingestion_service.domain.entity.JobPosting;
import com.hireai_ingestion_service.domain.repository.CandidateRepository;
import com.hireai_ingestion_service.domain.repository.JobPostingRepository;
import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.dto.response.GitHubVerificationResult;
import com.hireai_ingestion_service.exception.InvalidDocumentException;
import com.hireai_ingestion_service.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class CandidateIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CandidateIngestionService.class);

    private final JobPostingRepository jobPostingRepository;
    private final CandidateRepository candidateRepository;
    private final ResumeExtractionService resumeExtractionService;
    private final GitHubVerificationService gitHubVerificationService;

    public CandidateIngestionService(
            JobPostingRepository jobPostingRepository,
            CandidateRepository candidateRepository,
            ResumeExtractionService resumeExtractionService,
            GitHubVerificationService gitHubVerificationService) {
        this.jobPostingRepository = jobPostingRepository;
        this.candidateRepository = candidateRepository;
        this.resumeExtractionService = resumeExtractionService;
        this.gitHubVerificationService = gitHubVerificationService;
    }

    @Transactional
    public CandidateApplyResponse processCandidateApplication(CandidateApplyRequest request) {
        log.info("Processing application for candidate {} against Job Posting {}",
                request.email(), request.jobPostingId());

        // 1. Verify target Job Posting exists and accepts candidates
        JobPosting jobPosting = jobPostingRepository.findById(request.jobPostingId())
                .orElseThrow(() -> new ResourceNotFoundException("Target Job Posting not found: " + request.jobPostingId()));

        if (!"OPEN".equalsIgnoreCase(jobPosting.getStatus())) {
            throw new InvalidDocumentException("This Job Posting is closed for applications.");
        }

        // 2. Prevent duplicate submissions per job opening
        if (candidateRepository.existsByJobPostingIdAndEmail(request.jobPostingId(), request.email())) {
            throw new InvalidDocumentException("An application with this email already exists for this position.");
        }

        // 3. Verify GitHub presence and proof of work (public repos >= 1)
        GitHubVerificationResult gitHubResult = gitHubVerificationService.verifyCandidateProfile(request.githubUsername());

        // 4. Extract and sanitize binary resume document
        ResumeExtractionService.ExtractionResult extractionResult =
                resumeExtractionService.extractAndSanitize(request.resumeFile());

        // 5. Persist candidate entity with normalized text & JSONB metadata
        Candidate candidate = new Candidate(
                jobPosting,
                request.fullName().trim(),
                request.email().toLowerCase().trim(),
                gitHubResult.username(),
                extractionResult.text(),
                request.resumeFile().getOriginalFilename(),
                request.resumeFile().getSize()
        );

        candidate.setExtractedMetadata(Map.of(
                "mimeType", extractionResult.mimeType(),
                "publicRepos", gitHubResult.publicRepoCount(),
                "gitHubUrl", gitHubResult.profileUrl(),
                "followers", gitHubResult.followers()
        ));

        Candidate saved = candidateRepository.save(candidate);

        log.info("Candidate application successfully accepted with ID: {}", saved.getId());

        return new CandidateApplyResponse(
                saved.getId(),
                saved.getFullName(),
                saved.getEmail(),
                saved.getStatus().name(),
                "Application accepted. Queued for asynchronous code evaluation."
        );
    }
}