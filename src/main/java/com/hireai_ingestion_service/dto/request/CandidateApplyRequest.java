package com.hireai_ingestion_service.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public record CandidateApplyRequest(
        @NotNull(message = "Job posting ID cannot be null")
        UUID jobPostingId,

        @NotBlank(message = "Candidate full name is required")
        String fullName,

        @NotBlank(message = "Email address is required")
        @Email(message = "Email must be a valid RFC 5322 format")
        String email,

        @NotBlank(message = "GitHub username is mandatory")
        String githubUsername,

        @NotNull(message = "Resume file binary must be supplied")
        MultipartFile resumeFile
) {}