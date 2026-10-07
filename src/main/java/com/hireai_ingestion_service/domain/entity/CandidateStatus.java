package com.hireai_ingestion_service.domain.entity;

public enum CandidateStatus {
    RECEIVED,
    PARSING_FAILED,
    VERIFIED,
    IN_ANALYSIS,
    REJECTED,
    SHORTLISTED
}