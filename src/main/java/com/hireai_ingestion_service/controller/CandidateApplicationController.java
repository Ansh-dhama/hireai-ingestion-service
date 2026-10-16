package com.hireai_ingestion_service.controller;

import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.service.CandidateIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/candidates")
public class CandidateApplicationController {

    private final CandidateIngestionService ingestionService;

    public CandidateApplicationController(CandidateIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CandidateApplyResponse> applyForJob(
            @RequestParam("jobPostingId") @NotNull UUID jobPostingId,
            @RequestParam("fullName") @NotBlank String fullName,
            @RequestParam("email") @NotBlank @Email String email,
            @RequestParam("githubUsername") @NotBlank String githubUsername,
            @RequestParam(value = "consentGiven", defaultValue = "false")
            @AssertTrue(message = "You must consent to evidence processing to apply.") boolean consentGiven,
            @RequestParam(value = "noticeVersion", defaultValue = "v1.0-2026") @NotBlank String noticeVersion,
            @RequestPart("resume") @NotNull MultipartFile resumeFile,
            HttpServletRequest servletRequest) {

        CandidateApplyRequest request = new CandidateApplyRequest(
                jobPostingId,
                fullName,
                email,
                githubUsername,
                resumeFile,
                consentGiven,
                noticeVersion,
                true,
                null

        );

        CandidateApplyResponse response = ingestionService.processCandidateApplication(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}