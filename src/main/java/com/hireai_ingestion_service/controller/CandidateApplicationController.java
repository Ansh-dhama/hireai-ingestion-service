package com.hireai_ingestion_service.controller;

import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.service.CandidateIngestionService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/candidates")
public class CandidateApplicationController {

    private final CandidateIngestionService ingestionService;

    public CandidateApplicationController(
            CandidateIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(
            value = "/apply",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<CandidateApplyResponse> applyForJob(

            @RequestParam("jobPostingId")
            @NotNull UUID jobPostingId,

            @RequestParam("fullName")
            @NotBlank
            @Size(max = 255) String fullName,

            @RequestParam("email")
            @NotBlank
            @Email
            @Size(max = 255) String email,

            @RequestParam(value = "githubUsername", required = false)
            String githubUsername,

            @RequestPart("resume")
            @NotNull MultipartFile resumeFile,

            @RequestParam("consentGiven")
            @NotNull
            @AssertTrue(message = "Explicit consent is required to apply.")
            Boolean consentGiven,

            @RequestParam("consentNoticeVersion")
            @NotBlank
            @Size(max = 80) String consentNoticeVersion,

            @RequestParam(
                    value = "githubEvidenceConsent",
                    defaultValue = "false"
            )
            boolean githubEvidenceConsent,

            @RequestParam(value = "repositories", required = false)
            List<String> repositories
    ) {

        CandidateApplyRequest request = new CandidateApplyRequest(
                jobPostingId,
                fullName,
                email,
                githubUsername,
                resumeFile,
                consentGiven,
                consentNoticeVersion,
                githubEvidenceConsent,
                repositories
        );

        CandidateApplyResponse response =
                ingestionService.processCandidateApplication(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}