package com.hireai_ingestion_service.service.Validation;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ExtractionModels {
  private ExtractionModels() {}

  public record StartRequest(
      @NotNull @AssertTrue Boolean evidenceConsent,
      @NotBlank @Size(max = 80) String noticeVersion,
      @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?") String githubUsername,
      @Size(max = 3) List<@Pattern(regexp = "[A-Za-z0-9_.-]{1,100}") String> repositories) {
    public StartRequest {
      repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }
  }

  public record Job(
      String id,
      String candidateId,
      String githubUsername,
      int runNumber,
      String state,
      int attempts,
      int maxAttempts,
      long fencingToken,
      String leaseOwner,
      Instant leaseUntil,
      Instant availableAt,
      List<String> selection,
      String requestHash,
      String errorCode,
      Instant createdAt,
      Instant completedAt) {}

  public record Claim(Job job, String owner, long fence) {}

  public record Outbox(String id, String jobId, String kind, String owner) {}

  public record FileSample(
      String path,
      String category,
      String blobSha,
      String contentSha256,
      String content,
      int decodedBytes,
      int lineCount,
      int redactions,
      int score) {}

  public record RepositorySnapshot(
      String name,
      String fullName,
      String commitSha,
      String treeSha,
      boolean fork,
      String defaultBranch,
      String language,
      boolean truncated,
      boolean complete,
      List<String> limitations,
      List<Map<String, Object>> dependencies,
      List<FileSample> files) {}

  public record DiscoveredRepository(
      String name, String defaultBranch, boolean fork, String language) {}

  public record RepoState(
      String id,
      String name,
      String defaultBranch,
      boolean fork,
      String language,
      String commitSha,
      String treeSha,
      String state) {}

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
      Instant completedAt) {}

  public static class RetryLater extends RuntimeException {
    public final Instant availableAt;
    public final String code;
    public final boolean countAttempt;

    public RetryLater(String code, Instant when, boolean countAttempt) {
      super(code);
      this.code = code;
      availableAt = when;
      this.countAttempt = countAttempt;
    }
  }

  public static class PermanentFailure extends RuntimeException {
    public final String code;

    public PermanentFailure(String code) {
      super(code);
      this.code = code;
    }
  }

  public static class WaitingForEvidence extends RuntimeException {
    public WaitingForEvidence() {
      super("NO_ACCESSIBLE_PUBLIC_EVIDENCE");
    }
  }

  public static class StaleLease extends RuntimeException {}
}