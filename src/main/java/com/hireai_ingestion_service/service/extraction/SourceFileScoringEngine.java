package com.hireai_ingestion_service.service.extraction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SourceFileScoringEngine {
  private static final Set<String> IGNORED =
          Set.of(
                  "node_modules",
                  "target",
                  "build",
                  "dist",
                  "vendor",
                  ".git",
                  ".idea",
                  "__pycache__",
                  "coverage");
  private static final Set<String> EXT =
          Set.of(
                  "java", "kt", "py", "ts", "js", "go", "rs", "sql", "xml", "gradle", "kts", "json", "yml",
                  "yaml");
  private static final List<Pattern> SECRETS =
          List.of(
                  Pattern.compile("gh[pousr]_[A-Za-z0-9_]{20,}"),
                  Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
                  Pattern.compile("AKIA[A-Z0-9]{16}"),
                  Pattern.compile(
                          "(?i)(?:password|api[_-]?key|secret|access[_-]?token)\\s*[=:]\\s*[\"'][^\"'\\r"
                                  + "\\n"
                                  + "]{8,}[\"']"),
                  Pattern.compile(
                          "(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH"
                                  + " )?PRIVATE KEY-----"),
                  Pattern.compile("https?://[^\\s/:]+:[^\\s/@]+@"));

  public record Redacted(String text, int matches) {}

  public String category(String path) {
    String s = path.toLowerCase(Locale.ROOT);
    String base = s.substring(s.lastIndexOf('/') + 1);
    if (Set.of("pom.xml", "package.json", "build.gradle", "build.gradle.kts", "requirements.txt")
            .contains(base)
            || base.matches("requirements[-.].*\\.txt")) return "MANIFEST";
    if (s.contains("test") || s.contains("spec")) return "TEST";
    if (s.contains("security") || s.contains("exception") || s.contains("filter"))
      return "SECURITY";
    if (s.contains("migration")
            || s.contains("repository")
            || s.contains("dao")
            || s.endsWith(".sql")) return "DATA";
    if (s.contains("service")
            || s.contains("worker")
            || s.contains("engine")
            || s.contains("domain")) return "SERVICE";
    if (s.contains("controller") || s.contains("router")) return "API";
    return "OTHER";
  }

  public int score(String path, long size) {
    String s = path.toLowerCase(Locale.ROOT);
    if (path.length() > 1024
            || size <= 0
            || size > 65536
            || s.endsWith(".min.js")
            || s.endsWith("-lock.json")
            || s.endsWith(".lock")) return -1;
    for (String segment : s.split("/")) if (IGNORED.contains(segment)) return -1;
    String c = category(path);
    int dot = s.lastIndexOf('.');
    if (!c.equals("MANIFEST") && (dot < 0 || !EXT.contains(s.substring(dot + 1)))) return -1;
    return switch (c) {
      case "MANIFEST" -> 95;
      case "SERVICE" -> 85;
      case "SECURITY" -> 80;
      case "DATA" -> 75;
      case "TEST" -> 70;
      case "API" -> 55;
      default -> 25;
    };
  }

  public List<String> choose(Map<String, Long> paths, int maximum) {
    var ranked =
            paths.entrySet().stream()
                    .filter(e -> score(e.getKey(), e.getValue()) >= 0)
                    .sorted(
                            Comparator.<Map.Entry<String, Long>>comparingInt(
                                            e -> score(e.getKey(), e.getValue()))
                                    .reversed()
                                    .thenComparing(Map.Entry::getKey))
                    .toList();
    Set<String> selected = new LinkedHashSet<>();
    for (String category : List.of("MANIFEST", "SERVICE", "SECURITY", "DATA", "TEST")) {
      if (selected.size() >= maximum) break;
      ranked.stream()
              .filter(e -> category(e.getKey()).equals(category))
              .findFirst()
              .ifPresent(e -> selected.add(e.getKey()));
    }
    for (var e : ranked) {
      if (selected.size() >= maximum) break;
      selected.add(e.getKey());
    }
    return List.copyOf(selected);
  }

  public Redacted redact(String input) {
    int count = 0;
    String text = input;
    for (Pattern pattern : SECRETS) {
      var m = pattern.matcher(text);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        count++;
        m.appendReplacement(
                out, java.util.regex.Matcher.quoteReplacement(m.group().replaceAll("[^\\r\\n]", "*")));
      }
      m.appendTail(out);
      text = out.toString();
    }
    return new Redacted(text, count);
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of()
              .formatHex(
                      MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}