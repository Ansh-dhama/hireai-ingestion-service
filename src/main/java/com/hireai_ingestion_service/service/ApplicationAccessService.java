package com.hireai_ingestion_service.service;

import java.security.SecureRandom;
import java.util.*;

import com.hireai_ingestion_service.service.Validation.ExtractionProperties;
import com.hireai_ingestion_service.service.extraction.SourceFileScoringEngine;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationAccessService {
  private final JdbcTemplate db;
  private final ExtractionProperties p;
  private final SecureRandom random = new SecureRandom();

  public ApplicationAccessService(JdbcTemplate db, ExtractionProperties p) {
    this.db = db;
    this.p = p;
  }

  public String issue(String candidateId) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
        "INSERT INTO candidate_access_tokens(token_hash,candidate_id,expires_at)"
            + " VALUES(?,?,TIMESTAMPADD(HOUR,?,UTC_TIMESTAMP(6)))",
        SourceFileScoringEngine.sha256(token),
        candidateId,
        p.accessTokenHours());
    return token;
  }

  public void requireOwner(String candidateId, String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Application bearer token required");
    String token = authorization.substring(7);
    if (token.length() < 32 || token.length() > 128)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid application token");
    int count =
        db.queryForObject(
            "SELECT COUNT(*) FROM candidate_access_tokens WHERE token_hash=? AND candidate_id=? AND"
                + " expires_at>UTC_TIMESTAMP(6)",
            Integer.class,
            SourceFileScoringEngine.sha256(token),
            candidateId);
    if (count != 1)
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Invalid or expired application token");
  }
}