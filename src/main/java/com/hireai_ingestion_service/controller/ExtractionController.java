package com.hireai_ingestion_service.controller;

import com.hireai_ingestion_service.service.ApplicationAccessService;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.Job;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.StartRequest;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.Status;
import com.hireai_ingestion_service.service.Validation.ExtractionStore;

import jakarta.validation.Valid;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/extraction")
public class ExtractionController {

  private final ApplicationAccessService access;
  private final ExtractionStore store;
  private final JdbcTemplate db;

  public ExtractionController(
          ApplicationAccessService access,
          ExtractionStore store,
          JdbcTemplate db
  ) {
    this.access = access;
    this.store = store;
    this.db = db;
  }

  // Read the latest extraction job's progress.
  @GetMapping
  public Status status(
          @PathVariable("candidateId") UUID candidateId,
          @RequestHeader(
                  value = "Authorization",
                  required = false
          ) String authorization
  ) {
    String candidate = candidateId.toString();

    access.requireOwner(candidate, authorization);

    return store.status(candidate);
  }

  // Start a new extraction run or resume a waiting application.
  @PostMapping
  public ResponseEntity<Status> start(
          @PathVariable("candidateId") UUID candidateId,
          @RequestHeader(
                  value = "Authorization",
                  required = false
          ) String authorization,
          @RequestHeader(
                  value = "Idempotency-Key",
                  required = false
          ) String idempotencyKey,
          @Valid @RequestBody StartRequest request
  ) {
    String candidate = candidateId.toString();

    access.requireOwner(candidate, authorization);

    if (idempotencyKey != null
            && !idempotencyKey.matches("[\\x21-\\x7E]{16,128}")) {
      throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "Idempotency-Key must contain 16-128 printable ASCII characters"
      );
    }

    if (!"v1".equals(request.noticeVersion())) {
      throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "Unsupported consent notice version"
      );
    }

    boolean invalidRepository = request.repositories()
            .stream()
            .anyMatch(name -> name.equals(".") || name.equals(".."));

    if (invalidRepository) {
      throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "Invalid repository name"
      );
    }

    String username = db.queryForObject(
            "SELECT github_username FROM candidates WHERE id = ?",
            String.class,
            candidate
    );

    if (request.githubUsername() != null) {
      username = request.githubUsername().toLowerCase(Locale.ROOT);
    }

    List<String> repositories = request.repositories()
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .toList();

    Job job = store.enqueue(
            candidate,
            username,
            repositories,
            true,
            request.noticeVersion(),
            idempotencyKey
    );

    // Return this specific job, including an idempotent replay.
    return ResponseEntity.accepted()
            .body(store.status(job));
  }

  // Read repository snapshots, dependencies and coverage.
  @GetMapping("/repositories")
  public List<Map<String, Object>> repositories(
          @PathVariable("candidateId") UUID candidateId,
          @RequestHeader(
                  value = "Authorization",
                  required = false
          ) String authorization
  ) {
    String candidate = candidateId.toString();

    access.requireOwner(candidate, authorization);

    return store.repositories(candidate);
  }

  // Read metadata, optionally including redacted source content.
  @GetMapping("/code-samples")
  public List<Map<String, Object>> samples(
          @PathVariable("candidateId") UUID candidateId,
          @RequestHeader(
                  value = "Authorization",
                  required = false
          ) String authorization,
          @RequestParam(
                  name = "includeContent",
                  defaultValue = "false"
          ) boolean includeContent
  ) {
    String candidate = candidateId.toString();

    access.requireOwner(candidate, authorization);

    return store.samples(candidate, includeContent);
  }
}