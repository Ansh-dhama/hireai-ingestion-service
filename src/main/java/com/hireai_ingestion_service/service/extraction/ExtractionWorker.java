package com.hireai_ingestion_service.service.extraction;


import com.hireai_ingestion_service.service.Github.GitHubTreeCrawlerService;
import com.hireai_ingestion_service.service.Redis.RedisExtractionGateway;
import com.hireai_ingestion_service.service.Validation.ExtractionModels;
import com.hireai_ingestion_service.service.Validation.ExtractionProperties;
import com.hireai_ingestion_service.service.Validation.ExtractionStore;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExtractionWorker {
  private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);
  private final ExtractionStore store;
  private final RedisExtractionGateway redis;
  private final GitHubTreeCrawlerService crawler;
  private final ExtractionProperties p;
  private final String owner = UUID.randomUUID().toString();
  private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService heartbeat =
          Executors.newSingleThreadScheduledExecutor(
                  Thread.ofPlatform().daemon().name("extraction-heartbeat").factory());
  private final Semaphore slots;
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private volatile boolean closing;

  public ExtractionWorker(
          ExtractionStore store,
          RedisExtractionGateway redis,
          GitHubTreeCrawlerService crawler,
          ExtractionProperties p) {
    this.store = store;
    this.redis = redis;
    this.crawler = crawler;
    this.p = p;
    slots = new Semaphore(p.workers());
  }

  @Scheduled(fixedDelay = 250)
  public void poll() {
    if (!p.enabled() || closing || slots.availablePermits() == 0) return;
    try {
      var records = redis.read(owner);
      if (records == null) return;
      for (var record : records)
        submit(record.getId().getValue(), String.valueOf(record.getValue().get("jobId")));
    } catch (RuntimeException e) {
      log.debug("Extraction broker unavailable; durable jobs remain in MySQL");
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void reclaim() {
    if (!p.enabled() || closing || slots.availablePermits() == 0) return;
    try {
      for (var record : redis.reclaim(owner)) submit(record.get("messageId"), record.get("jobId"));
    } catch (RuntimeException e) {
      log.debug("Pending stream recovery will retry");
    }
  }

  private void submit(String messageId, String jobId) {
    if (jobId == null || !jobId.matches("[a-fA-F0-9-]{36}")) {
      redis.ack(messageId);
      return;
    }
    if (!slots.tryAcquire()) return;
    if (!running.add(jobId)) {
      slots.release();
      return;
    }
    tasks.submit(
            () -> {
              try {
                var claim = store.claim(jobId, owner);
                if (claim.isEmpty()) {
                  redis.ack(messageId);
                  return;
                }
                ExtractionModels.Claim c = claim.get();
                var beat =
                        heartbeat.scheduleAtFixedRate(
                                () -> {
                                  try {
                                    store.heartbeat(c);
                                  } catch (RuntimeException ignored) {
                                  }
                                },
                                10,
                                10,
                                TimeUnit.SECONDS);
                boolean durable = false;
                try {
                  crawler.extract(c);
                  durable = true;
                } catch (ExtractionModels.StaleLease e) {
                  durable = true;
                } catch (RuntimeException e) {
                  try {
                    store.failOrRetry(c, e);
                    durable = true;
                  } catch (ExtractionModels.StaleLease stale) {
                    durable = true;
                  }
                } finally {
                  beat.cancel(false);
                }
                if (durable) redis.ack(messageId);
              } catch (RuntimeException e) {
                log.warn(
                        "Extraction attempt could not record a durable outcome; recovery remains"
                                + " scheduled");
              } finally {
                running.remove(jobId);
                slots.release();
              }
            });
  }

  @Scheduled(fixedDelay = 1000)
  public void relay() {
    if (!p.enabled() || closing) return;
    try {
      for (int i = 0; i < 10; i++) {
        var event = store.claimOutbox(owner);
        if (event.isEmpty()) break;
        redis.publish(event.get());
        store.published(event.get());
      }
    } catch (RuntimeException e) {
      log.debug("Outbox relay will retry its durable notification");
    }
  }

  @Scheduled(fixedDelay = 5000)
  public void recover() {
    if (!p.enabled() || closing) return;

    try {
      store.reconcile();
    } catch (RuntimeException e) {
      log.warn("Extraction reconciliation will retry", e);
    }
  }

  @PreDestroy
  public void shutdown() {
    closing = true;
    tasks.shutdown();
    try {
      if (!tasks.awaitTermination(20, TimeUnit.SECONDS)) tasks.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      tasks.shutdownNow();
    }
    heartbeat.shutdownNow();
  }
}