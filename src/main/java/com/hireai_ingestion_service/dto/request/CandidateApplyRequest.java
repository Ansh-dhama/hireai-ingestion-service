package com.hireai_ingestion_service.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public record CandidateApplyRequest(UUID jobPostingId,
                                    String fullName,
                                    String email,
                                    String githubUsername,
                                    MultipartFile resumeFile,
                                    boolean consentGiven,
                                    String consentNoticeVersion,
                                    boolean githubEvidenceConsent,
                                    List<String> repositories) {
        public CandidateApplyRequest {
                repositories = repositories == null ? List.of() : List.copyOf(repositories);
        }
}