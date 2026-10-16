package com.hireai_ingestion_service.domain;

import java.time.Instant;

public record Status(
    String jobId,
    String candidateId,
    int runNumber,
    String state,
    int attempts,
    Instant availableAt,
    String errorCode,
    int repositoriesCompleted,
    int filesCollected,
    int sourceBytes,
    Instant completedAt
) {}