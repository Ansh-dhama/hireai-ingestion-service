package com.hireai_ingestion_service.domain.repository;

import com.hireai_ingestion_service.domain.entity.JobPosting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {
    List<JobPosting> findByCompanyIdAndStatus(UUID companyId, String status);
}