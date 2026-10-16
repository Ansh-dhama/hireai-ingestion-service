package com.hireai_ingestion_service.dto.response;

import java.util.UUID;

public record CandidateApplyResponse(
        UUID candidateId,
        String fullName,
        String email,
        String status,
        String message,
        String extractionJobId,
        String applicationAccessToken) {}
