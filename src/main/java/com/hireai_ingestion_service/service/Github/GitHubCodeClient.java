package com.hireai_ingestion_service.service.Github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hireai_ingestion_service.service.Redis.RedisExtractionGateway;
import com.hireai_ingestion_service.service.Validation.ExtractionModels;
import com.hireai_ingestion_service.service.Validation.ExtractionProperties;

import com.hireai_ingestion_service.service.Validation.ExtractionModels.PermanentFailure;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.RetryLater;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.StaleLease;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.WaitingForEvidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import java.util.List;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class GitHubCodeClient {
  private final HttpClient http;
  private final ObjectMapper json;
  private final RedisExtractionGateway limits;
  private final ExtractionProperties p;
  private final String base, token;

  public GitHubCodeClient(
          HttpClient http,
          ObjectMapper json,
          RedisExtractionGateway limits,
          ExtractionProperties p,
          @Value("${github.api.base-url}") String base,
          @Value("${github.api.token:}") String token) {
    this.http = http;
    this.json = json;
    this.limits = limits;
    this.p = p;
    this.base = base.replaceAll("/+$", "");
    this.token = token;
  }

  public static final class Budget {
    private int remaining;
    private final Instant deadline = Instant.now().plusSeconds(600);
    private final Runnable reserve;

    public Budget(int remaining) {
      this(remaining, () -> {});
    }

    public Budget(int remaining, Runnable reserve) {
      this.remaining = remaining;
      this.reserve = reserve;
    }

    public void use() {
      if (remaining-- <= 0 || Instant.now().isAfter(deadline))
        throw new ExtractionModels.PermanentFailure("COLLECTION_REQUEST_OR_TIME_BUDGET_EXCEEDED");
      reserve.run();
    }
  }

  public JsonNode get(String path, Budget budget, int maximumBytes) {
    try (AutoCloseable permit = limits.acquire()) {
      budget.use();
      var b =
              HttpRequest.newBuilder(URI.create(base + path))
                      .timeout(Duration.ofSeconds(p.requestTimeoutSeconds()))
                      .header("Accept", "application/vnd.github+json")
                      .header("X-GitHub-Api-Version", "2022-11-28")
                      .header("User-Agent", "HireAI-Phase2");
      if (!token.isBlank()) b.header("Authorization", "Bearer " + token);
      CompletableFuture<HttpResponse<byte[]>> future =
              http.sendAsync(b.GET().build(), info -> new LimitedBody(maximumBytes));
      HttpResponse<byte[]> response;
      try {
        response = future.get(p.requestTimeoutSeconds() + 1L, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new ExtractionModels.RetryLater("GITHUB_TIMEOUT", Instant.now().plusSeconds(20), true);
      }
      int status = response.statusCode();
      boolean primaryExhausted =
              response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0");
      if (status == 429
              || (status == 403
              && (primaryExhausted || response.headers().firstValue("Retry-After").isPresent()))) {
        Instant next = retryAt(response);
        limits.cooldown(next);
        throw new ExtractionModels.RetryLater("GITHUB_RATE_LIMIT", next, false);
      }
      if (status == 403) {
        Instant next = Instant.now().plusSeconds(65);
        limits.cooldown(next);
        throw new RetryLater("GITHUB_ACCESS_OR_SECONDARY_LIMIT", next, true);
      }
      if (status == 404 || status == 409) throw new WaitingForEvidence();
      if (status == 401) throw new PermanentFailure("GITHUB_CREDENTIAL_INVALID");
      if (status >= 500)
        throw new RetryLater("GITHUB_UPSTREAM_FAILURE", Instant.now().plusSeconds(20), true);
      if (status != 200) throw new PermanentFailure("GITHUB_HTTP_" + status);
      if (primaryExhausted) limits.cooldown(retryAt(response));
      return json.readTree(response.body());
    } catch (RetryLater | PermanentFailure | WaitingForEvidence | StaleLease e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RetryLater("WORKER_INTERRUPTED", Instant.now().plusSeconds(5), false);
    } catch (ExecutionException e) {
      throw new RetryLater("GITHUB_RESPONSE_FAILURE", Instant.now().plusSeconds(20), true);
    } catch (Exception e) {
      throw new RetryLater("GITHUB_CONNECTION_FAILURE", Instant.now().plusSeconds(20), true);
    }
  }

  private Instant retryAt(HttpResponse<?> r) {
    Instant now = Instant.now(), next = now.plusSeconds(65);
    String retry = r.headers().firstValue("Retry-After").orElse("");
    try {
      if (!retry.isBlank()) next = now.plusSeconds(Long.parseLong(retry) + 1);
    } catch (NumberFormatException e) {
      try {
        next =
                ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .plusSeconds(1);
      } catch (Exception ignored) {
      }
    }
    try {
      String reset = r.headers().firstValue("X-RateLimit-Reset").orElse("");
      if (!reset.isBlank()
              && r.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0")) {
        Instant end = Instant.ofEpochSecond(Long.parseLong(reset) + 1);
        if (end.isAfter(next)) next = end;
      }
    } catch (NumberFormatException ignored) {
    }
    return next.isAfter(now) ? next : now.plusSeconds(1);
  }

  public static String segment(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBody(int limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    public void onNext(List<ByteBuffer> chunks) {
      for (ByteBuffer chunk : chunks) {
        if (chunk.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IOException("GitHub response size limit"));
          return;
        }
        byte[] b = new byte[chunk.remaining()];
        chunk.get(b);
        bytes.writeBytes(b);
      }
      subscription.request(1);
    }

    public void onError(Throwable e) {
      result.completeExceptionally(e);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}