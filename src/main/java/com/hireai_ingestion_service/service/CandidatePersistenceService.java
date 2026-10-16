package com.hireai_ingestion_service.service;

import com.hireai_ingestion_service.domain.entity.Candidate;
import com.hireai_ingestion_service.domain.repository.*;
import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.*;
import java.util.*;

import com.hireai_ingestion_service.service.Validation.ExtractionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CandidatePersistenceService {
  private final CandidateRepository candidates;
  private final JobPostingRepository jobs;
  private final ExtractionStore extraction;
  private final ApplicationAccessService access;

  public CandidatePersistenceService(
          CandidateRepository candidates,
          JobPostingRepository jobs,
          ExtractionStore extraction,
          ApplicationAccessService access) {
    this.candidates = candidates;
    this.jobs = jobs;
    this.extraction = extraction;
    this.access = access;
  }

  @Transactional
  public CandidateApplyResponse persist(
          CandidateApplyRequest request, ResumeExtractionService.ExtractionResult text) {
    var job =
            jobs.findById(request.jobPostingId())
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job posting not found"));
    if (!"OPEN".equals(job.getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not open");
    if (candidates.existsByJobPostingIdAndEmail(job.getId(), request.email()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Already applied to this job");
    String name =
            Optional.ofNullable(request.resumeFile().getOriginalFilename())
                    .orElse("resume")
                    .replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (name.length() > 255) name = name.substring(name.length() - 255);
    var candidate =
            new Candidate(
                    job,
                    request.fullName(),
                    request.email(),
                    request.githubUsername(),
                    text.text(),
                    name,
                    request.resumeFile().getSize());
    candidate.recordConsent(request.consentNoticeVersion(), request.githubEvidenceConsent());
    candidate.setExtractedMetadata(
            Map.of("mimeType", text.mimeType(), "githubOwnership", "UNCONFIRMED"));
    candidate = candidates.saveAndFlush(candidate);
    String token = access.issue(candidate.getId().toString());
    var queued =
            extraction.enqueue(
                    candidate.getId().toString(),
                    request.githubUsername(),
                    request.repositories(),
                    request.githubEvidenceConsent(),
                    request.consentNoticeVersion(),
                    null);
    return new CandidateApplyResponse(
            candidate.getId(),
            candidate.getFullName(),
            candidate.getEmail(),
            queued.state().equals("QUEUED") ? "QUEUED" : "WAITING_EVIDENCE",
            queued.state().equals("QUEUED")
                    ? "Application saved and extraction job durably queued"
                    : "Application saved; evidence permission or GitHub handle is required",
            queued.id(),
            token);
  }
}