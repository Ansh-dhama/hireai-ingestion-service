-- Add consent timestamp if missing.
SET @hireai_consent_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'candidates'
          AND column_name = 'consent_given_at'
    ),
    'SELECT 1',
    'ALTER TABLE candidates
     ADD COLUMN consent_given_at TIMESTAMP(6) NULL DEFAULT NULL'
);

PREPARE hireai_consent_stmt FROM @hireai_consent_sql;
EXECUTE hireai_consent_stmt;
DEALLOCATE PREPARE hireai_consent_stmt;


-- Add consent notice version if missing.
SET @hireai_consent_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'candidates'
          AND column_name = 'consent_notice_version'
    ),
    'SELECT 1',
    'ALTER TABLE candidates
     ADD COLUMN consent_notice_version VARCHAR(80) NULL DEFAULT NULL'
);

PREPARE hireai_consent_stmt FROM @hireai_consent_sql;
EXECUTE hireai_consent_stmt;
DEALLOCATE PREPARE hireai_consent_stmt;


-- Add GitHub evidence consent flag if missing.
SET @hireai_consent_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'candidates'
          AND column_name = 'evidence_consent_given'
    ),
    'SELECT 1',
    'ALTER TABLE candidates
     ADD COLUMN evidence_consent_given BOOLEAN NOT NULL DEFAULT FALSE'
);

PREPARE hireai_consent_stmt FROM @hireai_consent_sql;
EXECUTE hireai_consent_stmt;
DEALLOCATE PREPARE hireai_consent_stmt;