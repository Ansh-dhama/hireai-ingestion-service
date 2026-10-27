package com.hireai_ingestion_service.service;

import com.hireai_ingestion_service.domain.entity.*;
import com.hireai_ingestion_service.domain.repository.*;
import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.dto.response.GitHubVerificationResult;
import com.hireai_ingestion_service.exception.DuplicateApplicationException;
import com.hireai_ingestion_service.exception.ResourceNotFoundException;
import com.hireai_ingestion_service.service.Github.GitHubVerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
@Service
public class CandidateIngestionService {
    private final ResumeExtractionService resumes;
    private final CandidatePersistenceService persistence;
    private final GitHubVerificationService gitHubVerificationService;
    private final JobPostingRepository jobPostingRepository;
    private final CandidateRepository candidateRepository;
    public CandidateIngestionService(
            ResumeExtractionService resumes, CandidatePersistenceService persistence, GitHubVerificationService gitHubVerificationService, JobPostingRepository jobPostingRepository, CandidateRepository candidateRepository) {
        this.resumes = resumes;
        this.persistence = persistence;
        this.gitHubVerificationService = gitHubVerificationService;
        this.jobPostingRepository = jobPostingRepository;
        this.candidateRepository = candidateRepository;
    }


    public CandidateApplyResponse processCandidateApplication(CandidateApplyRequest raw) {
        if (!raw.consentGiven() || !"v1".equals(raw.consentNoticeVersion()))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Explicit consent and supported notice version v1 required");
        String username =
                raw.githubUsername() == null || raw.githubUsername().isBlank()
                        ? null
                        : raw.githubUsername().strip();
        if (username != null && !username.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GitHub username");
        if (raw.repositories().size() > 3
                || raw.repositories().stream()
                .anyMatch(
                        x ->
                                x == null
                                        || !x.matches("[A-Za-z0-9_.-]{1,100}")
                                        || x.equals(".")
                                        || x.equals("..")))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Choose at most three valid repository names");
        String normalizedEmail = raw.email().strip().toLowerCase(Locale.ROOT);

        // 1. Validate Job Posting existence before heavy work
        JobPosting jobPosting = jobPostingRepository.findById(raw.jobPostingId())
                .orElseThrow(() -> new ResourceNotFoundException("Target Job Posting not found: " + raw.jobPostingId()));

        if (candidateRepository.existsByJobPostingIdAndEmail(raw.jobPostingId(), normalizedEmail)) {
            throw new DuplicateApplicationException("An application with this email already exists for this job posting.");
        }

        var request =
                new CandidateApplyRequest(
                        raw.jobPostingId(),
                        raw.fullName().strip(),
                        raw.email().strip().toLowerCase(Locale.ROOT),
                        username == null ? null : username.toLowerCase(Locale.ROOT),
                        raw.resumeFile(),
                        true,
                        raw.consentNoticeVersion(),
                        raw.githubEvidenceConsent(),
                        raw.repositories().stream().map(x -> x.toLowerCase(Locale.ROOT)).toList());
        // No DB transaction or GitHub request surrounds document parsing.

        GitHubVerificationResult gitHubResult = null;
        if (request.githubUsername() != null) {
            gitHubResult = gitHubVerificationService.verifyCandidateProfile(request.githubUsername());
        }
        return persistence.persist(request, resumes.extractAndSanitize(request.resumeFile()));
    }
}