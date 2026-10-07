package com.hireai_ingestion_service.domain.repository;

import com.hireai_ingestion_service.domain.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, UUID> {
    boolean existsByJobPostingIdAndEmail(UUID jobPostingId, String email);
    Optional<Candidate> findByJobPostingIdAndEmail(UUID jobPostingId, String email);
}