package com.hireai_ingestion_service.service;

import com.hireai_ingestion_service.domain.entity.*;
import com.hireai_ingestion_service.domain.repository.*;
import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.dto.response.GitHubVerificationResult;
import com.hireai_ingestion_service.exception.DuplicateApplicationException;
import com.hireai_ingestion_service.exception.InvalidDocumentException;
import com.hireai_ingestion_service.exception.ResourceNotFoundException;
import com.hireai_ingestion_service.service.Github.GitHubVerificationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;
@Service
public class CandidateIngestionService {
    private final ResumeExtractionService resumes;
    private final CandidatePersistenceService persistence;

    public CandidateIngestionService(
            ResumeExtractionService resumes, CandidatePersistenceService persistence) {
        this.resumes = resumes;
        this.persistence = persistence;
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
        return persistence.persist(request, resumes.extractAndSanitize(request.resumeFile()));
    }
}