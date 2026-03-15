-- =========================================================
-- 챕터 1 더미 데이터 삽입 프로시저 (스키마 정합 버전)
-- =========================================================

USE hipster;

-- ---------------------------------------------------------
-- STEP 1. 기준 데이터 삽입 (기존 데이터 있으면 건너뜀)
-- ---------------------------------------------------------

-- Artist 1000명
INSERT INTO artists (name, status, created_at, updated_at)
WITH RECURSIVE nums (n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 1000
)
SELECT
    CONCAT('Artist_', n),
    'ACTIVE',
    NOW(),
    NOW()
FROM nums;

-- Genre (10개)
INSERT INTO genres (name, status, created_at, updated_at) VALUES
('Rock',        'ACTIVE', NOW(), NOW()),
('Pop',         'ACTIVE', NOW(), NOW()),
('Electronic',  'ACTIVE', NOW(), NOW()),
('Jazz',        'ACTIVE', NOW(), NOW()),
('Classical',   'ACTIVE', NOW(), NOW()),
('Hip-hop',     'ACTIVE', NOW(), NOW()),
('R&B',         'ACTIVE', NOW(), NOW()),
('Country',     'ACTIVE', NOW(), NOW()),
('Metal',       'ACTIVE', NOW(), NOW()),
('Folk',        'ACTIVE', NOW(), NOW());

-- Descriptor (10개)
INSERT INTO descriptors (name, created_at, updated_at) VALUES
('Atmospheric', NOW(), NOW()),
('Energetic',   NOW(), NOW()),
('Melancholic', NOW(), NOW()),
('Experimental',NOW(), NOW()),
('Upbeat',      NOW(), NOW()),
('Dark',        NOW(), NOW()),
('Romantic',    NOW(), NOW()),
('Epic',        NOW(), NOW()),
('Minimalist',  NOW(), NOW()),
('Groovy',      NOW(), NOW());

-- Location (10개)
INSERT INTO locations (name, created_at, updated_at) VALUES
('United States', NOW(), NOW()),
('United Kingdom',NOW(), NOW()),
('Japan',         NOW(), NOW()),
('Germany',       NOW(), NOW()),
('France',        NOW(), NOW()),
('Sweden',        NOW(), NOW()),
('South Korea',   NOW(), NOW()),
('Australia',     NOW(), NOW()),
('Canada',        NOW(), NOW()),
('Brazil',        NOW(), NOW());

-- ---------------------------------------------------------
-- STEP 2. Release + ChartScore 500만 건 삽입 프로시저
-- ---------------------------------------------------------

DROP PROCEDURE IF EXISTS insert_dummy_releases;

DELIMITER $$

CREATE PROCEDURE insert_dummy_releases()
BEGIN
    DECLARE i          INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE total      INT DEFAULT 5000000;
    DECLARE artist_count INT DEFAULT 1000;
    DECLARE min_release_id BIGINT;

    WHILE i <= total DO

        -- ── Release 배치 ──────────────────────────────────────
        INSERT INTO releases (artist_id, location_id, title, release_type, release_date,
                              catalog_number, label, status, created_at, updated_at)
        WITH RECURSIVE seq (n) AS (
            SELECT i UNION ALL SELECT n + 1 FROM seq WHERE n < i + batch_size - 1
        )
        SELECT
            (n MOD artist_count) + 1,
            (n MOD 10) + 1,
            CONCAT('Release_', n),
            ELT(1 + (n MOD 4), 'ALBUM', 'SINGLE', 'EP', 'COMPILATION'),
            CASE
                WHEN (n MOD 100) < 20 THEN DATE_ADD('1990-01-01', INTERVAL (n MOD 3652) DAY)
                WHEN (n MOD 100) < 50 THEN DATE_ADD('2000-01-01', INTERVAL (n MOD 3652) DAY)
                WHEN (n MOD 100) < 85 THEN DATE_ADD('2010-01-01', INTERVAL (n MOD 3652) DAY)
                ELSE                       DATE_ADD('2020-01-01', INTERVAL (n MOD 1826) DAY)
            END,
            CONCAT('CAT-', n),
            CONCAT('Label_', (n MOD 50) + 1),
            'ACTIVE',
            NOW(),
            NOW()
        FROM seq;

        -- ── 방금 삽입된 Release의 시작 ID 기준 ───────────────
        SET min_release_id = LAST_INSERT_ID();

        -- ── ChartScore 배치 (release_id = 방금 삽입된 ID 순서) ──
        INSERT INTO chart_scores (release_id, total_ratings, effective_votes,
                                  weighted_avg_rating, bayesian_score, is_esoteric,
                                  last_updated, created_at, updated_at)
        WITH RECURSIVE seq (n) AS (
            SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < batch_size - 1
        )
        SELECT
            min_release_id + n,
            FLOOR(RAND() * 10000),
            ROUND(RAND() * 5000, 2),
            ROUND(2 + RAND() * 3, 2),
            ROUND(2 + RAND() * 3, 4),
            ((min_release_id + n) MOD 20 = 0),
            NOW(), NOW(), NOW()
        FROM seq;

        -- ── ReleaseGenre 배치 ─────────────────────────────────
        -- 장르 분포: Rock(1)=40%, Pop(2)=25%, Electronic(3)=10%, 기타=25%
        INSERT INTO release_genres (release_id, genre_id, is_primary, `order`, created_at)
        WITH RECURSIVE seq (n) AS (
            SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < batch_size - 1
        )
        SELECT
            min_release_id + n,
            CASE
                WHEN ((min_release_id + n) MOD 100) < 40 THEN 1
                WHEN ((min_release_id + n) MOD 100) < 65 THEN 2
                WHEN ((min_release_id + n) MOD 100) < 75 THEN 3
                ELSE ((min_release_id + n) MOD 7) + 4
            END,
            1,   -- is_primary
            0,   -- order
            NOW()
        FROM seq;

        -- ── ReleaseDescriptor 배치 ────────────────────────────
        INSERT INTO release_descriptors (release_id, descriptor_id, `order`, created_at)
        WITH RECURSIVE seq (n) AS (
            SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < batch_size - 1
        )
        SELECT
            min_release_id + n,
            ((min_release_id + n) MOD 10) + 1,
            0,
            NOW()
        FROM seq;



        -- ── ReleaseLanguage 배치 ──────────────────────────────
        INSERT INTO release_languages (release_id, language, created_at)
        WITH RECURSIVE seq (n) AS (
            SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < batch_size - 1
        )
        SELECT
            min_release_id + n,
            ELT(1 + ((min_release_id + n) MOD 5), 'EN', 'KO', 'JA', 'DE', 'FR'),
            NOW()
        FROM seq;

        SET i = i + batch_size;

    END WHILE;

    SELECT '✅ 더미 데이터 삽입 완료' AS result;
END$$

DELIMITER ;
