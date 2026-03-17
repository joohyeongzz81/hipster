package com.hipster.reward.repository;

import com.hipster.reward.domain.RewardCampaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RewardCampaignRepository extends JpaRepository<RewardCampaign, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select campaign from RewardCampaign campaign where campaign.code = :code")
    Optional<RewardCampaign> findByCodeForUpdate(@Param("code") String code);
}
