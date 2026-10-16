package com.hireai_ingestion_service.service.Redis;

import com.hireai_ingestion_service.service.Validation.ExtractionModels.Outbox;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.RetryLater;
import com.hireai_ingestion_service.service.Validation.ExtractionProperties;
import com.hireai_ingestion_service.service.extraction.SourceFileScoringEngine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisExtractionGateway {

  private final StringRedisTemplate redis;
  private final ExtractionProperties p;
  private final String rateKey;

  private volatile boolean initialized;
  private volatile String reclaimCursor = "0-0";

  private static final DefaultRedisScript<Long> INIT =
          new DefaultRedisScript<>(
                  "redis.call('XGROUP','CREATE',KEYS[1],ARGV[1],'0-0','MKSTREAM'); return 1",
                  Long.class
          );

  private static final DefaultRedisScript<List> RECLAIM =
          new DefaultRedisScript<>(
                  "return redis.call('XAUTOCLAIM',KEYS[1],ARGV[1],ARGV[2],ARGV[3],ARGV[4],'COUNT',ARGV[5])",
                  List.class
          );

  private static final DefaultRedisScript<List> ACQUIRE =
          new DefaultRedisScript<>(
                  """
                  local t=redis.call('TIME')
                  local now=t[1]*1000+math.floor(t[2]/1000)

                  local blocked=tonumber(redis.call('GET',KEYS[3]) or '0')
                  if blocked>now then
                      return {0,blocked-now}
                  end

                  local state=redis.call('HMGET',KEYS[1],'tokens','at')
                  local tokens=tonumber(state[1]) or tonumber(ARGV[1])
                  local at=tonumber(state[2]) or now

                  tokens=math.min(
                      tonumber(ARGV[1]),
                      tokens+math.max(0,now-at)/1000*tonumber(ARGV[2])
                  )

                  redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now)

                  if tokens<1 then
                      return {0,math.ceil((1-tokens)/tonumber(ARGV[2])*1000)}
                  end

                  if redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[3]) then
                      return {0,1000}
                  end

                  redis.call('HSET',KEYS[1],'tokens',tokens-1,'at',now)
                  redis.call('PEXPIRE',KEYS[1],3600000)

                  redis.call('ZADD',KEYS[2],now+30000,ARGV[4])
                  redis.call('PEXPIRE',KEYS[2],60000)

                  return {1,0}
                  """,
                  List.class
          );

  private static final DefaultRedisScript<Long> COOLDOWN =
          new DefaultRedisScript<>(
                  """
                  local old=tonumber(redis.call('GET',KEYS[1]) or '0')
                  local n=tonumber(ARGV[1])

                  if n>old then
                      redis.call('SET',KEYS[1],n,'PX',ARGV[2])
                  end

                  return 1
                  """,
                  Long.class
          );

  public RedisExtractionGateway(
          StringRedisTemplate redis,
          ExtractionProperties p,
          @Value("${github.api.token:}") String token
  ) {
    this.redis = redis;
    this.p = p;

    this.rateKey =
            "hireai:github:{"
                    + SourceFileScoringEngine.sha256(
                    token.isBlank() ? "shared-public-egress" : token
            )
                    + "}";
  }

  public synchronized void ensureGroup() {
    if (initialized) {
      return;
    }

    try {
      redis.execute(INIT, List.of(p.stream()), p.group());
    } catch (RuntimeException e) {
      if (!causedBy(e, "BUSYGROUP")) {
        throw e;
      }
    }

    initialized = true;
  }

  public void publish(Outbox event) {
    ensureGroup();

    String stream =
            "FAILED".equals(event.kind()) ? p.dlqStream() : p.stream();

    redis.<String, String>opsForStream().add(
            stream,
            Map.of(
                    "eventId", event.id(),
                    "jobId", event.jobId(),
                    "schemaVersion", "1",
                    "type", event.kind()
            )
    );
  }

  public List<MapRecord<String, Object, Object>> read(String consumer) {
    ensureGroup();

    try {
      return readGroup(consumer);
    } catch (RuntimeException e) {
      if (!causedBy(e, "NOGROUP")) {
        throw e;
      }

      initialized = false;
      ensureGroup();

      return readGroup(consumer);
    }
  }

  private List<MapRecord<String, Object, Object>> readGroup(String consumer) {
    List<MapRecord<String, Object, Object>> records =
            redis.<Object, Object>opsForStream().read(
                    Consumer.from(p.group(), consumer),
                    StreamReadOptions.empty()
                            .count(1)
                            .block(Duration.ofMillis(500)),
                    StreamOffset.create(
                            p.stream(),
                            ReadOffset.lastConsumed()
                    )
            );

    return records == null ? List.of() : records;
  }

  public List<Map<String, String>> reclaim(String consumer) {
    ensureGroup();

    List<?> reply;

    try {
      reply = redis.execute(
              RECLAIM,
              List.of(p.stream()),
              p.group(),
              consumer,
              "120000",
              reclaimCursor,
              "10"
      );
    } catch (RuntimeException e) {
      if (causedBy(e, "NOGROUP")) {
        initialized = false;
        ensureGroup();

        return List.of();
      }

      throw e;
    }

    List<Map<String, String>> result = new ArrayList<>();

    if (reply == null
            || reply.size() < 2
            || !(reply.get(1) instanceof List<?> messages)) {
      return result;
    }

    reclaimCursor = text(reply.getFirst());

    for (Object value : messages) {
      if (value instanceof List<?> row
              && row.size() >= 2
              && row.get(1) instanceof List<?> fields) {

        Map<String, String> record = new HashMap<>();
        record.put("messageId", text(row.getFirst()));

        for (int i = 0; i + 1 < fields.size(); i += 2) {
          record.put(
                  text(fields.get(i)),
                  text(fields.get(i + 1))
          );
        }

        result.add(record);
      }
    }

    return result;
  }

  public void ack(String id) {
    redis.opsForStream().acknowledge(
            p.stream(),
            p.group(),
            id
    );
  }

  public AutoCloseable acquire() {
    String id = UUID.randomUUID().toString();

    try {
      List<?> reply = redis.execute(
              ACQUIRE,
              List.of(
                      rateKey + ":bucket",
                      rateKey + ":active",
                      rateKey + ":blocked"
              ),
              String.valueOf(p.tokenCapacity()),
              String.valueOf(p.tokenRefillPerSecond()),
              String.valueOf(p.providerConcurrency()),
              id
      );

      if (reply == null
              || reply.size() < 2
              || !(reply.get(0) instanceof Number granted)
              || !(reply.get(1) instanceof Number delay)) {
        throw new RetryLater(
                "RATE_LIMIT_STORE_UNAVAILABLE",
                Instant.now().plusSeconds(3),
                false
        );
      }

      if (granted.longValue() != 1) {
        throw new RetryLater(
                "GITHUB_LOCAL_RATE_LIMIT",
                Instant.now().plusMillis(
                        Math.max(1000, delay.longValue())
                ),
                false
        );
      }

      return () -> redis.opsForZSet().remove(
              rateKey + ":active",
              id
      );

    } catch (RetryLater e) {
      throw e;
    } catch (RuntimeException e) {
      throw new RetryLater(
              "RATE_LIMIT_STORE_UNAVAILABLE",
              Instant.now().plusSeconds(3),
              false
      );
    }
  }

  public void cooldown(Instant until) {
    long ttl = Math.max(
            1000,
            Duration.between(Instant.now(), until).toMillis() + 60000
    );

    redis.execute(
            COOLDOWN,
            List.of(rateKey + ":blocked"),
            String.valueOf(until.toEpochMilli()),
            String.valueOf(ttl)
    );
  }

  private String text(Object value) {
    return value instanceof byte[] bytes
            ? new String(bytes, StandardCharsets.UTF_8)
            : String.valueOf(value);
  }

  private boolean causedBy(Throwable error, String marker) {
    for (Throwable cause = error;
         cause != null;
         cause = cause.getCause()) {

      if (String.valueOf(cause.getMessage()).contains(marker)) {
        return true;
      }
    }

    return false;
  }
}