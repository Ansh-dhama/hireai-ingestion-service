package com.hireai_ingestion_service.service.Validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// Imports nested types declared inside ExtractionModels.
import com.hireai_ingestion_service.service.Validation.ExtractionModels.*;

// SourceFileScoringEngine belongs to the extraction package.
import com.hireai_ingestion_service.service.extraction.SourceFileScoringEngine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import java.time.Duration;
import java.time.Instant;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ExtractionStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ExtractionProperties p;

    public ExtractionStore(
            JdbcTemplate db, PlatformTransactionManager tm, ObjectMapper mapper, ExtractionProperties p) {
        this.db = db;
        tx = new TransactionTemplate(tm);
        this.mapper = mapper;
        this.p = p;
    }

    public Job enqueue(
            String candidateId,
            String username,
            List<String> selected,
            boolean consent,
            String notice,
            String key) {
        List<String> selection =
                selected == null ? List.of() : selected.stream().distinct().sorted().toList();
        String hash = SourceFileScoringEngine.sha256(username + json(selection) + consent + notice);
        return tx.execute(
                t -> {
                    if (db.queryForList("SELECT id FROM candidates WHERE id=? FOR UPDATE", candidateId)
                            .isEmpty())
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found");
                    if (key != null) {
                        List<Job> replay =
                                jobs(
                                        "SELECT * FROM extraction_jobs WHERE candidate_id=? AND request_key=?",
                                        candidateId,
                                        key);
                        if (!replay.isEmpty()) return sameRequest(replay.getFirst(), hash);
                    }
                    List<Job> active =
                            jobs("SELECT * FROM extraction_jobs WHERE active_candidate_id=?", candidateId);
                    if (!active.isEmpty()) {
                        Job old = active.getFirst();
                        if (!old.state().equals("WAITING_INPUT")) {
                            sameRequest(old, hash);
                            if (key != null) {
                                String previous =
                                        db.queryForObject(
                                                "SELECT request_key FROM extraction_jobs WHERE id=?",
                                                String.class,
                                                old.id());
                                if (previous != null && !previous.equals(key))
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT, "Active extraction already has another request key");
                                if (previous == null)
                                    db.update("UPDATE extraction_jobs SET request_key=? WHERE id=?", key, old.id());
                            }
                            return old;
                        }
                        db.update(
                                "UPDATE extraction_jobs SET"
                                        + " status='CANCELLED',completed_at=UTC_TIMESTAMP(6),fencing_token=fencing_token+1"
                                        + " WHERE id=?",
                                old.id());
                    }
                    int run =
                            db.queryForObject(
                                    "SELECT COALESCE(MAX(run_number),0)+1 FROM extraction_jobs WHERE candidate_id=?",
                                    Integer.class,
                                    candidateId);
                    String id = UUID.randomUUID().toString();
                    boolean ready = consent && username != null && !username.isBlank();
                    db.update(
                            "INSERT INTO"
                                    + " extraction_jobs(id,candidate_id,github_username,run_number,status,max_attempts,selection_json,consent_notice_version,request_key,request_hash,last_error_code)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            candidateId,
                            username,
                            run,
                            ready ? "QUEUED" : "WAITING_INPUT",
                            p.maxAttempts(),
                            json(selection),
                            notice,
                            key,
                            hash,
                            ready ? null : consent ? "GITHUB_HANDLE_REQUIRED" : "EVIDENCE_CONSENT_REQUIRED");
                    if (consent)
                        db.update(
                                "INSERT INTO"
                                        + " extraction_consent_events(id,candidate_id,job_id,purpose,notice_version)"
                                        + " VALUES(?,?,?,'PUBLIC_GITHUB_COLLECTION',?)",
                                UUID.randomUUID().toString(),
                                candidateId,
                                id,
                                notice);
                    db.update(
                            "UPDATE candidates SET github_username=?,evidence_consent_given=? WHERE id=?",
                            username,
                            consent,
                            candidateId);
                    candidateState(candidateId, ready ? "QUEUED" : "WAITING_EVIDENCE");
                    if (ready) outbox(id, "EXTRACT");
                    return job(id);
                });
    }

    private ExtractionModels.Job sameRequest(ExtractionModels.Job old, String hash) {
        if (!old.requestHash().equals(hash))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Another extraction request already uses this key or active application");
        return old;
    }

    public Optional<Claim> claim(String id, String owner) {
        return tx.execute(
                t -> {
                    List<Job> rows = jobs("SELECT * FROM extraction_jobs WHERE id=? FOR UPDATE", id);
                    if (rows.isEmpty()) return Optional.empty();
                    Job old = rows.getFirst();
                    if (!Set.of("QUEUED", "RETRY_WAIT").contains(old.state())
                            || old.availableAt().isAfter(now())) return Optional.empty();
                    db.update(
                            "UPDATE extraction_jobs SET"
                                    + " status='RUNNING',attempt_count=attempt_count+1,fencing_token=fencing_token+1,lease_owner=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))"
                                    + " WHERE id=?",
                            owner,
                            p.leaseSeconds(),
                            id);
                    candidateState(old.candidateId(), "EXTRACTING_CODE");
                    Job current = job(id);
                    return Optional.of(new Claim(current, owner, current.fencingToken()));
                });
    }

    public boolean heartbeat(Claim c) {
        return db.update(
                "UPDATE extraction_jobs SET lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) WHERE"
                        + " id=? AND status='RUNNING' AND lease_owner=? AND fencing_token=? AND"
                        + " lease_until>UTC_TIMESTAMP(6)",
                p.leaseSeconds(),
                c.job().id(),
                c.owner(),
                c.fence())
                == 1;
    }

    public void reserveRequest(Claim c) {
        tx.executeWithoutResult(
                t -> {
                    locked(c);
                    if (db.update(
                            "UPDATE extraction_jobs SET requests_used=requests_used+1 WHERE id=? AND"
                                    + " requests_used<?",
                            c.job().id(),
                            p.maxRequests())
                            != 1) throw new PermanentFailure("COLLECTION_REQUEST_BUDGET_EXCEEDED");
                });
    }

    private Job locked(Claim c) {
        Job j = jobForUpdate(c.job().id());
        if (!j.state().equals("RUNNING")
                || !Objects.equals(j.leaseOwner(), c.owner())
                || j.fencingToken() != c.fence()
                || j.leaseUntil() == null
                || !j.leaseUntil().isAfter(now())) throw new StaleLease();
        return j;
    }

    public void discover(Claim c, List<DiscoveredRepository> repos) {
        tx.executeWithoutResult(
                t -> {
                    locked(c);
                    for (var r : repos) {
                        int exists =
                                db.queryForObject(
                                        "SELECT COUNT(*) FROM candidate_repositories WHERE job_id=? AND repo_name=?",
                                        Integer.class,
                                        c.job().id(),
                                        r.name());
                        if (exists == 0)
                            db.update(
                                    "INSERT INTO"
                                            + " candidate_repositories(id,job_id,candidate_id,repo_name,full_name,default_branch,is_fork,primary_language,coverage_json,detected_dependencies)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                                    UUID.randomUUID().toString(),
                                    c.job().id(),
                                    c.job().candidateId(),
                                    r.name(),
                                    c.job().githubUsername() + "/" + r.name(),
                                    r.defaultBranch(),
                                    r.fork(),
                                    r.language(),
                                    "{}",
                                    "[]");
                    }
                });
    }

    public List<RepoState> repoStates(String jobId) {
        return db.query(
                "SELECT * FROM candidate_repositories WHERE job_id=? ORDER BY repo_name",
                (r, n) ->
                        new RepoState(
                                r.getString("id"),
                                r.getString("repo_name"),
                                r.getString("default_branch"),
                                r.getBoolean("is_fork"),
                                r.getString("primary_language"),
                                r.getString("commit_sha"),
                                r.getString("tree_sha"),
                                r.getString("status")),
                jobId);
    }

    public void pin(Claim c, String repoId, String commit, String tree) {
        tx.executeWithoutResult(
                t -> {
                    locked(c);
                    db.update(
                            "UPDATE candidate_repositories SET commit_sha=?,tree_sha=? WHERE id=? AND job_id=?"
                                    + " AND commit_sha IS NULL",
                            commit,
                            tree,
                            repoId,
                            c.job().id());
                });
    }

    public void acceptRepository(Claim c, RepoState repo, RepositorySnapshot result) {
        tx.executeWithoutResult(
                t -> {
                    locked(c);
                    var actual =
                            repoStates(c.job().id()).stream()
                                    .filter(x -> x.id().equals(repo.id()))
                                    .findFirst()
                                    .orElseThrow();
                    if (actual.state().equals("COMPLETED")) return;
                    if (!Objects.equals(actual.commitSha(), result.commitSha())) throw new StaleLease();
                    int newBytes = result.files().stream().mapToInt(FileSample::decodedBytes).sum();
                    if (result.files().size() > p.maxFilesPerRepository()
                            || totals(c.job().id())[2] + newBytes > p.maxTotalBytes())
                        throw new PermanentFailure("SOURCE_BUDGET_EXCEEDED");
                    for (var f : result.files())
                        db.update(
                                "INSERT INTO"
                                        + " candidate_code_samples(id,repository_id,job_id,candidate_id,file_path,path_sha256,file_category,blob_sha,content_sha256,code_content,file_size_bytes,line_count,redaction_count,signal_score)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                UUID.randomUUID().toString(),
                                repo.id(),
                                c.job().id(),
                                c.job().candidateId(),
                                f.path(),
                                SourceFileScoringEngine.sha256(f.path()),
                                f.category(),
                                f.blobSha(),
                                f.contentSha256(),
                                f.content(),
                                f.decodedBytes(),
                                f.lineCount(),
                                f.redactions(),
                                f.score());
                    Map<String, Object> coverage = new LinkedHashMap<>();
                    coverage.put("treeTruncated", result.truncated());
                    coverage.put("selectedSampleComplete", result.complete());
                    coverage.put("limitations", result.limitations());
                    coverage.put("sampledFiles", result.files().size());
                    coverage.put("decodedBytes", newBytes);
                    db.update(
                            "UPDATE candidate_repositories SET"
                                    + " status='COMPLETED',coverage_json=?,detected_dependencies=? WHERE id=?",
                            json(coverage),
                            json(result.dependencies()),
                            repo.id());
                });
    }

    public void finish(Claim c) {
        tx.executeWithoutResult(
                t -> {
                    locked(c);
                    boolean hasFiles = totals(c.job().id())[1] > 0;
                    db.update(
                            "UPDATE extraction_jobs SET"
                                    + " status=?,last_error_code=?,completed_at=UTC_TIMESTAMP(6),lease_owner=NULL,lease_until=NULL"
                                    + " WHERE id=?",
                            hasFiles ? "COMPLETED" : "WAITING_INPUT",
                            hasFiles ? null : "NO_ACCESSIBLE_PUBLIC_EVIDENCE",
                            c.job().id());
                    candidateState(c.job().candidateId(), hasFiles ? "EXTRACTED" : "WAITING_EVIDENCE");
                });
    }

    public void failOrRetry(Claim c, RuntimeException error) {
        tx.executeWithoutResult(
                t -> {
                    Job j = locked(c);
                    if (error instanceof WaitingForEvidence) {
                        db.update(
                                "UPDATE extraction_jobs SET"
                                        + " status='WAITING_INPUT',last_error_code='NO_ACCESSIBLE_PUBLIC_EVIDENCE',lease_owner=NULL,lease_until=NULL"
                                        + " WHERE id=?",
                                j.id());
                        candidateState(j.candidateId(), "WAITING_EVIDENCE");
                        return;
                    }
                    boolean defer = error instanceof RetryLater x && !x.countAttempt;
                    String code =
                            error instanceof RetryLater x
                                    ? x.code
                                    : error instanceof PermanentFailure x ? x.code : "EXTRACTION_TEMPORARY_FAILURE";
                    boolean terminal =
                            error instanceof PermanentFailure
                                    || (!defer && j.attempts() >= j.maxAttempts())
                                    || j.createdAt().isBefore(now().minus(Duration.ofHours(24)));
                    if (terminal) {
                        terminal(j.id(), j.candidateId(), code);
                        return;
                    }
                    Instant next =
                            error instanceof RetryLater x
                                    ? x.availableAt
                                    : now()
                                    .plusSeconds(
                                            Math.min(300, 5L * (1L << Math.min(j.attempts(), 6)))
                                                    + new Random().nextInt(5));
                    db.update(
                            "UPDATE extraction_jobs SET"
                                    + " status='RETRY_WAIT',attempt_count=attempt_count-?,available_at=?,last_error_code=?,lease_owner=NULL,lease_until=NULL"
                                    + " WHERE id=?",
                            defer ? 1 : 0,
                            Timestamp.from(next),
                            code,
                            j.id());
                    candidateState(j.candidateId(), "EXTRACTION_RETRY");
                    outbox(j.id(), "EXTRACT");
                });
    }

    private void terminal(String jobId, String candidate, String code) {
        db.update(
                "UPDATE extraction_jobs SET"
                        + " status='FAILED',last_error_code=?,completed_at=UTC_TIMESTAMP(6),lease_owner=NULL,lease_until=NULL,fencing_token=fencing_token+1"
                        + " WHERE id=?",
                code,
                jobId);
        candidateState(candidate, "EXTRACTION_FAILED");
        outbox(jobId, "FAILED");
    }

    public void reconcile() {
        tx.executeWithoutResult(
                t -> {
                    var expired =
                            jobs(
                                    "SELECT * FROM extraction_jobs WHERE status='RUNNING' AND"
                                            + " lease_until<=UTC_TIMESTAMP(6) LIMIT 20 FOR UPDATE SKIP LOCKED");
                    for (Job j : expired) {
                        if (j.attempts() >= j.maxAttempts())
                            terminal(j.id(), j.candidateId(), "WORKER_LEASE_EXPIRED");
                        else {
                            db.update(
                                    "UPDATE extraction_jobs SET"
                                            + " status='RETRY_WAIT',available_at=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(6)),lease_owner=NULL,lease_until=NULL,fencing_token=fencing_token+1,last_error_code='WORKER_LEASE_EXPIRED'"
                                            + " WHERE id=?",
                                    j.id());
                            candidateState(j.candidateId(), "EXTRACTION_RETRY");
                            outbox(j.id(), "EXTRACT");
                        }
                    }
                    var due =
                            jobs(
                                    "SELECT j.* FROM extraction_jobs j WHERE j.status IN ('QUEUED','RETRY_WAIT') AND"
                                            + " j.available_at<=UTC_TIMESTAMP(6) AND (j.last_notification_at IS NULL OR"
                                            + " j.last_notification_at<TIMESTAMPADD(SECOND,-30,UTC_TIMESTAMP(6))) AND NOT"
                                            + " EXISTS(SELECT 1 FROM extraction_outbox o WHERE o.job_id=j.id AND"
                                            + " (o.status='READY' OR (o.status='PUBLISHING' AND"
                                            + " o.lease_until>UTC_TIMESTAMP(6)))) LIMIT 20 FOR UPDATE SKIP LOCKED");
                    for (Job j : due) outbox(j.id(), "EXTRACT");
                });
    }

    private void outbox(String id, String type) {
        db.update(
                "INSERT INTO extraction_outbox(id,job_id,event_type) VALUES(?,?,?)",
                UUID.randomUUID().toString(),
                id,
                type);
    }

    public Optional<Outbox> claimOutbox(String owner) {
        return tx.execute(
                t -> {
                    var rows =
                            db.queryForList(
                                    "SELECT o.* FROM extraction_outbox o WHERE (o.status='READY' OR"
                                            + " (o.status='PUBLISHING' AND o.lease_until<=UTC_TIMESTAMP(6))) AND"
                                            + " (o.event_type='FAILED' OR EXISTS(SELECT 1 FROM extraction_jobs j WHERE"
                                            + " j.id=o.job_id AND j.status IN ('QUEUED','RETRY_WAIT') AND"
                                            + " j.available_at<=UTC_TIMESTAMP(6))) ORDER BY o.created_at LIMIT 1 FOR"
                                            + " UPDATE SKIP LOCKED");
                    if (rows.isEmpty()) return Optional.empty();
                    var r = rows.getFirst();
                    String id = (String) r.get("id");
                    db.update(
                            "UPDATE extraction_outbox SET"
                                    + " status='PUBLISHING',lease_owner=?,lease_until=TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(6))"
                                    + " WHERE id=?",
                            owner,
                            id);
                    return Optional.of(
                            new ExtractionModels.Outbox(id, (String) r.get("job_id"), (String) r.get("event_type"), owner));
                });
    }

    public void published(ExtractionModels.Outbox event) {
        tx.executeWithoutResult(
                t -> {
                    int changed =
                            db.update(
                                    "UPDATE extraction_outbox SET"
                                            + " status='PUBLISHED',published_at=UTC_TIMESTAMP(6),lease_owner=NULL,lease_until=NULL"
                                            + " WHERE id=? AND status='PUBLISHING' AND lease_owner=?",
                                    event.id(),
                                    event.owner());
                    if (changed == 1)
                        db.update(
                                "UPDATE extraction_jobs SET last_notification_at=UTC_TIMESTAMP(6) WHERE id=?",
                                event.jobId());
                });
    }

    public ExtractionModels.Job latest(String candidate) {
        var rows =
                jobs(
                        "SELECT * FROM extraction_jobs WHERE candidate_id=? ORDER BY run_number DESC LIMIT 1",
                        candidate);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No extraction job exists");
        return rows.getFirst();
    }

    public Status status(String candidate) {
        return status(latest(candidate));
    }

    public Status status(Job j) {
        int[] n = totals(j.id());
        return new Status(
                j.id(),
                j.candidateId(),
                j.runNumber(),
                j.state(),
                j.attempts(),
                j.availableAt(),
                j.errorCode(),
                n[0],
                n[1],
                n[2],
                j.completedAt());
    }

    public int[] totals(String jobId) {
        int repos =
                db.queryForObject(
                        "SELECT COUNT(*) FROM candidate_repositories WHERE job_id=? AND status='COMPLETED'",
                        Integer.class,
                        jobId);
        var r =
                db.queryForMap(
                        "SELECT COUNT(*) AS files,COALESCE(SUM(file_size_bytes),0) AS bytes FROM"
                                + " candidate_code_samples WHERE job_id=?",
                        jobId);
        return new int[] {
                repos, ((Number) r.get("files")).intValue(), ((Number) r.get("bytes")).intValue()
        };
    }

    public List<Map<String, Object>> repositories(String candidate) {
        var rows =
                db.queryForList(
                        "SELECT"
                                + " id,repo_name,full_name,commit_sha,tree_sha,is_fork,ownership_status,status,coverage_json,detected_dependencies"
                                + " FROM candidate_repositories WHERE job_id=? ORDER BY repo_name",
                        latest(candidate).id());
        for (var r : rows) {
            r.put("coverage_json", readJson(r.get("coverage_json")));
            r.put("detected_dependencies", readJson(r.get("detected_dependencies")));
        }
        return rows;
    }

    public List<Map<String, Object>> samples(String candidate, boolean content) {
        String columns =
                "id,repository_id,file_path,file_category,blob_sha,content_sha256,file_size_bytes,line_count,redaction_count,signal_score"
                        + (content ? ",code_content" : "");
        return db.queryForList(
                "SELECT "
                        + columns
                        + " FROM candidate_code_samples WHERE job_id=? ORDER BY repository_id,file_path LIMIT"
                        + " 15",
                latest(candidate).id());
    }

    private void candidateState(String id, String state) {
        db.update(
                "UPDATE candidates SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", state, id);
    }

    private ExtractionModels.Job job(String id) {
        return jobs("SELECT * FROM extraction_jobs WHERE id=?", id).getFirst();
    }

    private ExtractionModels.Job jobForUpdate(String id) {
        return jobs("SELECT * FROM extraction_jobs WHERE id=? FOR UPDATE", id).getFirst();
    }

    private List<ExtractionModels.Job> jobs(String sql, Object... args) {
        return db.query(sql, (r, n) -> map(r), args);
    }

    private ExtractionModels.Job map(ResultSet r) throws SQLException {
        return new ExtractionModels.Job(
                r.getString("id"),
                r.getString("candidate_id"),
                r.getString("github_username"),
                r.getInt("run_number"),
                r.getString("status"),
                r.getInt("attempt_count"),
                r.getInt("max_attempts"),
                r.getLong("fencing_token"),
                r.getString("lease_owner"),
                instant(r, "lease_until"),
                instant(r, "available_at"),
                strings(r.getString("selection_json")),
                r.getString("request_hash"),
                r.getString("last_error_code"),
                instant(r, "created_at"),
                instant(r, "completed_at"));
    }

    private Instant now() {
        return db.queryForObject("SELECT UTC_TIMESTAMP(6)", Timestamp.class).toInstant();
    }

    private Instant instant(ResultSet r, String key) throws SQLException {
        Timestamp t = r.getTimestamp(key);
        return t == null ? null : t.toInstant();
    }

    private List<String> strings(String s) {
        try {
            return mapper.readValue(s, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid persisted selection");
        }
    }

    private Object readJson(Object s) {
        try {
            return mapper.readValue(String.valueOf(s), Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid persisted JSON");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON value");
        }
    }
}