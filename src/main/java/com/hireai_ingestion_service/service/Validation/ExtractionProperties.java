package com.hireai_ingestion_service.service.Validation;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("hireai.extraction")
public record ExtractionProperties(
    boolean enabled,
    @Min(1) @Max(32) int workers,
    @Min(1) @Max(10) int maxAttempts,
    @Min(30) int leaseSeconds,
    @Min(1) @Max(3) int maxRepositories,
    @Min(1) @Max(5) int maxFilesPerRepository,
    @Min(1024) @Max(122880) int maxTotalBytes,
    @Min(1) @Max(65536) int maxFileBytes,
    @Min(1) @Max(100) int maxRequests,
    @Min(1) @Max(30) int maxTreeRequests,
    @Min(1) @Max(20) int requestTimeoutSeconds,
    @Min(1) int tokenCapacity,
    @DecimalMin("0.01") double tokenRefillPerSecond,
    @Min(1) @Max(16) int providerConcurrency,
    @NotBlank String stream,
    @NotBlank String dlqStream,
    @NotBlank String group,
    @Min(1) @Max(720) int accessTokenHours) {}
