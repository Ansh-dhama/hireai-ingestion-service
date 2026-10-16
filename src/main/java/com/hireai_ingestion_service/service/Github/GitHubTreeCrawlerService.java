package com.hireai_ingestion_service.service.Github;

import static com.hireai_ingestion_service.service.Github.GitHubCodeClient.segment;

import com.fasterxml.jackson.databind.JsonNode;

import com.hireai_ingestion_service.service.Validation.ExtractionModels.Claim;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.DiscoveredRepository;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.FileSample;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.Job;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.PermanentFailure;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.RepoState;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.RepositorySnapshot;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.StaleLease;
import com.hireai_ingestion_service.service.Validation.ExtractionModels.WaitingForEvidence;

import com.hireai_ingestion_service.service.Validation.ExtractionProperties;
import com.hireai_ingestion_service.service.Validation.ExtractionStore;

import com.hireai_ingestion_service.service.extraction.ManifestDependencyParser;
import com.hireai_ingestion_service.service.extraction.SourceFileScoringEngine;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

@Service
public class GitHubTreeCrawlerService {
  private final GitHubCodeClient api;
  private final ExtractionStore store;
  private final SourceFileScoringEngine scorer;
  private final ManifestDependencyParser parser;
  private final ExtractionProperties p;

  public GitHubTreeCrawlerService(
      GitHubCodeClient api,
      ExtractionStore store,
      SourceFileScoringEngine scorer,
      ManifestDependencyParser parser,
      ExtractionProperties p) {
    this.api = api;
    this.store = store;
    this.scorer = scorer;
    this.parser = parser;
    this.p = p;
  }

  public void extract(Claim claim) {
    Job job = claim.job();
    var budget = new GitHubCodeClient.Budget(p.maxRequests(), () -> store.reserveRequest(claim));
    if (store.repoStates(job.id()).isEmpty()) store.discover(claim, discover(job, budget));
    for (RepoState initial : store.repoStates(job.id())) {
      if (initial.state().equals("COMPLETED")) continue;
      if (!store.heartbeat(claim)) throw new StaleLease();
      String base = "/repos/" + segment(job.githubUsername()) + "/" + segment(initial.name());
      RepoState repo = initial;
      if (repo.commitSha() == null) {
        JsonNode commit =
            api.get(base + "/commits/" + segment(repo.defaultBranch()), budget, 1_048_576);
        String sha = sha(commit.path("sha").asText()),
            tree = sha(commit.path("commit").path("tree").path("sha").asText());
        store.pin(claim, repo.id(), sha, tree);
        repo =
            store.repoStates(job.id()).stream()
                .filter(x -> x.id().equals(initial.id()))
                .findFirst()
                .orElseThrow();
      }
      int remaining = p.maxTotalBytes() - store.totals(job.id())[2];
      store.acceptRepository(claim, repo, collect(base, repo, budget, remaining));
    }
    store.finish(claim);
  }

  private List<DiscoveredRepository> discover(Job job, GitHubCodeClient.Budget budget) {
    List<DiscoveredRepository> result = new ArrayList<>();
    if (!job.selection().isEmpty()) {
      for (String name : job.selection())
        result.add(
            repository(
                api.get(
                    "/repos/" + segment(job.githubUsername()) + "/" + segment(name),
                    budget,
                    262144)));
    } else {
      // Pagination is bounded. The top selected repositories are an explicit sample.
      for (int page = 1; page <= 3 && result.size() < p.maxRepositories(); page++) {
        JsonNode list =
            api.get(
                "/users/"
                    + segment(job.githubUsername())
                    + "/repos?per_page=100&sort=pushed&direction=desc&page="
                    + page,
                budget,
                4_194_304);
        if (!list.isArray()) throw new PermanentFailure("GITHUB_INVALID_REPOSITORY_RESPONSE");
        for (JsonNode repo : list) {
          if (repo.path("size").asLong() > 0 && !repo.path("disabled").asBoolean())
            result.add(repository(repo));
          if (result.size() >= p.maxRepositories()) break;
        }
        if (list.size() < 100) break;
      }
    }
    if (result.isEmpty()) throw new WaitingForEvidence();
    return result.stream().limit(p.maxRepositories()).toList();
  }

  private DiscoveredRepository repository(JsonNode n) {
    if (n.path("private").asBoolean() || !n.path("visibility").asText("public").equals("public"))
      throw new WaitingForEvidence();
    String name = n.path("name").asText(), branch = n.path("default_branch").asText();
    if (!name.matches("[A-Za-z0-9_.-]{1,100}") || branch.isBlank() || branch.length() > 255)
      throw new PermanentFailure("GITHUB_INVALID_REPOSITORY_METADATA");
    return new DiscoveredRepository(
        name,
        branch,
        n.path("fork").asBoolean(),
        n.path("language").isNull() ? null : n.path("language").asText());
  }

  private RepositorySnapshot collect(
      String base, RepoState repo, GitHubCodeClient.Budget budget, int remaining) {
    JsonNode tree =
        api.get(base + "/git/trees/" + repo.treeSha() + "?recursive=1", budget, 8_388_608);
    boolean truncated = tree.path("truncated").asBoolean();
    List<String> limitations =
        new ArrayList<>(
            List.of(
                "SAMPLED_FILES_NOT_FULL_REPOSITORY",
                "ACCOUNT_CONTROL_AND_AUTHORSHIP_NOT_VERIFIED"));
    Map<String, JsonNode> files = new TreeMap<>();
    boolean treeComplete = true;
    if (!truncated) addFiles(files, tree, "");
    else {
      record Pending(String sha, String prefix) {}
      var queue = new ArrayDeque<Pending>();
      queue.add(new Pending(repo.treeSha(), ""));
      int calls = 0;
      while (!queue.isEmpty() && calls++ < p.maxTreeRequests()) {
        var item = queue.removeFirst();
        JsonNode part = api.get(base + "/git/trees/" + item.sha(), budget, 8_388_608);
        if (part.path("truncated").asBoolean()) {
          treeComplete = false;
          limitations.add("TREE_RESPONSE_TRUNCATED");
        }
        addFiles(files, part, item.prefix());
        for (JsonNode node : part.path("tree"))
          if (node.path("type").asText().equals("tree") && safeTreePath(node.path("path").asText()))
            queue.add(
                new Pending(
                    sha(node.path("sha").asText()),
                    item.prefix() + node.path("path").asText() + "/"));
      }
      if (!queue.isEmpty()) {
        treeComplete = false;
        limitations.add("TREE_TRAVERSAL_BUDGET_REACHED");
      }
    }
    Map<String, Long> sizes = new HashMap<>();
    files.forEach((path, node) -> sizes.put(path, node.path("size").asLong()));
    List<FileSample> samples = new ArrayList<>();
    List<Map<String, Object>> dependencies = new ArrayList<>();
    boolean allSelected = true;
    for (String path : scorer.choose(sizes, p.maxFilesPerRepository())) {
      JsonNode metadata = files.get(path);
      long declared = metadata.path("size").asLong();
      if (declared > remaining || declared > p.maxFileBytes()) {
        allSelected = false;
        limitations.add("SOURCE_BYTE_BUDGET_SKIPPED_FILE");
        continue;
      }
      String blob = sha(metadata.path("sha").asText());
      JsonNode value = api.get(base + "/git/blobs/" + blob, budget, p.maxFileBytes() * 2 + 4096);
      if (!blob.equalsIgnoreCase(value.path("sha").asText()))
        throw new PermanentFailure("GITHUB_BLOB_ID_MISMATCH");
      if (!value.path("encoding").asText().equals("base64")) {
        allSelected = false;
        limitations.add("UNSUPPORTED_BLOB_ENCODING");
        continue;
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(value.path("content").asText().replaceAll("\\s+", ""));
      } catch (IllegalArgumentException e) {
        allSelected = false;
        limitations.add("INVALID_BASE64_BLOB");
        continue;
      }
      if (decoded.length > remaining || decoded.length > p.maxFileBytes()) {
        allSelected = false;
        limitations.add("DECODED_BYTE_LIMIT");
        continue;
      }
      if (decoded.length != declared) {
        allSelected = false;
        limitations.add("BLOB_SIZE_MISMATCH");
        continue;
      }
      String raw;
      try {
        raw =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString();
      } catch (CharacterCodingException e) {
        allSelected = false;
        limitations.add("NON_UTF8_FILE_SKIPPED");
        continue;
      }
      if (raw.indexOf(0) >= 0) {
        allSelected = false;
        limitations.add("BINARY_FILE_SKIPPED");
        continue;
      }
      var safe = scorer.redact(raw);
      int lines = raw.isEmpty() ? 0 : raw.split("\\R", -1).length;
      samples.add(
          new FileSample(
              path,
              scorer.category(path),
              blob,
              SourceFileScoringEngine.sha256(safe.text()),
              safe.text(),
              decoded.length,
              lines,
              safe.matches(),
              scorer.score(path, decoded.length)));
      if (scorer.category(path).equals("MANIFEST"))
        dependencies.addAll(parser.parse(path, safe.text()));
      remaining -= decoded.length;
    }
    return new RepositorySnapshot(
        repo.name(),
        base.substring("/repos/".length()),
        repo.commitSha(),
        repo.treeSha(),
        repo.fork(),
        repo.defaultBranch(),
        repo.language(),
        truncated,
        treeComplete && allSelected,
        limitations.stream().distinct().toList(),
        dependencies,
        samples);
  }

  private void addFiles(Map<String, JsonNode> files, JsonNode tree, String prefix) {
    for (JsonNode node : tree.path("tree")) {
      String path = prefix + node.path("path").asText();
      if (node.path("type").asText().equals("blob")
          && Set.of("100644", "100755").contains(node.path("mode").asText())
          && safeTreePath(path)
          && scorer.score(path, node.path("size").asLong()) >= 0) files.put(path, node);
    }
  }

  private boolean safeTreePath(String path) {
    if (path.startsWith("/") || path.contains("\\") || path.length() > 1024) return false;
    for (String part : path.split("/"))
      if (Set.of("..", "node_modules", "vendor", "target", "build", "dist", ".git").contains(part))
        return false;
    return true;
  }

  private String sha(String s) {
    if (!s.matches("[a-fA-F0-9]{40,64}")) throw new PermanentFailure("GITHUB_INVALID_OBJECT_ID");
    return s;
  }
}