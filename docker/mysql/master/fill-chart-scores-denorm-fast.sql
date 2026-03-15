USE hipster;

DROP PROCEDURE IF EXISTS fill_chart_scores_denorm_fast;

DELIMITER $$

CREATE PROCEDURE fill_chart_scores_denorm_fast(
    IN p_batch_size INT,
    IN p_multi_genre_percent INT,
    IN p_seed VARCHAR(64)
)
BEGIN
    DECLARE v_min_release_id BIGINT DEFAULT 0;
    DECLARE v_max_release_id BIGINT DEFAULT 0;
    DECLARE v_start_release_id BIGINT DEFAULT 0;

    IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
        SET p_batch_size = 50000;
    END IF;

    IF p_multi_genre_percent IS NULL OR p_multi_genre_percent < 0 THEN
        SET p_multi_genre_percent = 0;
    END IF;

    IF p_multi_genre_percent > 100 THEN
        SET p_multi_genre_percent = 100;
    END IF;

    IF p_seed IS NULL OR CHAR_LENGTH(p_seed) = 0 THEN
        SET p_seed = 'chart-denorm-fast-v1';
    END IF;

    SELECT IFNULL(MIN(release_id), 0), IFNULL(MAX(release_id), 0)
      INTO v_min_release_id, v_max_release_id
      FROM chart_scores;

    SET v_start_release_id = v_min_release_id;

    WHILE v_start_release_id <= v_max_release_id DO
        UPDATE chart_scores cs
        SET
            cs.release_type = ELT(1 + (cs.release_id MOD 4), 'ALBUM', 'SINGLE', 'EP', 'COMPILATION'),
            cs.release_year = YEAR(
                CASE
                    WHEN (cs.release_id MOD 100) < 20 THEN DATE_ADD('1990-01-01', INTERVAL (cs.release_id MOD 3652) DAY)
                    WHEN (cs.release_id MOD 100) < 50 THEN DATE_ADD('2000-01-01', INTERVAL (cs.release_id MOD 3652) DAY)
                    WHEN (cs.release_id MOD 100) < 85 THEN DATE_ADD('2010-01-01', INTERVAL (cs.release_id MOD 3652) DAY)
                    ELSE DATE_ADD('2020-01-01', INTERVAL (cs.release_id MOD 1826) DAY)
                END
            ),
            cs.location_id = (cs.release_id MOD 10) + 1,
            cs.genre_ids = CASE
                WHEN MOD(CRC32(CONCAT(cs.release_id, ':', p_seed)), 100) < p_multi_genre_percent THEN
                    JSON_ARRAY(
                        JSON_OBJECT(
                            'id',
                            CASE
                                WHEN (cs.release_id MOD 100) < 40 THEN 1
                                WHEN (cs.release_id MOD 100) < 65 THEN 2
                                WHEN (cs.release_id MOD 100) < 75 THEN 3
                                ELSE (cs.release_id MOD 7) + 4
                            END,
                            'isPrimary',
                            TRUE
                        ),
                        JSON_OBJECT(
                            'id',
                            MOD(
                                CASE
                                    WHEN (cs.release_id MOD 100) < 40 THEN 1
                                    WHEN (cs.release_id MOD 100) < 65 THEN 2
                                    WHEN (cs.release_id MOD 100) < 75 THEN 3
                                    ELSE (cs.release_id MOD 7) + 4
                                END + 5,
                                10
                            ) + 1,
                            'isPrimary',
                            FALSE
                        )
                    )
                ELSE
                    JSON_ARRAY(
                        JSON_OBJECT(
                            'id',
                            CASE
                                WHEN (cs.release_id MOD 100) < 40 THEN 1
                                WHEN (cs.release_id MOD 100) < 65 THEN 2
                                WHEN (cs.release_id MOD 100) < 75 THEN 3
                                ELSE (cs.release_id MOD 7) + 4
                            END,
                            'isPrimary',
                            TRUE
                        )
                    )
            END,
            cs.descriptor_ids = JSON_ARRAY((cs.release_id MOD 10) + 1),
            cs.languages = JSON_ARRAY(ELT(1 + (cs.release_id MOD 5), 'EN', 'KO', 'JA', 'DE', 'FR')),
            cs.updated_at = NOW(6)
        WHERE cs.release_id >= v_start_release_id
          AND cs.release_id < v_start_release_id + p_batch_size;

        SET v_start_release_id = v_start_release_id + p_batch_size;
    END WHILE;

    DELETE FROM release_genres WHERE is_primary = b'0';

    INSERT INTO release_genres (created_at, is_primary, `order`, genre_id, release_id)
    SELECT
        NOW(6),
        b'0',
        1,
        MOD(
            CASE
                WHEN (r.id MOD 100) < 40 THEN 1
                WHEN (r.id MOD 100) < 65 THEN 2
                WHEN (r.id MOD 100) < 75 THEN 3
                ELSE (r.id MOD 7) + 4
            END + 5,
            10
        ) + 1,
        r.id
    FROM releases r
    WHERE MOD(CRC32(CONCAT(r.id, ':', p_seed)), 100) < p_multi_genre_percent
      AND NOT EXISTS (
          SELECT 1
          FROM release_genres rg
          WHERE rg.release_id = r.id
            AND rg.is_primary = b'0'
      );

    SELECT
        (SELECT COUNT(*) FROM chart_scores) AS chart_scores_count,
        (SELECT COUNT(*) FROM chart_scores WHERE release_type IS NOT NULL) AS filled_release_type_count,
        (SELECT COUNT(*) FROM chart_scores WHERE JSON_LENGTH(genre_ids) >= 2) AS multi_genre_chart_scores_count,
        (SELECT COUNT(*) FROM release_genres WHERE is_primary = b'0') AS secondary_release_genres_count;
END$$

DELIMITER ;
