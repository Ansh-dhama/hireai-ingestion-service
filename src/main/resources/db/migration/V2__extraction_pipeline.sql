CREATE TABLE candidate_access_tokens (
                                         token_hash CHAR(64) NOT NULL,
                                         candidate_id CHAR(36) NOT NULL,
                                         expires_at DATETIME(6) NOT NULL,

                                         PRIMARY KEY (token_hash),
                                         KEY ix_token_candidate (candidate_id),

                                         FOREIGN KEY (candidate_id)
                                             REFERENCES candidates(id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE extraction_jobs (
                                 id VARCHAR(36) NOT NULL,
                                 candidate_id CHAR(36) NOT NULL,
                                 github_username VARCHAR(100) NULL,

                                 run_number INT NOT NULL,
                                 status VARCHAR(24) NOT NULL,
                                 attempt_count INT NOT NULL DEFAULT 0,
                                 max_attempts INT NOT NULL,
                                 requests_used INT NOT NULL DEFAULT 0,

                                 selection_json JSON NOT NULL,
                                 consent_notice_version VARCHAR(80) NOT NULL,

                                 request_key VARCHAR(128)
                                        CHARACTER SET ascii COLLATE ascii_bin NULL,
                                 request_hash CHAR(64) NOT NULL,

                                 lease_owner VARCHAR(80) NULL,
                                 lease_until DATETIME(6) NULL,
                                 fencing_token BIGINT NOT NULL DEFAULT 0,

                                 available_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),
                                 last_notification_at DATETIME(6) NULL,
                                 last_error_code VARCHAR(100) NULL,

                                 created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),
                                 completed_at DATETIME(6) NULL,

                                 active_candidate_id VARCHAR(36)
                                     GENERATED ALWAYS AS (
                                         CASE
                                             WHEN status IN (
                                                             'QUEUED',
                                                             'RUNNING',
                                                             'RETRY_WAIT',
                                                             'WAITING_INPUT'
                                                 )
                                                 THEN candidate_id
                                             ELSE NULL
                                             END
                                         ) STORED,

                                 PRIMARY KEY (id),

                                 UNIQUE KEY uq_active_extraction (active_candidate_id),
                                 UNIQUE KEY uq_extraction_run (candidate_id, run_number),
                                 UNIQUE KEY uq_extraction_request (candidate_id, request_key),

                                 KEY ix_extraction_due (status, available_at),
                                 KEY ix_extraction_lease (status, lease_until),

                                 FOREIGN KEY (candidate_id)
                                     REFERENCES candidates(id),

                                 CHECK (
                                     status IN (
                                                'QUEUED',
                                                'RUNNING',
                                                'RETRY_WAIT',
                                                'WAITING_INPUT',
                                                'COMPLETED',
                                                'FAILED',
                                                'CANCELLED'
                                         )
                                     )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE extraction_outbox (
                                   id VARCHAR(36) NOT NULL,
                                   job_id VARCHAR(36) NOT NULL,
                                   event_type VARCHAR(30) NOT NULL,
                                   status VARCHAR(16) NOT NULL DEFAULT 'READY',

                                   lease_owner VARCHAR(80) NULL,
                                   lease_until DATETIME(6) NULL,

                                   created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),
                                   published_at DATETIME(6) NULL,

                                   PRIMARY KEY (id),
                                   KEY ix_outbox_ready (status, created_at),

                                   FOREIGN KEY (job_id)
                                       REFERENCES extraction_jobs(id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE candidate_repositories (
                                        id VARCHAR(36) NOT NULL,
                                        job_id VARCHAR(36) NOT NULL,
                                        candidate_id CHAR(36) NOT NULL,

                                        repo_name VARCHAR(100) NOT NULL,
                                        full_name VARCHAR(255) NOT NULL,
                                        default_branch VARCHAR(255) NOT NULL,

                                        commit_sha VARCHAR(64) NULL,
                                        tree_sha VARCHAR(64) NULL,
                                        is_fork BOOLEAN NOT NULL,
                                        primary_language VARCHAR(100) NULL,

                                        ownership_status VARCHAR(30) NOT NULL
                                                                    DEFAULT 'UNCONFIRMED',
                                        status VARCHAR(24) NOT NULL DEFAULT 'PENDING',

                                        coverage_json JSON NOT NULL,
                                        detected_dependencies JSON NOT NULL,

                                        created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

                                        PRIMARY KEY (id),

                                        UNIQUE KEY uq_repo_job (job_id, repo_name),
                                        UNIQUE KEY uq_repo_context (id, job_id, candidate_id),

                                        FOREIGN KEY (job_id)
                                            REFERENCES extraction_jobs(id),

                                        FOREIGN KEY (candidate_id)
                                            REFERENCES candidates(id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE candidate_code_samples (
                                        id VARCHAR(36) NOT NULL,
                                        repository_id VARCHAR(36) NOT NULL,
                                        job_id VARCHAR(36) NOT NULL,
                                        candidate_id CHAR(36) NOT NULL,

                                        file_path VARCHAR(1024) NOT NULL,
                                        path_sha256 CHAR(64) NOT NULL,
                                        file_category VARCHAR(30) NOT NULL,

                                        blob_sha VARCHAR(64) NOT NULL,
                                        content_sha256 CHAR(64) NOT NULL,
                                        code_content LONGTEXT NOT NULL,

                                        file_size_bytes INT NOT NULL,
                                        line_count INT NOT NULL,
                                        redaction_count INT NOT NULL,
                                        signal_score INT NOT NULL,

                                        created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

                                        PRIMARY KEY (id),

                                        UNIQUE KEY uq_sample_path (
                                            repository_id,
                                            path_sha256
                                            ),

                                        FOREIGN KEY (repository_id, job_id, candidate_id)
                                            REFERENCES candidate_repositories (
                                                                               id,
                                                                               job_id,
                                                                               candidate_id
                                                )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE extraction_consent_events (
                                           id VARCHAR(36) NOT NULL,
                                           candidate_id CHAR(36) NOT NULL,
                                           job_id VARCHAR(36) NOT NULL,

                                           purpose VARCHAR(80) NOT NULL,
                                           notice_version VARCHAR(80) NOT NULL,

                                           recorded_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

                                           PRIMARY KEY (id),

                                           FOREIGN KEY (job_id)
                                               REFERENCES extraction_jobs(id),

                                           FOREIGN KEY (candidate_id)
                                               REFERENCES candidates(id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;