-- =========================================================
-- 별도 분리: ReleaseRatingSummary 데이터 삽입 프로시저
-- =========================================================

USE hipster;

DROP PROCEDURE IF EXISTS insert_dummy_rating_summary;

DELIMITER $$

CREATE PROCEDURE insert_dummy_rating_summary()
BEGIN
    DECLARE v_id BIGINT DEFAULT 0;
    DECLARE v_max_id BIGINT DEFAULT 0;
    DECLARE batch_size INT DEFAULT 50000;

    -- 기존 releases 테이블의 최대 ID 확인
    SELECT IFNULL(MAX(id), 0) INTO v_max_id FROM releases;

    WHILE v_id <= v_max_id DO
        -- ── ReleaseRatingSummary 배치 ────────────────────────
        -- 이미 있는 releases.id 를 참조하여 삽입함으로써 Auto_Increment 갭 등에 영향받지 않고 안전함
        INSERT INTO release_rating_summary (release_id, total_rating_count, average_score, weighted_score_sum, weighted_count_sum, batch_synced_at, updated_at)
        SELECT 
            id,
            ROUND(RAND() * 500),
            ROUND(RAND() * 5, 2),
            ROUND(RAND() * 50000, 4),
            ROUND(RAND() * 10000, 4),
            NOW(),
            NOW()
        FROM releases
        WHERE id > v_id AND id <= v_id + batch_size;

        SET v_id = v_id + batch_size;
    END WHILE;

    SELECT '✅ 별도 평점 요약 더미 데이터 500만건 삽입 완료' AS result;
END$$

DELIMITER ;
