package com.hipster.user.repository;

import com.hipster.user.domain.UserWeightStats;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWeightStatsRepository extends JpaRepository<UserWeightStats, Long> {

    void deleteByUserId(Long userId);
}
