package com.hireai_ingestion_service.controller;

import com.hireai_ingestion_service.dto.request.CandidateApplyRequest;
import com.hireai_ingestion_service.dto.response.CandidateApplyResponse;
import com.hireai_ingestion_service.service.CandidateIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/candidates")
@Tag(name = "Candidate Ingestion API", description = "Endpoints for applicant intake and verification")
public class CandidateApplicationController {

    private final CandidateIngestionService ingestionService;

    public CandidateApplicationController(CandidateIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Submit candidate resume & profile",
            description = "Validates resume binary via Apache Tika, verifies public GitHub activity, and saves candidate records.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Application accepted and persisted",
                            content = @Content(schema = @Schema(implementation = CandidateApplyResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid payload, fake GitHub profile, or invalid file structure",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Job posting not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "413", description = "Resume payload exceeds 10MB limit",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<CandidateApplyResponse> applyForJob(
            @RequestParam("jobPostingId") @NotNull UUID jobPostingId,
            @RequestParam("fullName") @NotBlank String fullName,
            @RequestParam("email") @Email @NotBlank String email,
            @RequestParam("githubUsername") @NotBlank String githubUsername,
            @RequestPart("resume") @NotNull MultipartFile resumeFile) {

        CandidateApplyRequest request = new CandidateApplyRequest(
                jobPostingId, fullName, email, githubUsername, resumeFile
        );

        CandidateApplyResponse response = ingestionService.processCandidateApplication(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}