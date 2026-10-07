package com.hireai_ingestion_service.dto.response;

public record GitHubVerificationResult(
        String username,
        int publicRepoCount,
        int followers,
        String profileUrl,
        boolean verified
) {}